"""Filename sanitization, formatting, and display title helpers."""

from __future__ import annotations

import re
import sys
import unicodedata

WINDOWS_RESERVED = (
    {"CON", "PRN", "AUX", "NUL"}
    | {f"COM{i}" for i in range(1, 10)}
    | {f"LPT{i}" for i in range(1, 10)}
)

REPLACEMENTS = str.maketrans({
    ":": " -",
    "/": "-",
    "\\": "-",
    "|": "-",
    '"': "'",
    "?": None,
    "*": None,
    "<": None,
    ">": None,
})

MAX_TITLE_BYTES = 150
RESERVED_SUFFIX_BYTES = 45    # " [2160p] [id]" plus temp extensions
WINDOWS_PATH_LIMIT = 259

TEMP_FILE_PATTERN = re.compile(
    r"(\.part(-Frag\d+)?"
    r"|\.ytdl"
    r"|\.temp\.\w+"
    r"|\.f[\w-]+\.\w+(\.part)?"
    r"|\.(webp|jpg|jpeg|png))$"
)


def format_duration(seconds):
    """Convert seconds into HH:MM:SS."""

    if seconds is None:
        return "Unknown"

    hours, remainder = divmod(int(seconds), 3600)
    minutes, secs = divmod(remainder, 60)

    return f"{hours:02}:{minutes:02}:{secs:02}"


def format_file_size(size):
    """Convert bytes into a readable file size."""

    if size is None:
        return "Unknown"

    val = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if val < 1024:
            return f"{val:.1f} {unit}"
        val /= 1024

    return f"{val:.1f} TB"


def sanitize_name(name, max_bytes=MAX_TITLE_BYTES, fallback="untitled"):
    """Make a string safe as a file or folder name on every platform."""

    name = unicodedata.normalize("NFC", str(name or ""))
    chars = (" " if c.isspace() else c for c in name)
    name = "".join(c for c in chars if unicodedata.category(c) not in ("Cc", "Cf"))

    name = name.translate(REPLACEMENTS)
    name = re.sub(r" +", " ", name).strip(" .")

    name = name.encode("utf-8")[:max_bytes].decode("utf-8", errors="ignore")
    name = name.strip(" .")

    if name.split(".")[0].strip().upper() in WINDOWS_RESERVED:
        name = "_" + name

    return name or fallback


def max_title_bytes(directory):
    """Shorten titles on Windows when the folder path is already long."""

    limit = MAX_TITLE_BYTES
    if sys.platform == "win32":
        room = WINDOWS_PATH_LIMIT - len(str(directory)) - 1 - RESERVED_SUFFIX_BYTES
        limit = min(limit, room)

    return max(limit, 30)


def build_outtmpl(directory, title, video_id, tag):
    """Build a safe name template, e.g. 'Title [720p] [id].%(ext)s'."""

    safe_title = sanitize_name(
        title, max_bytes=max_title_bytes(directory), fallback=video_id
    )
    safe_title = safe_title.replace("%", "%%")
    return f"{safe_title} {tag} [{video_id}].%(ext)s"


def safe_folder_name(name, fallback="Playlist"):
    """Make a string safe to use as a folder name."""

    return sanitize_name(name, max_bytes=80, fallback=fallback)


def clean_ig_title(raw_title, username=None, media_id="ig"):
    """Collapse multi-line Instagram captions and strip hashtag walls for clean display & filenames."""

    text = re.sub(r"\s+", " ", (raw_title or "")).strip()
    m = re.match(
        r"^(?:\d[\d,.]*\s+likes?(?:,\s*\d[\d,.]*\s+comments?)?\s*-\s*[^:]+:\s*)(.+)$",
        text,
        re.IGNORECASE,
    )
    if m:
        text = m.group(1).strip()
    m2 = re.match(r"^[^:\n]{1,60}\s+on\s+Instagram:\s*(.+)$", text, re.IGNORECASE)
    if m2:
        text = m2.group(1).strip()
    text = text.strip('"\'“” .')

    no_tags = re.sub(r"(?:\s+#[A-Za-z0-9_]+){2,}\.?$", "", text).strip(" .")
    if no_tags:
        text = no_tags
    elif text.startswith("#"):
        text = ""

    if len(text) > 90:
        text = text[:89].rstrip(" ,;:-") + "…"

    if text:
        return text
    if username and username != "Instagram":
        return f"Video by {username}"
    return f"Instagram Post ({media_id})"
