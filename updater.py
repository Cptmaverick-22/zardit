"""Keeps yt-dlp up to date without pip.

The newest yt-dlp (and the yt-dlp-ejs helper it needs) is downloaded from PyPI
into the user's config folder. At start-up, activate() puts that copy ahead of
the installed or bundled one. If it can't be loaded it is removed, and the
built-in copy is used instead.

Standard library only. Importing this module must not import yt-dlp, because
activate() has to run before anything else imports it.
"""

from __future__ import annotations

import hashlib
import http.client
import importlib
import importlib.metadata
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

from config import CONFIG_DIR, load_settings, remember_setting

log = logging.getLogger("zardit")
log.addHandler(logging.NullHandler())

UPDATE_DIR = CONFIG_DIR / "updates"

PYPI_JSON = "https://pypi.org/pypi/{name}/json"
PYPI_VERSION_JSON = "https://pypi.org/pypi/{name}/{version}/json"
TRUSTED_HOSTS = ("pypi.org", "files.pythonhosted.org")

USER_AGENT = "zardit-updater"
TIMEOUT = 30              # seconds, for a deliberate update
QUIET_TIMEOUT = 5         # seconds, for the background check
CHUNK = 64 * 1024
MAX_JSON_BYTES = 30 * 1024 * 1024
MAX_WHEEL_BYTES = 50 * 1024 * 1024
MAX_UNPACKED_BYTES = 200 * 1024 * 1024
CHECK_INTERVAL = 24 * 60 * 60

NETWORK_ERROR = (
    "Couldn't download the update. Check your internet connection "
    "and try again."
)


class UpdateError(Exception):
    """A problem described by a message that is safe to show to the user."""


def _new_status():
    return {
        "active": False,       # running from a downloaded update
        "dir": None,           # version name of that update's folder
        "folder": None,
        "failed": None,        # why a downloaded update could not be used
        "superseded": False,   # the built-in copy turned out to be newer
        "activated": False,    # activate() has already run in this process
    }


_status = _new_status()

# Reading, changing and writing the state file must not interleave between
# threads (the background check and an install can run at the same time).
_state_lock = threading.Lock()


@dataclass
class Wheel:
    name: str
    version: str
    url: str
    sha256: str
    size: int


@dataclass
class UpdateInfo:
    current: str                 # the version running now
    latest: str                  # the newest version on PyPI
    available: bool              # latest is newer than anything we have
    pending_restart: bool        # an update is installed but not running yet
    installed: str | None        # the downloaded update on disk, if any
    wheel: Wheel | None          # set when an update is available
    ejs: tuple                   # (is yt-dlp-ejs required, its pinned version)


# --------------------------------------------------------------------------
# Folders and state
# --------------------------------------------------------------------------

def _state_file():
    return UPDATE_DIR / "state.json"


def _staging():
    return UPDATE_DIR / "staging"


def _valid_name(name):
    """A version string that is safe to use in a folder name."""

    return bool(re.fullmatch(r"[0-9A-Za-z.+_-]{1,40}", str(name)))


def _read_state():
    try:
        data = json.loads(_state_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}

    return data if isinstance(data, dict) else {}


def _write_state(**changes):
    """Change some state values. A value of None removes the key."""


    with _state_lock:
        state = _read_state()

        for key, value in changes.items():
            if value is None:
                state.pop(key, None)
            else:
                state[key] = value

        try:
            UPDATE_DIR.mkdir(parents=True, exist_ok=True)
            temporary = _state_file().with_suffix(".tmp")
            temporary.write_text(json.dumps(state, indent=2), encoding="utf-8")
            temporary.replace(_state_file())
        except OSError:
            log.warning("Could not save the update state", exc_info=True)


def _cleanup_old(keep):
    """Delete downloaded versions except the ones named in keep."""

    try:
        children = list(UPDATE_DIR.iterdir())
    except OSError:
        return

    for child in children:
        if (
            child.is_dir()
            and child.name.startswith("v")
            and child.name[1:] not in keep
        ):
            shutil.rmtree(child, ignore_errors=True)


# --------------------------------------------------------------------------
# Versions
# --------------------------------------------------------------------------

def parse_version(text):
    """'2026.10.26' -> (2026, 10, 26). Unknown text gives ()."""

    match = re.match(r"[0-9]+(?:\.[0-9]+)*", str(text or ""))

    return tuple(int(part) for part in match.group(0).split(".")) if match else ()


