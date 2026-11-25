"""Application constants and per-user settings.

Settings live in the user's config folder, not next to the program, so an
installed (read-only) copy of the app can still save them.
"""

import json
import logging
import os
import sys
from pathlib import Path

log = logging.getLogger("zardit")

APP_NAME = "Zardit"
APP_SLUG = "zardit"
APP_TAGLINE = "Blaze through videos, audio & playlists across every platform."
APP_VERSION = "1.2"   # The only place the version is set.

AUDIO_BITRATES = [128, 192, 256, 320]
ORIGINAL_AUDIO = "original"     # Source audio saved as it is, no conversion
AUDIO_CHOICES = AUDIO_BITRATES + [ORIGINAL_AUDIO]
PLAYLIST_QUALITIES = [2160, 1440, 1080, 720, 480, 360]

ORIGINAL_IMAGE = "original"
IMAGE_FORMATS = [ORIGINAL_IMAGE, "png", "jpg", "webp"]


def audio_label(setting):
    """How an audio choice reads on screen."""

    if setting == ORIGINAL_AUDIO:
        return "Original audio (no conversion)"

    return f"{setting} kbps MP3"


def image_label(setting):
    """How an image format choice reads on screen."""

    if setting == ORIGINAL_IMAGE:
        return "Original image (no conversion)"

    return f"{str(setting).upper()} image"

# --------------------------------------------------------------------------
# Locations
# --------------------------------------------------------------------------

def _xdg_config_home():
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")


def _config_dir():
    """Per-user config folder for each operating system."""

    if sys.platform == "win32":
        base = Path(
            os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming"
        )
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = _xdg_config_home()

    return base / APP_SLUG


CONFIG_DIR = _config_dir()
SETTINGS_FILE = CONFIG_DIR / "settings.json"

def default_download_dir():
    """The 'Zardit' folder inside the user's Downloads folder."""

    downloads = Path.home() / "Downloads"

    if sys.platform.startswith("linux"):
        # Respect the desktop's Downloads folder, which may be translated.
        try:
            text = (_xdg_config_home() / "user-dirs.dirs").read_text(
                encoding="utf-8"
            )

            for line in text.splitlines():
                if line.startswith("XDG_DOWNLOAD_DIR="):
                    value = line.split("=", 1)[1].strip().strip('"')
                    downloads = Path(value.replace("$HOME", str(Path.home())))
                    break

        except OSError:
            pass

    return downloads / APP_NAME


# --------------------------------------------------------------------------
# Reading and writing settings
# --------------------------------------------------------------------------

def _read_json(path):
    """Return a dict from a JSON file, or None if missing or invalid."""

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None

    return data if isinstance(data, dict) else None


def _write_settings(settings):
    """Save settings atomically (write a temporary file, then replace)."""

    CONFIG_DIR.mkdir(parents=True, exist_ok=True)

    temporary_file = SETTINGS_FILE.with_suffix(".tmp")

    temporary_file.write_text(
        json.dumps(settings, indent=4),
        encoding="utf-8",
    )
    temporary_file.replace(SETTINGS_FILE)


def load_settings():
    """Return saved settings, or an empty dict if missing or broken."""

    return _read_json(SETTINGS_FILE) or {}

def save_setting(key, value):
    """Save one setting without losing the others."""

    settings = load_settings()
    settings[key] = value

    _write_settings(settings)

def remember_setting(key, value):
    """Save a preference, ignoring failures (not worth an error)."""

    try:
        save_setting(key, value)
    except OSError:
        pass

# --------------------------------------------------------------------------
# Download folder
# --------------------------------------------------------------------------

def get_download_dir(create=True):
    """Return the saved download folder, or the default if unusable.

    With create=False nothing is created on disk, which suits status
    displays such as the diagnostics report.
    """

    saved_path = load_settings().get("download_dir")
    fallback = default_download_dir()

    try:
        if isinstance(saved_path, str) and saved_path.strip():
            download_dir = Path(saved_path).expanduser().resolve()

            if download_dir.exists() and not download_dir.is_dir():
                raise ValueError("Saved download path is not a directory.")

            if create:
                download_dir.mkdir(parents=True, exist_ok=True)

            return download_dir

    except (OSError, ValueError) as error:
        log.warning(
            "The saved download folder %s can't be used (%s); using %s",
            saved_path, error, fallback,
        )

    if create:
        fallback.mkdir(parents=True, exist_ok=True)

    return fallback.resolve()

def save_download_dir(path):
    """Validate and save the preferred download directory."""

    if not str(path).strip():
        raise ValueError("Directory cannot be empty.")

    download_dir = Path(path).expanduser().resolve()

    if download_dir.exists() and not download_dir.is_dir():
        raise ValueError("The selected path is not a directory.")

    download_dir.mkdir(parents=True, exist_ok=True)

    save_setting("download_dir", str(download_dir))

    return download_dir