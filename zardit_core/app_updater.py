"""In-app Zardit application updater with SHA-256 verification and build-fingerprint checks."""

from __future__ import annotations

import hashlib
import http.client
import json
import logging
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

import config

log = logging.getLogger("zardit")

GITHUB_REPO = "Cptmaverick-22/zardit"
RELEASES_LATEST_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
COMMIT_API = f"https://api.github.com/repos/{GITHUB_REPO}/commits/{{ref}}"
RELEASES_PAGE_URL = f"https://github.com/{GITHUB_REPO}/releases"

TRUSTED_APP_HOSTS = (
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

USER_AGENT = f"Zardit-AppUpdater/{config.APP_VERSION}"
TIMEOUT = 15
QUIET_TIMEOUT = 6
CHECK_INTERVAL = 24 * 60 * 60
SAME_TAG_GRACE_SEC = 20 * 60
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_SUMS_BYTES = 256 * 1024
MAX_ASSET_BYTES = 250 * 1024 * 1024
MAX_UNPACKED_BYTES = 500 * 1024 * 1024
CHUNK = 64 * 1024

_state_lock = threading.Lock()


class AppUpdateError(Exception):
    """User-safe error during app update checking, downloading, or staging."""


@dataclass
class ReleaseAsset:
    name: str
    url: str
    sha256: str
    size: int
    updated_at: int


@dataclass
class AppUpdateInfo:
    current_version: str
    latest_version: str
    tag_name: str
    available: bool
    same_tag_hotfix: bool
    release_url: str
    release_notes: str
    asset: ReleaseAsset | None
    install_mode: str
    remote_commit: str = ""


def _state_dir() -> Path:
    return config.CONFIG_DIR / "app_updates"


def _state_file() -> Path:
    return _state_dir() / "state.json"


def _read_state() -> dict:
    with _state_lock:
        try:
            data = json.loads(_state_file().read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}


def _write_state(**changes) -> None:
    with _state_lock:
        try:
            _state_dir().mkdir(parents=True, exist_ok=True)
            path = _state_file()
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                if not isinstance(data, dict):
                    data = {}
            except (OSError, ValueError):
                data = {}
            data.update(changes)
            tmp = path.with_suffix(".tmp")
            tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
            os.replace(tmp, path)
        except OSError:
            pass


def auto_check_enabled() -> bool:
    return config.load_settings().get("check_updates", True) is not False


def set_auto_check(enabled: bool) -> None:
    config.remember_setting("check_updates", bool(enabled))


def parse_app_version(version_text: str) -> tuple[int, ...]:
    cleaned = str(version_text or "").strip().lstrip("vV")
    parts = [int(p) for p in re.findall(r"\d+", cleaned)]
    return tuple(parts) if parts else (0,)


def parse_iso_timestamp(text: str) -> int:
    raw = str(text or "").strip()
    if not raw:
        return 0
    try:
        if raw.endswith("Z"):
            raw = raw[:-1] + "+00:00"
        return int(datetime.fromisoformat(raw).timestamp())
    except ValueError:
        return 0


def parse_sha256sums(text: str) -> dict[str, str]:
    sums: dict[str, str] = {}
    for line in str(text or "").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+)$", line)
        if match:
            digest = match.group(1).lower()
            name = Path(match.group(2).strip()).name
            if name:
                sums[name] = digest
    return sums


def require_trusted_app_url(url: str) -> None:
    parsed = urlparse(str(url or "").strip())
    host = (parsed.hostname or "").lower()
    if (
        parsed.scheme != "https"
        or parsed.username is not None
        or parsed.password is not None
        or host not in TRUSTED_APP_HOSTS
    ):
        raise AppUpdateError("Untrusted download host refused.")


class _TrustedRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require_trusted_app_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_OPENER = urllib.request.build_opener(_TrustedRedirectHandler())


def _open_trusted(url: str, accept: str = "*/*", timeout: int = TIMEOUT):
    require_trusted_app_url(url)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": accept},
    )
    response = _OPENER.open(request, timeout=timeout)
    try:
        require_trusted_app_url(response.geturl())
    except AppUpdateError:
        response.close()
        raise
    return response