def python_supported(requires, version=None):
    """True if a 'requires_python' string such as '>=3.10' allows this Python."""

    current = tuple(version or sys.version_info[:3])

    for clause in (requires or "").split(","):
        match = re.fullmatch(
            r"\s*(>=|<=|==|!=|>|<)\s*([0-9]+(?:\.[0-9]+)*)(\.\*)?\s*", clause
        )

        if not match:
            continue

        operator, number, wildcard = match.groups()
        wanted = tuple(int(part) for part in number.split("."))

        if wildcard:
            same = current[:len(wanted)] == wanted

            if (operator == "==" and not same) or (operator == "!=" and same):
                return False

            continue

        size = max(len(current), len(wanted))
        have = current + (0,) * (size - len(current))
        need = wanted + (0,) * (size - len(wanted))

        allowed = {
            ">=": have >= need, "<=": have <= need,
            ">": have > need, "<": have < need,
            "==": have == need, "!=": have != need,
        }[operator]

        if not allowed:
            return False

    return True


def current_version():
    """The yt-dlp version this process is running."""

    from yt_dlp.version import __version__

    return __version__


def _installed_version():
    """The version of the yt-dlp installed with the app, if it can be told."""

    try:
        return importlib.metadata.version("yt-dlp")
    except Exception:
        return None


def _package_version(folder):
    """The yt-dlp version inside an unpacked folder, read without importing."""

    try:
        text = (folder / "yt_dlp" / "version.py").read_text(encoding="utf-8")
    except OSError:
        return None

    match = re.search(r"^__version__\s*=\s*['\"]([^'\"]+)['\"]", text, re.M)

    return match.group(1) if match else None


def update_version():
    """The downloaded update that will be used at the next start, or None."""

    name = _read_state().get("active")

    if (
        isinstance(name, str)
        and _valid_name(name)
        and (UPDATE_DIR / f"v{name}" / "yt_dlp").is_dir()
    ):
        return name

    return None


def update_active():
    """True if this run uses a downloaded update."""

    return _status["active"]


# --------------------------------------------------------------------------
# Loading an update at start-up
# --------------------------------------------------------------------------

def _purge_modules():
    for name in list(sys.modules):
        if name.split(".")[0] in ("yt_dlp", "yt_dlp_ejs"):
            del sys.modules[name]


def _remove_from_path(folder):
    try:
        sys.path.remove(str(folder))
    except ValueError:
        pass


def _discard(name, reason):
    """Forget a downloaded update. With a reason, remember why."""

    if _valid_name(name):
        shutil.rmtree(UPDATE_DIR / f"v{name}", ignore_errors=True)

    if reason:
        _status["failed"] = reason
        _write_state(
            active=None,
            failed={"version": str(name), "reason": reason,
                    "time": time.time()},
        )
    else:
        _write_state(active=None)


def activate():
    """Put the downloaded yt-dlp, if there is one, ahead of the installed copy.

    Call this before anything imports yt-dlp. A copy that can't be loaded is
    deleted, so a bad update can never leave the app unable to start.
    Only the first call in a process does anything.
    """

    if _status["activated"]:
        return

    _status["activated"] = True

    name = _read_state().get("active")

    if not name:
        return

    if "yt_dlp" in sys.modules:
        log.warning("yt-dlp was imported before the update could be loaded")
        return

    if not (isinstance(name, str) and _valid_name(name)):
        _discard(name, "the update record is damaged")
        return

    folder = UPDATE_DIR / f"v{name}"

    if not (folder / "yt_dlp").is_dir():
        _discard(name, "its files are missing")
        return

    version = _package_version(folder) or name
    installed = _installed_version()

    if installed and parse_version(installed) >= parse_version(version):
        # The app itself now ships a yt-dlp that is at least as new.
        _status["superseded"] = True
        _discard(name, None)
        return

    sys.path.insert(0, str(folder))

    try:
        importlib.invalidate_caches()

        import yt_dlp
        import yt_dlp.version

        origin = Path(yt_dlp.__file__).resolve()

        if folder.resolve() not in origin.parents:
            raise ImportError(f"yt-dlp was loaded from {origin}")

        loaded = yt_dlp.version.__version__

    except Exception as error:
        _remove_from_path(folder)
        _purge_modules()
        _discard(name, f"{type(error).__name__}: {error}")
        return

    _status.update(active=True, version=loaded, dir=name, folder=str(folder))


