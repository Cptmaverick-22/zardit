"""Log file and diagnostics report."""

import logging
import platform
import re
import shutil
import subprocess
import sys
import threading
from logging.handlers import RotatingFileHandler
from pathlib import Path

from yt_dlp.version import __version__ as YTDLP_VERSION

import updater
from config import APP_NAME, APP_VERSION, CONFIG_DIR, get_build_info, get_download_dir

LOG_FILE = CONFIG_DIR / "zardit.log"
LOG_NAME = "zardit"

# Share links carry tracking parameters (si=, utm_..., and so on) that
# identify who shared them. They are removed before anything is written.
_TRACKING = r"(?:si|pp|feature|utm_[A-Za-z0-9_]+|igsh|ig_rid|xmt|slof)"
_FIRST_PARAM = re.compile(rf"\?{_TRACKING}=[^&\s\"'<>]*&")
_OTHER_PARAM = re.compile(rf"[?&]{_TRACKING}=[^&\s\"'<>]*")
_COOKIE_KV_RE = re.compile(
    r"\b(sessionid|csrftoken|ds_user_id)=([^\s;\"'&<>]+)",
    re.IGNORECASE,
)
_AUTH_HEADER_RE = re.compile(
    r"\b(Cookie|Set-Cookie|Authorization)\s*:\s*([^\r\n]+)",
    re.IGNORECASE,
)


def strip_tracking(text):
    """Remove share-tracking parameters from the links in a piece of text."""

    previous = None

    while previous != text:
        previous = text
        text = _FIRST_PARAM.sub("?", text)

    return _OTHER_PARAM.sub("", text)


def redact_sensitive(text):
    """Strip tracking parameters as well as session cookies and auth headers."""

    cleaned = strip_tracking(text or "")
    cleaned = _COOKIE_KV_RE.sub(r"\1=<redacted>", cleaned)
    cleaned = _AUTH_HEADER_RE.sub(r"\1: <redacted>", cleaned)
    return cleaned


class _PrivacyFilter(logging.Filter):
    def filter(self, record):
        try:
            message = record.getMessage()
        except Exception:
            return True

        cleaned = redact_sensitive(message)

        if cleaned != message:
            record.msg, record.args = cleaned, ()

        return True


def setup_logging():
    """Start writing the log file. Safe to call more than once.

    Returns the log file's path, or None if it can't be written.
    """

    logger = logging.getLogger(LOG_NAME)

    if any(isinstance(h, RotatingFileHandler) for h in logger.handlers):
        return LOG_FILE

    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(
            LOG_FILE, maxBytes=512 * 1024, backupCount=2, encoding="utf-8"
        )
    except OSError:
        return None

    handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)-7s %(message)s", "%Y-%m-%d %H:%M:%S"
    ))
    handler.addFilter(_PrivacyFilter())
    logger.addHandler(handler)
    logger.setLevel(logging.DEBUG)

    # A logging problem must never disturb the interface.
    logging.raiseExceptions = False

    _log_uncaught_exceptions()
    logger.info("--- %s %s started ---", APP_NAME, APP_VERSION)
    logger.info("yt-dlp %s", updater.describe())

    return LOG_FILE


def _log_uncaught_exceptions():
    """Record crashes in the log, then let Python report them as usual."""

    logger = logging.getLogger(LOG_NAME)

    def on_exception(exc_type, exc, traceback):
        if not issubclass(exc_type, KeyboardInterrupt):
            logger.critical(
                "Uncaught exception", exc_info=(exc_type, exc, traceback)
            )

        sys.__excepthook__(exc_type, exc, traceback)

    def on_thread_exception(args):
        if args.exc_type is not SystemExit:
            logger.critical(
                "Uncaught exception in a thread",
                exc_info=(args.exc_type, args.exc_value, args.exc_traceback),
            )

    sys.excepthook = on_exception
    threading.excepthook = on_thread_exception


def _tool_version(name, flag):
    """The first line of a tool's version output, or why it isn't available."""

    path = shutil.which(name)

    if not path:
        return "not found"

    options = {}

    if sys.platform == "win32":
        options["creationflags"] = 0x08000000   # No console window flashing.

    try:
        result = subprocess.run(
            [path, flag],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=5,
            **options,
        )
    except (OSError, subprocess.SubprocessError):
        return "found, but could not be run"

    lines = (result.stdout or result.stderr).strip().splitlines()

    return lines[0] if lines else "unknown version"


def _log_tail(count):
    try:
        text = LOG_FILE.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return "(no log yet)"

    return "\n".join(text.splitlines()[-count:]) or "(log is empty)"


def diagnostics_text(max_log_lines=80):
    """A report to paste when asking for help. Home folder paths are hidden."""

    try:
        folder = str(get_download_dir(create=False))
    except OSError:
        folder = "unknown"

    build = get_build_info()
    commit = build.get("commit") or ""
    commit_suffix = f" ({commit[:7]})" if commit else ""

    lines = [
        f"{APP_NAME} {APP_VERSION}{commit_suffix}",
        f"Python {platform.python_version()} on {platform.system()} "
        f"{platform.release()} ({platform.machine()})",
        f"yt-dlp {YTDLP_VERSION}",
        f"yt-dlp source: {updater.describe()}",
        f"FFmpeg: {_tool_version('ffmpeg', '-version')}",
        f"Deno: {_tool_version('deno', '--version')}",
        f"Download folder: {folder}",
        f"Log file: {LOG_FILE}",
        "",
        "----- Recent log -----",
        _log_tail(max_log_lines),
    ]

    return redact_sensitive("\n".join(lines)).replace(str(Path.home()), "~")