def _get_json(url: str, timeout: int = TIMEOUT) -> dict:
    try:
        with _open_trusted(url, "application/vnd.github+json", timeout) as resp:
            data = resp.read(MAX_JSON_BYTES + 1)
    except urllib.error.HTTPError as err:
        raise AppUpdateError(f"GitHub returned HTTP {err.code}.") from err
    except (OSError, http.client.HTTPException) as err:
        raise AppUpdateError("Could not reach GitHub. Check your internet connection.") from err

    if len(data) > MAX_JSON_BYTES:
        raise AppUpdateError("GitHub response exceeded safe size limit.")
    try:
        parsed = json.loads(data.decode("utf-8", errors="replace"))
    except ValueError as err:
        raise AppUpdateError("Unexpected JSON response from GitHub.") from err
    if not isinstance(parsed, dict):
        raise AppUpdateError("Unexpected release payload from GitHub.")
    return parsed


def _get_text(url: str, timeout: int = TIMEOUT) -> str:
    try:
        with _open_trusted(url, "text/plain", timeout) as resp:
            data = resp.read(MAX_SUMS_BYTES + 1)
    except urllib.error.HTTPError as err:
        raise AppUpdateError(f"Could not read checksums (HTTP {err.code}).") from err
    except (OSError, http.client.HTTPException) as err:
        raise AppUpdateError("Could not download checksums from GitHub.") from err

    if len(data) > MAX_SUMS_BYTES:
        raise AppUpdateError("Checksums file exceeded safe size limit.")
    return data.decode("utf-8", errors="replace")


def detect_install_mode() -> str:
    if sys.platform == "win32":
        if getattr(sys, "frozen", False):
            exe_dir = Path(sys.executable).resolve().parent
            if (exe_dir / "unins000.exe").is_file():
                return "windows_installer"
            return "windows_portable"
        return "windows_source"
    if sys.platform.startswith("linux") and os.environ.get("APPIMAGE"):
        return "linux_appimage"
    return "source"


def _select_platform_asset(
    assets: list[dict],
    checksums: dict[str, str],
    install_mode: str,
) -> ReleaseAsset | None:
    if install_mode in ("windows_installer", "windows_source"):
        suffixes = ("-win64-setup.exe", "-win64-portable.zip")
    elif install_mode == "windows_portable":
        suffixes = ("-win64-portable.zip", "-win64-setup.exe")
    else:
        suffixes = ("-x86_64.AppImage",)

    for suffix in suffixes:
        for item in assets:
            name = str(item.get("name") or "")
            if not name.startswith("Zardit-") or not name.endswith(suffix):
                continue
            url = str(item.get("browser_download_url") or "")
            if not url:
                continue
            digest = checksums.get(name, "")
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                continue
            return ReleaseAsset(
                name=name,
                url=url,
                sha256=digest,
                size=int(item.get("size") or 0),
                updated_at=parse_iso_timestamp(item.get("updated_at") or ""),
            )
    return None


def _clean_markdown_line(raw: str) -> str:
    line = str(raw or "").strip()
    if not line:
        return ""
    if re.fullmatch(r"[-*_]{3,}", line):
        return ""
    line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line)
    line = re.sub(r"^\s*#{1,6}\s*", "", line)
    line = line.replace("**", "").replace("__", "").replace("`", "")
    if re.match(r"^\s*[-*]\s+", line):
        line = "• " + re.sub(r"^\s*[-*]\s+", "", line)
    return re.sub(r"\s+", " ", line).strip()


def _summarize_release_notes(body: str, max_lines: int = 10) -> str:
    lines = []
    for raw in str(body or "").splitlines():
        line = _clean_markdown_line(raw)
        if not line:
            continue
        if not lines and re.match(r"^zardit\s+v?\d+(?:\.\d+)*$", line, re.IGNORECASE):
            continue
        if line.lower().startswith("full changelog:"):
            continue
        lines.append(line)
        if len(lines) >= max_lines:
            break
    return "\n".join(lines)