def describe():
    """One line for the log and the diagnostics report."""

    try:
        version = current_version()
    except Exception:
        version = "unknown"

    if _status["active"]:
        text = f"{version} (downloaded update in {_status['folder']})"
    else:
        text = f"{version} (installed or bundled copy)"

    pending = update_version()

    if pending and parse_version(pending) > parse_version(version):
        text += f"; {pending} will be used after a restart"

    if _status["failed"]:
        text += f"; a downloaded update was removed: {_status['failed']}"
    elif _status["superseded"]:
        text += (
            "; the downloaded update was removed because the installed "
            "copy is at least as new"
        )

    return text


# --------------------------------------------------------------------------
# Talking to PyPI
# --------------------------------------------------------------------------

def _require_trusted(url):
    parsed = urlparse(str(url))
    host = (parsed.hostname or "").lower()

    if parsed.scheme != "https" or host not in TRUSTED_HOSTS:
        raise UpdateError("The download address isn't a trusted PyPI address.")


def _open(url, accept="*/*", timeout=TIMEOUT):
    """Open a PyPI address. Redirects to other hosts are refused."""

    _require_trusted(url)

    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": accept}
    )
    response = urllib.request.urlopen(request, timeout=timeout)

    try:
        _require_trusted(response.geturl())
    except UpdateError:
        response.close()
        raise

    return response


def _get_json(url, timeout=TIMEOUT):
    try:
        with _open(url, "application/json", timeout) as response:
            data = response.read(MAX_JSON_BYTES + 1)

    except urllib.error.HTTPError as error:
        raise UpdateError(
            f"PyPI answered with an error (HTTP {error.code})."
        ) from error

    except (OSError, http.client.HTTPException) as error:
        raise UpdateError(
            "Couldn't reach PyPI. Check your internet connection."
        ) from error

    if len(data) > MAX_JSON_BYTES:
        raise UpdateError("PyPI sent an unexpectedly large answer.")

    try:
        result = json.loads(data)
    except ValueError as error:
        raise UpdateError("PyPI sent an unexpected answer.") from error

    if not isinstance(result, dict):
        raise UpdateError("PyPI sent an unexpected answer.")

    return result


def _pick_wheel(data, name):
    """The installable wheel of a PyPI release, with its checksum."""

    info = data.get("info") or {}
    version = str(info.get("version") or "")

    for entry in data.get("urls") or []:
        filename = str(entry.get("filename") or "")

        if (
            entry.get("packagetype") != "bdist_wheel"
            or entry.get("yanked")
            or not filename.endswith("none-any.whl")
        ):
            continue

        url = str(entry.get("url") or "")
        sha256 = str((entry.get("digests") or {}).get("sha256") or "").lower()

        if not url or not re.fullmatch(r"[0-9a-f]{64}", sha256):
            continue

        requires = entry.get("requires_python") or info.get("requires_python")

        if not python_supported(requires):
            raise UpdateError(
                f"{name} {version} doesn't support this Python "
                f"({sys.version.split()[0]})."
            )

        return Wheel(name, version, url, sha256, int(entry.get("size") or 0))

    raise UpdateError(f"No installable release of {name} was found on PyPI.")


EJS_REQUIREMENT = re.compile(
    r"^\s*yt[-_.]dlp[-_.]ejs\s*(?:\[[^\]]*\])?\s*"
    r"(==|>=|~=|<=|>|<)?\s*([0-9][0-9A-Za-z.+-]*)?",
    re.IGNORECASE,
)


def ejs_requirement(requires_dist):
    """(is yt-dlp-ejs required, the exact version asked for or None)."""

    for line in requires_dist or []:
        match = EJS_REQUIREMENT.match(str(line))

        if match:
            exact = match.group(2) if match.group(1) == "==" else None
            return True, exact

    return False, None


def check_for_update(timeout=TIMEOUT):
    """Ask PyPI for the newest yt-dlp. Raises UpdateError on problems."""

    data = _get_json(PYPI_JSON.format(name="yt-dlp"), timeout)
    info = data.get("info") or {}
    latest = str(info.get("version") or "")

    if not parse_version(latest) or not _valid_name(latest):
        raise UpdateError("PyPI sent an unexpected answer.")

    running = current_version()
    on_disk = update_version()

    newest_known = max(parse_version(running), parse_version(on_disk))
    available = parse_version(latest) > newest_known
    pending = bool(on_disk) and parse_version(on_disk) > parse_version(running)

    _write_state(checked=time.time(), latest=latest)

    return UpdateInfo(
        current=running,
        latest=latest,
        available=available,
        pending_restart=pending,
        installed=on_disk,
        wheel=_pick_wheel(data, "yt-dlp") if available else None,
        ejs=ejs_requirement(info.get("requires_dist")),
    )