def _fetch_tag_commit(tag_name: str, timeout: int) -> str:
    if not re.fullmatch(r"[0-9A-Za-z._-]{1,40}", tag_name or ""):
        return ""
    try:
        data = _get_json(COMMIT_API.format(ref=tag_name), timeout=min(timeout, 8))
        sha = str(data.get("sha") or "").strip()
        return sha if re.fullmatch(r"[0-9a-fA-F]{40}", sha) else ""
    except AppUpdateError:
        return ""


def check_for_app_update(timeout: int = TIMEOUT, check_same_tag_commit: bool = True) -> AppUpdateInfo:
    """Check GitHub Releases for a newer Zardit version or refreshed same-tag build."""

    release = _get_json(RELEASES_LATEST_API, timeout=timeout)
    tag_name = str(release.get("tag_name") or "").strip()
    if not tag_name:
        raise AppUpdateError("GitHub release is missing a tag name.")

    latest_version = tag_name.lstrip("vV")
    release_url = str(release.get("html_url") or RELEASES_PAGE_URL)
    if not release_url.startswith("https://github.com/"):
        release_url = RELEASES_PAGE_URL
    release_notes = _summarize_release_notes(release.get("body") or "")

    assets = release.get("assets") or []
    sums_url = next(
        (
            str(a.get("browser_download_url") or "")
            for a in assets
            if str(a.get("name") or "") == "SHA256SUMS.txt"
        ),
        "",
    )
    checksums = parse_sha256sums(_get_text(sums_url, timeout=timeout)) if sums_url else {}
    install_mode = detect_install_mode()
    asset = _select_platform_asset(assets, checksums, install_mode)

    local_tuple = parse_app_version(config.APP_VERSION)
    remote_tuple = parse_app_version(latest_version)

    available = False
    same_tag_hotfix = False
    remote_commit = ""

    build_info = config.get_build_info()
    local_commit = build_info.get("commit") or ""
    local_built_at = int(build_info.get("built_at") or 0)
    state = _read_state()
    installed_sha = str(state.get("installed_asset_sha256") or "")

    if remote_tuple > local_tuple:
        available = True
    elif remote_tuple == local_tuple and asset is not None:
        if installed_sha and installed_sha == asset.sha256:
            available = False
        else:
            if check_same_tag_commit and local_commit:
                remote_commit = _fetch_tag_commit(tag_name, timeout=timeout)
            commit_changed = bool(
                remote_commit and local_commit and not remote_commit.lower().startswith(local_commit.lower())
                and not local_commit.lower().startswith(remote_commit.lower())
            )
            rebuilt_later = bool(
                local_built_at > 0 and asset.updated_at > local_built_at + SAME_TAG_GRACE_SEC
            )
            if commit_changed or rebuilt_later:
                available = True
                same_tag_hotfix = True

    info = AppUpdateInfo(
        current_version=config.APP_VERSION,
        latest_version=latest_version,
        tag_name=tag_name,
        available=available,
        same_tag_hotfix=same_tag_hotfix,
        release_url=release_url,
        release_notes=release_notes,
        asset=asset,
        install_mode=install_mode,
        remote_commit=remote_commit,
    )

    _write_state(
        checked=time.time(),
        latest_version=latest_version,
        tag_name=tag_name,
        available=available,
        same_tag_hotfix=same_tag_hotfix,
        release_url=release_url,
        release_notes=release_notes,
        asset_name=asset.name if asset else "",
        asset_url=asset.url if asset else "",
        asset_sha256=asset.sha256 if asset else "",
        asset_size=asset.size if asset else 0,
        asset_updated_at=asset.updated_at if asset else 0,
        remote_commit=remote_commit,
    )
    return info


def dismiss_cached_update(asset_sha256: str = "", latest_version: str = "") -> None:
    _write_state(
        dismissed_asset_sha256=asset_sha256,
        dismissed_version=latest_version,
    )


def _cached_info_if_valid() -> AppUpdateInfo | None:
    state = _read_state()
    if not state.get("available"):
        return None
    latest = str(state.get("latest_version") or "")
    if not latest:
        return None
    local_t = parse_app_version(config.APP_VERSION)
    remote_t = parse_app_version(latest)
    hotfix = bool(state.get("same_tag_hotfix"))
    if remote_t < local_t:
        return None
    if remote_t == local_t and not hotfix:
        return None

    asset_sha = str(state.get("asset_sha256") or "")
    if asset_sha and asset_sha == str(state.get("installed_asset_sha256") or ""):
        return None
    if asset_sha and asset_sha == str(state.get("dismissed_asset_sha256") or ""):
        return None
    if not asset_sha and latest == str(state.get("dismissed_version") or ""):
        return None

    asset = None
    if state.get("asset_name") and state.get("asset_url") and asset_sha:
        asset = ReleaseAsset(
            name=str(state["asset_name"]),
            url=str(state["asset_url"]),
            sha256=asset_sha,
            size=int(state.get("asset_size") or 0),
            updated_at=int(state.get("asset_updated_at") or 0),
        )
    return AppUpdateInfo(
        current_version=config.APP_VERSION,
        latest_version=latest,
        tag_name=str(state.get("tag_name") or f"v{latest}"),
        available=True,
        same_tag_hotfix=hotfix,
        release_url=str(state.get("release_url") or RELEASES_PAGE_URL),
        release_notes=str(state.get("release_notes") or ""),
        asset=asset,
        install_mode=detect_install_mode(),
        remote_commit=str(state.get("remote_commit") or ""),
    )


def background_check_app_update(now: float | None = None) -> AppUpdateInfo | None:
    """Once-a-day silent check for app updates. Returns AppUpdateInfo if actionable, else None."""

    try:
        if not auto_check_enabled():
            return None

        state = _read_state()
        checked = state.get("checked")
        current_time = now if now is not None else time.time()
        due = not isinstance(checked, (int, float)) or (current_time - checked >= CHECK_INTERVAL)

        if due:
            info = check_for_app_update(timeout=QUIET_TIMEOUT, check_same_tag_commit=True)
            if not info.available:
                return None
            if info.asset and info.asset.sha256 == str(state.get("dismissed_asset_sha256") or ""):
                return None
            if not info.asset and info.latest_version == str(state.get("dismissed_version") or ""):
                return None
            return info

        return _cached_info_if_valid()
    except Exception:
        log.debug("App update background check failed", exc_info=True)
        _write_state(checked=time.time())
        return None


def download_and_verify_asset(asset: ReleaseAsset, dest_dir: Path, report=None) -> Path:
    """Download a GitHub release asset over HTTPS and verify its SHA-256 digest."""

    safe_name = Path(asset.name).name
    if not safe_name or safe_name != asset.name or "/" in asset.name or "\\" in asset.name:
        raise AppUpdateError("Invalid release asset filename.")
    if asset.size and asset.size > MAX_ASSET_BYTES:
        raise AppUpdateError("Release file exceeds safe size limit.")

    dest_dir.mkdir(parents=True, exist_ok=True)
    target = dest_dir / safe_name
    part = dest_dir / f"{safe_name}.part"
    hasher = hashlib.sha256()
    received = 0

    try:
        with _open_trusted(asset.url, "*/*", TIMEOUT) as resp, open(part, "wb") as out:
            header_len = resp.headers.get("Content-Length")
            total = int(header_len) if header_len and header_len.isdigit() else asset.size
            if total and total > MAX_ASSET_BYTES:
                raise AppUpdateError("Release download exceeds safe size limit.")

            while True:
                chunk = resp.read(CHUNK)
                if not chunk:
                    break
                received += len(chunk)
                if received > MAX_ASSET_BYTES:
                    raise AppUpdateError("Release download exceeded safe size limit.")
                hasher.update(chunk)
                out.write(chunk)
                if report and total:
                    pct = min(100, int(received * 100 / total))
                    report(f"Downloading {safe_name}… {pct}%")
    except urllib.error.HTTPError as err:
        part.unlink(missing_ok=True)
        raise AppUpdateError(f"Download failed (HTTP {err.code}).") from err
    except (OSError, http.client.HTTPException) as err:
        part.unlink(missing_ok=True)
        raise AppUpdateError("Download interrupted. Check your connection.") from err

    actual = hasher.hexdigest().lower()
    if actual != asset.sha256.lower():
        part.unlink(missing_ok=True)
        raise AppUpdateError("Checksum mismatch! Downloaded file failed SHA-256 verification.")

    os.replace(part, target)
    return target