# --------------------------------------------------------------------------
# Installing an update
# --------------------------------------------------------------------------

def _download(wheel, folder, report):
    """Download a wheel and check its checksum. Returns the file's path."""

    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{wheel.name}-{wheel.version}.whl"

    digest = hashlib.sha256()
    done = 0
    last_report = 0.0

    try:
        response = _open(wheel.url)
    except (OSError, http.client.HTTPException) as error:
        raise UpdateError(NETWORK_ERROR) from error

    with response:
        try:
            with open(path, "wb") as output:
                while True:
                    try:
                        chunk = response.read(CHUNK)
                    except (OSError, http.client.HTTPException) as error:
                        raise UpdateError(NETWORK_ERROR) from error

                    if not chunk:
                        break

                    done += len(chunk)

                    if done > MAX_WHEEL_BYTES:
                        raise UpdateError(
                            "The download is larger than expected."
                        )

                    digest.update(chunk)
                    output.write(chunk)

                    now = time.monotonic()

                    if now - last_report >= 0.2:
                        last_report = now
                        amount = (
                            f"{done * 100 // wheel.size}%"
                            if wheel.size else f"{done // 1024} KB"
                        )
                        report(
                            f"Downloading {wheel.name} {wheel.version}… "
                            f"{amount}"
                        )

        except OSError as error:
            raise UpdateError(f"Couldn't save the download: {error}") from error

    if digest.hexdigest() != wheel.sha256:
        raise UpdateError(
            f"The downloaded {wheel.name} file is damaged (its checksum "
            "doesn't match). Please try again."
        )

    return path


def _extract_wheel(path, target):
    """Unpack a wheel into target, refusing anything that escapes it."""

    target.mkdir(parents=True, exist_ok=True)
    root = target.resolve()
    unpacked = 0

    try:
        with zipfile.ZipFile(path) as bundle:
            for member in bundle.infolist():
                name = member.filename
                orig_name = getattr(member, "orig_filename", name)

                if member.is_dir():
                    continue

                posix = PurePosixPath(name)

                if (
                    "\\" in name
                    or "\\" in orig_name
                    or posix.is_absolute()
                    or ".." in posix.parts
                    or any(":" in part for part in posix.parts)
                ):
                    raise UpdateError(
                        "The downloaded package contains an unsafe file name."
                    )

                if posix.parts[0].endswith(".data"):
                    continue    # Man pages and similar, not needed here.

                destination = root.joinpath(*posix.parts).resolve()

                if root not in destination.parents:
                    raise UpdateError(
                        "The downloaded package contains an unsafe file name."
                    )

                destination.parent.mkdir(parents=True, exist_ok=True)

                with bundle.open(member) as source, \
                        open(destination, "wb") as output:
                    while True:
                        chunk = source.read(CHUNK)

                        if not chunk:
                            break

                        unpacked += len(chunk)

                        if unpacked > MAX_UNPACKED_BYTES:
                            raise UpdateError(
                                "The downloaded package is larger than "
                                "expected."
                            )

                        output.write(chunk)

    except zipfile.BadZipFile as error:
        raise UpdateError("The downloaded file isn't a valid package.") from error

    except OSError as error:
        raise UpdateError(f"Couldn't unpack the update: {error}") from error


SMOKE_TEST = (
    "import os, sys\n"
    "sys.path.insert(0, sys.argv[1])\n"
    "import yt_dlp, yt_dlp.version\n"
    "assert os.path.realpath(yt_dlp.__file__).startswith("
    "os.path.realpath(sys.argv[1])), yt_dlp.__file__\n"
    "print(yt_dlp.version.__version__)\n"
)