def _safe_extract_zip(zip_path: Path, target_dir: Path) -> None:
    target_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    with zipfile.ZipFile(zip_path) as zf:
        for member in zf.infolist():
            rel = PurePosixPath(member.filename.replace("\\", "/"))
            if rel.is_absolute() or ".." in rel.parts or not rel.parts:
                raise AppUpdateError("Refused unsafe path inside portable update archive.")
            total += int(member.file_size or 0)
            if total > MAX_UNPACKED_BYTES:
                raise AppUpdateError("Portable update archive exceeds safe unpacked size.")
        zf.extractall(target_dir)


def _current_appimage_path() -> Path | None:
    if not sys.platform.startswith("linux"):
        return None
    raw = os.environ.get("APPIMAGE") or ""
    if not raw:
        return None
    try:
        candidate = Path(raw).resolve()
        return candidate if candidate.is_file() else None
    except OSError:
        return None


def can_self_update_appimage() -> bool:
    appimage = _current_appimage_path()
    if appimage is None:
        return False
    return os.access(appimage.parent, os.W_OK) and os.access(appimage, os.W_OK)


def _launch_linux_appimage_swap(
    staged_file: Path,
    target_appimage: Path,
    pid: int,
) -> None:
    script_path = staged_file.parent / "apply_appimage_update.sh"
    script = (
        "#!/bin/sh\n"
        f"PID={int(pid)}\n"
        f"STAGED={shlex.quote(str(staged_file))}\n"
        f"TARGET={shlex.quote(str(target_appimage))}\n"
        f"STAGING_DIR={shlex.quote(str(staged_file.parent))}\n"
        "i=0\n"
        "while kill -0 \"$PID\" 2>/dev/null && [ \"$i\" -lt 100 ]; do\n"
        "  sleep 0.2\n"
        "  i=$((i + 1))\n"
        "done\n"
        "chmod 755 \"$STAGED\" || exit 1\n"
        "mv -f \"$STAGED\" \"$TARGET\" || exit 1\n"
        "rm -rf \"$STAGING_DIR\" 2>/dev/null || true\n"
        "unset APPDIR APPIMAGE ARGV0 OWD PYTHONHOME PYTHONPATH LD_LIBRARY_PATH QT_PLUGIN_PATH QML2_IMPORT_PATH\n"
        "nohup \"$TARGET\" >/dev/null 2>&1 </dev/null &\n"
    )
    script_path.write_text(script, encoding="utf-8")
    os.chmod(script_path, 0o700)
    subprocess.Popen(
        ["/bin/sh", str(script_path)],
        start_new_session=True,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        close_fds=True,
    )


def _launch_windows_portable_swap(staged_root: Path, install_dir: Path, pid: int) -> None:
    batch_path = staged_root.parent / "apply_portable_update.cmd"
    # Determine whether the zip extracted a nested Zardit/ folder or direct files
    source_dir = staged_root / "Zardit" if (staged_root / "Zardit" / "Zardit.exe").is_file() else staged_root
    if not (source_dir / "Zardit.exe").is_file():
        raise AppUpdateError("Portable update archive is missing Zardit.exe.")

    script = (
        "@echo off\r\n"
        "chcp 65001 >NUL\r\n"
        "setlocal\r\n"
        "set /a wait_tries=0\r\n"
        ":waitloop\r\n"
        f"tasklist /FI \"PID eq {pid}\" 2>NUL | find /I \"{pid}\" >NUL\r\n"
        "if not errorlevel 1 (\r\n"
        "  set /a wait_tries+=1\r\n"
        "  if %wait_tries% GEQ 25 goto copyloop_init\r\n"
        "  ping -n 2 127.0.0.1 >NUL\r\n"
        "  goto waitloop\r\n"
        ")\r\n"
        ":copyloop_init\r\n"
        "set /a copy_tries=0\r\n"
        ":copyloop\r\n"
        f"xcopy /E /Y /I /Q \"{source_dir}\\*\" \"{install_dir}\\\" >NUL 2>&1\r\n"
        "if errorlevel 1 (\r\n"
        "  set /a copy_tries+=1\r\n"
        "  if %copy_tries% LSS 8 (\r\n"
        "    ping -n 2 127.0.0.1 >NUL\r\n"
        "    goto copyloop\r\n"
        "  )\r\n"
        ")\r\n"
        f"start \"\" \"{install_dir}\\Zardit.exe\"\r\n"
        f"rmdir /S /Q \"{staged_root.parent}\" 2>NUL\r\n"
    )
    batch_path.write_text(script, encoding="utf-8")
    # CREATE_NEW_PROCESS_GROUP (0x00000200) | CREATE_NO_WINDOW (0x08000000)
    # Note: DETACHED_PROCESS + CREATE_NO_WINDOW is invalid in Win32 CreateProcess.
    creationflags = 0x00000200 | 0x08000000
    subprocess.Popen(
        ["cmd.exe", "/c", str(batch_path)],
        creationflags=creationflags,
        close_fds=True,
    )