def _smoke_test(folder):
    """Check that the new copy imports with this Python before using it.

    Skipped in a packaged app, where sys.executable is the app itself and
    can't run a script. There, activate() performs the same check at the next
    start and removes a copy that fails.
    """

    if getattr(sys, "frozen", False):
        return

    options = {}

    if sys.platform == "win32":
        options["creationflags"] = 0x08000000   # No console window.

    try:
        result = subprocess.run(
            [sys.executable, "-c", SMOKE_TEST, str(folder)],
            capture_output=True, text=True, timeout=120, **options,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise UpdateError(f"Couldn't test the new version: {error}") from error

    if result.returncode != 0:
        log.error("The new yt-dlp failed its test:\n%s", result.stderr)
        raise UpdateError(
            "The new version doesn't work with this Python, so it was not "
            "installed."
        )


def install_update(report=None, info=None):
    """Download, check and install the newest yt-dlp.

    Returns the installed version, or None if nothing newer exists. The new
    version is used after the next start. Raises UpdateError on problems.
    """

    report = report or (lambda text: None)

    if info is None:
        report("Checking for updates…")
        info = check_for_update()

    if not info.available or info.wheel is None:
        return None

    version = info.latest
    staging = _staging()

    shutil.rmtree(staging, ignore_errors=True)

    try:
        wheels = [info.wheel]
        required, pinned = info.ejs

        if required:
            report("Checking yt-dlp-ejs…")

            url = (
                PYPI_VERSION_JSON.format(name="yt-dlp-ejs", version=pinned)
                if pinned else PYPI_JSON.format(name="yt-dlp-ejs")
            )
            ejs = _pick_wheel(_get_json(url), "yt-dlp-ejs")

            if pinned and ejs.version != pinned:
                raise UpdateError("PyPI sent an unexpected yt-dlp-ejs release.")

            wheels.append(ejs)

        unpacked = staging / "unpacked"

        for wheel in wheels:
            path = _download(wheel, staging / "downloads", report)
            report(f"Unpacking {wheel.name}…")
            _extract_wheel(path, unpacked)

        found = _package_version(unpacked)

        if found != version:
            raise UpdateError(
                f"The package contains yt-dlp {found}, not {version}."
            )

        report("Testing the new version…")
        _smoke_test(unpacked)

        UPDATE_DIR.mkdir(parents=True, exist_ok=True)
        target = UPDATE_DIR / f"v{version}"
        shutil.rmtree(target, ignore_errors=True)

        try:
            os.replace(unpacked, target)
        except OSError as error:
            raise UpdateError(f"Couldn't install the update: {error}") from error

        _write_state(active=version, installed=time.time(), failed=None)

        keep = {version}

        if _status["dir"]:
            keep.add(_status["dir"])    # The running copy stays on disk.

        _cleanup_old(keep)

    finally:
        shutil.rmtree(staging, ignore_errors=True)

    log.info("Installed yt-dlp %s; it will be used after a restart", version)

    return version


def remove_update():
    """Go back to the built-in yt-dlp at the next start.

    Returns True if there was a downloaded update.
    """

    had_update = update_version() is not None

    _write_state(active=None)
    _cleanup_old({_status["dir"]} if _status["dir"] else set())

    return had_update


# --------------------------------------------------------------------------
# Daily check and restarting
# --------------------------------------------------------------------------

def auto_check_enabled():
    return load_settings().get("check_updates", True) is not False


def set_auto_check(enabled):
    try:
        remember_setting("check_updates", bool(enabled))
    except OSError:
        pass


def check_due(now=None):
    checked = _read_state().get("checked")

    if not isinstance(checked, (int, float)):
        return True

    return (now if now is not None else time.time()) - checked >= CHECK_INTERVAL


def cached_update():
    """The newer version found by the last check, if it is still newer."""

    latest = _read_state().get("latest")

    if not isinstance(latest, str):
        return None

    newest_known = max(
        parse_version(current_version()), parse_version(update_version())
    )

    return latest if parse_version(latest) > newest_known else None


def background_check():
    """The once-a-day check. Returns a newer version or None; never raises.

    A network request is made at most once a day. In between, the answer of
    the last check is used.
    """

    try:
        if not auto_check_enabled():
            return None

        if check_due():
            info = check_for_update(QUIET_TIMEOUT)

            return info.latest if info.available else None

        return cached_update()

    except Exception:
        log.debug("The update check failed", exc_info=True)

        # Don't try again at every start while offline.
        _write_state(checked=time.time())

        return None


def restart_command():
    if getattr(sys, "frozen", False):
        appimage = os.environ.get("APPIMAGE")
        if appimage:
            return [appimage, *sys.argv[1:]]
        return [sys.executable, *sys.argv[1:]]

    return [sys.executable, *sys.argv]


def restart():
    """Start a fresh copy of the app. Returns True if it was launched."""

    options = {}

    if sys.platform == "win32":
        options["creationflags"] = 0x00000008 | 0x00000200   # Detached.
    else:
        options["start_new_session"] = True

    try:
        subprocess.Popen(restart_command(), **options)
    except OSError:
        return False

    return True