def install_app_update(info: AppUpdateInfo, report=None) -> tuple[str, Path | None]:
    """Download, SHA-256 verify, and apply or save the Zardit app update."""

    if not info.asset:
        raise AppUpdateError("No verified release package found for your platform.")

    mode = info.install_mode
    if mode in ("windows_installer", "windows_source"):
        dl_dir = _state_dir() / "downloads"
        if report:
            report(f"Downloading {info.asset.name}…")
        exe_path = download_and_verify_asset(info.asset, dl_dir, report=report)
        _write_state(installed_asset_sha256=info.asset.sha256, available=False)
        if mode == "windows_installer" and sys.platform == "win32":
            if report:
                report("Launching verified installer…")
            subprocess.Popen(
                [str(exe_path), "/SP-", "/CLOSEAPPLICATIONS", "/RESTARTAPPLICATIONS"],
                close_fds=True,
            )
            return ("installer_launched", exe_path)
        return ("downloaded", exe_path)

    if mode == "windows_portable" and sys.platform == "win32":
        staging = Path(tempfile.mkdtemp(prefix="zardit-portable-"))
        try:
            if report:
                report(f"Downloading {info.asset.name}…")
            zip_path = download_and_verify_asset(info.asset, staging, report=report)
            if report:
                report("Extracting verified update…")
            extracted = staging / "unpacked"
            _safe_extract_zip(zip_path, extracted)
            install_dir = Path(sys.executable).resolve().parent
            _write_state(installed_asset_sha256=info.asset.sha256, available=False)
            _launch_windows_portable_swap(extracted, install_dir, os.getpid())
            return ("portable_updating", install_dir)
        except Exception:
            shutil.rmtree(staging, ignore_errors=True)
            raise

    if mode == "linux_appimage" and can_self_update_appimage():
        target_appimage = _current_appimage_path()
        if target_appimage is not None:
            staging = Path(
                tempfile.mkdtemp(prefix=".zardit-update-", dir=target_appimage.parent)
            )
            try:
                if report:
                    report(f"Downloading {info.asset.name}…")
                staged_path = download_and_verify_asset(info.asset, staging, report=report)
                os.chmod(staged_path, 0o755)
                _write_state(installed_asset_sha256=info.asset.sha256, available=False)
                if report:
                    report("Applying verified AppImage update…")
                _launch_linux_appimage_swap(staged_path, target_appimage, os.getpid())
                return ("appimage_updating", target_appimage)
            except Exception:
                shutil.rmtree(staging, ignore_errors=True)
                raise

    # Source / non-writable AppImage fallback: save verified package cleanly in Downloads
    dl_dir = config.get_download_dir(create=True)
    if report:
        report(f"Downloading {info.asset.name}…")
    out_path = download_and_verify_asset(info.asset, dl_dir, report=report)
    if os.name == "posix":
        try:
            os.chmod(out_path, 0o755)
        except OSError:
            pass
    _write_state(installed_asset_sha256=info.asset.sha256, available=False)
    return ("downloaded", out_path)
