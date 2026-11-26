"""Download logic for Zardit.

This module never prints and never asks questions. Interfaces (terminal, GUI)
pass in a Reporter to receive progress and messages, and a threading.Event
to cancel a download.
"""

from __future__ import annotations

import copy
import errno
import hashlib
import html
import json
import logging
import re
import shutil
import subprocess
import sys
import threading
import time
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import urllib.request
import xml.etree.ElementTree as ET

import yt_dlp
from yt_dlp.utils import DownloadCancelled as _YtdlpCancelled
from yt_dlp.utils import DownloadError as _YtdlpDownloadError
from yt_dlp.utils import UnavailableVideoError

from config import (
    APP_VERSION,
    AUDIO_BITRATES,
    AUDIO_CHOICES,
    IMAGE_FORMATS,
    ORIGINAL_AUDIO,
    ORIGINAL_IMAGE,
    PLAYLIST_QUALITIES,
    get_download_dir,
)

# Messages go to the log file once an interface calls logs.setup_logging().
log = logging.getLogger("zardit")
log.addHandler(logging.NullHandler())


# --------------------------------------------------------------------------
# Errors and reporting
# --------------------------------------------------------------------------

class DownloadError(Exception):
    """A problem described by a message that is safe to show to the user."""


class PlaylistLinkError(DownloadError):
    """A playlist link was given where a single video was expected."""


class DownloadCancelled(_YtdlpCancelled):
    """Raised inside a progress hook to stop yt-dlp."""


class Reporter:
    """Receives events from the download functions.

    Interfaces subclass this and override what they need. Every method
    does nothing by default.
    """

    def message(self, text, level="info"):
        """A line of information. level is "info", "warning" or "error"."""

    def stage(self, text):
        """A processing step, such as merging or converting."""

    def progress(self, downloaded, total, speed, eta):
        """Overall progress in bytes. total, speed and eta may be None."""

    def progress_done(self):
        """The download phase has ended (finished, failed or cancelled)."""

    def playlist_item(self, number, total, title):
        """A playlist item is about to start."""


# --------------------------------------------------------------------------
# Results
# --------------------------------------------------------------------------

@dataclass
class VideoSummary:
    title: str
    channel: str
    duration: int | None
    upload_date: str
    thumbnail: str | None


@dataclass
class PlaylistInfo:
    title: str
    entries: list


@dataclass
class DownloadResult:
    status: str                    # "completed", "exists" or "cancelled"
    folder: Path | None = None
    filename: str | None = None    # set when status is "exists" or "completed"
    file_path: Path | None = None


@dataclass
class PlaylistResult:
    total: int
    folder: Path | None = None
    completed: int = 0
    skipped: int = 0
    failed: list = field(default_factory=list)   # (title, message) pairs
    cancelled: bool = False
    completed_items: list = field(default_factory=list)


@dataclass
class FormatOption:
    value: int | str
    label: str
    badge: str = ""
    details: str = ""
    size: int | None = None


# --------------------------------------------------------------------------
# yt-dlp access
# --------------------------------------------------------------------------

class _YtdlpLogger:
    """Sends yt-dlp's own messages to the log instead of the terminal."""

    def debug(self, message):
        log.debug(message)

    def info(self, message):
        log.info(message)

    def warning(self, message):
        log.warning(message)

    def error(self, message):
        log.error(message)


def _ydl(**options):
    """A quiet YoutubeDL instance whose messages go to the log."""

    settings = {"quiet": True, "logger": _YtdlpLogger()}
    settings.update(options)

    return yt_dlp.YoutubeDL(settings)


def _is_cancelled(cancel):
    return cancel is not None and cancel.is_set()


# --------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------

ANSI_PATTERN = re.compile(r"\x1b\[[0-9;]*m")
VIDEO_ID_PATTERN = re.compile(r"^[0-9A-Za-z_-]{11}$")

SUPPORTED_PLATFORMS = (
    ("YouTube", ("youtube.com", "youtu.be")),
    ("Instagram", ("instagram.com", "instagr.am")),
    ("Facebook", ("facebook.com", "fb.watch", "fb.com")),
    ("Reddit", ("reddit.com", "redd.it", "v.redd.it")),
    ("X / Twitter", ("twitter.com", "x.com", "t.co")),
    ("Threads", ("threads.net", "threads.com")),
)


def _host_matches(hostname, domain):
    return hostname == domain or hostname.endswith(f".{domain}")


def detect_platform(url):
    """Return a readable platform name for a media URL."""

    try:
        hostname = (urlparse(url).hostname or "").lower()
    except ValueError:
        return "Web"

    for name, domains in SUPPORTED_PLATFORMS:
        if any(_host_matches(hostname, d) for d in domains):
            return name

    return "Web"


def validate_url(url):
    """Validate an HTTP/HTTPS media URL from YouTube or another platform."""

    if not isinstance(url, str) or any(ch.isspace() for ch in url.strip()):
        return False

    try:
        parsed = urlparse(url.strip())
        hostname = (parsed.hostname or "").lower()

        return (
            parsed.scheme in ("http", "https")
            and bool(hostname)
            and "." in hostname
            and not hostname.startswith(".")
            and not hostname.endswith(".")
        )

    except (ValueError, AttributeError):
        return False


def validate_youtube_url(url):
    """Validate supported YouTube URLs."""

    try:
        parsed = urlparse(url)
        hostname = (parsed.hostname or "").lower()

        return (
            parsed.scheme in ("http", "https")
            and (
                hostname == "youtu.be"
                or hostname == "youtube.com"
                or hostname.endswith(".youtube.com")
            )
        )

    except (ValueError, AttributeError):
        return False


_URL_IN_TEXT = re.compile(r"https?://[^\s<>\"']+")


def extract_media_url(text):
    """Extract the first valid media URL from clipboard or pasted text."""

    if not isinstance(text, str):
        return None

    stripped = text.strip()
    if not stripped:
        return None

    if validate_url(stripped):
        return stripped

    for match in _URL_IN_TEXT.finditer(stripped):
        candidate = match.group(0).rstrip(".,);]>'\"")
        if validate_url(candidate):
            return candidate

    return None


extract_youtube_url = extract_media_url


def _list_id(url):
    """The playlist ID in a link's query string, or None."""

    try:
        values = parse_qs(urlparse(url).query).get("list")
    except ValueError:
        return None

    return values[0] if values else None


def is_mix_list(list_id):
    """True for YouTube's personalised Mix lists (list=RD...)."""

    return bool(list_id) and list_id.startswith("RD") \
        and not list_id.startswith("RDCLAK")


def has_playlist_and_video(url):
    """True if a link points at a video that is inside a real playlist."""

    if not validate_youtube_url(url):
        return False

    list_id = _list_id(url)

    if not list_id or is_mix_list(list_id):
        return False

    parsed = urlparse(url)
    hostname = (parsed.hostname or "").lower()

    if hostname == "youtu.be":
        return bool(parsed.path.strip("/"))

    return (
        "v" in parse_qs(parsed.query)
        or parsed.path.startswith(("/shorts/", "/live/"))
    )


def format_duration(seconds):
    """Convert seconds into HH:MM:SS."""

    if seconds is None:
        return "Unknown"

    hours, remainder = divmod(int(seconds), 3600)
    minutes, seconds = divmod(remainder, 60)

    return f"{hours:02}:{minutes:02}:{seconds:02}"


def format_file_size(size):
    """Convert bytes into a readable file size."""

    if size is None:
        return "Unknown"

    size = float(size)

    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024:
            return f"{size:.1f} {unit}"

        size /= 1024

    return f"{size:.1f} TB"


# --------------------------------------------------------------------------
# Readable error messages
# --------------------------------------------------------------------------

DISK_FULL = "The disk is full. Free some space or choose another download folder."
NO_PERMISSION = "No permission to write to the download folder. Choose another folder."
NAME_TOO_LONG = (
    "The file name or folder path is too long. "
    "Use a shorter folder name or location."
)

# Errors raised by the operating system, recognised by their error number.
_ERRNO_MESSAGES = {
    errno.ENOSPC: DISK_FULL,
    errno.EACCES: NO_PERMISSION,
    errno.EPERM: NO_PERMISSION,
    errno.EROFS: NO_PERMISSION,
    errno.ENAMETOOLONG: NAME_TOO_LONG,
}

# Messages from yt-dlp, recognised by their text. The first match wins, so
# more specific rules come before general ones.
_ERROR_RULES = [
    (re.compile(p, re.IGNORECASE), text) for p, text in (
        (r"confirm your age|age[- ]restricted|age verification",
         "This video is age-restricted and needs a signed-in account, "
         "which this app doesn't support yet."),
        (r"not a bot",
         "The site is asking to confirm you're not a bot. Wait a while, "
         "update yt-dlp, or try another network."),
        (r"members[- ]only|join this channel",
         "This video is only available to channel members."),
        (r"private video|private post",
         "This video or post is private."),
        (r"sign in|log in|login required|login_required|cookies|empty media response",
         "This post or video needs a signed-in account, "
         "which Zardit doesn't support yet."),
        (r"in your country|geo[- ]?restrict|not made this video available"
         r"|available in your region",
         "This video isn't available in your country."),
        (r"premieres in|live event will begin|is upcoming",
         "This is a premiere or live event that hasn't started yet."),
        (r"copyright",
         "This video was blocked for copyright reasons."),
        (r"requested format is not available|no video formats found"
         r"|only images are available",
         "That quality isn't available for this video. Try another one."),
        (r"unsupported url",
         "That link isn't a video or media post Zardit can download."),
        (r"http error 403",
         "Access was refused (HTTP 403). This is usually "
         "temporary. Try again in a minute, or use Update yt-dlp in the app."),
        (r"http error 429|too many requests|rate[- ]limit",
         "Too many requests (HTTP 429). "
         "Wait a few minutes and try again."),
        (r"http error 5\d\d",
         "The site had a server problem. Try again in a moment."),
        (r"unavailable|no longer available|has been removed|been terminated",
         "This video is unavailable. It may have been removed or made private."),
        (r"javascript runtime|js runtime|n challenge|\bnsig\b"
         r"|signature solving|challenge solving",
         "yt-dlp couldn't solve the site's JavaScript check. Make sure Deno "
         "is installed (run: deno --version), then use Update yt-dlp in the "
         "app."),
        (r"no space left|not enough space|disk full|\[errno 28\]",
         DISK_FULL),
        (r"permission denied|\[errno 13\]|read-only file system"
         r"|access is denied",
         NO_PERMISSION),
        (r"file name too long|filename too long|\[errno 36\]|path too long",
         NAME_TOO_LONG),
        (r"ffprobe|ffmpeg|postprocessing",
         "Media conversion failed. Check that FFmpeg is installed "
         "and up to date."),
        (r"timed out|timeout|connection|name or service not known"
         r"|getaddrinfo|temporary failure in name resolution"
         r"|network is unreachable|unable to download (webpage|api)",
         "Network error. Check your internet connection and try again."),
    )
]


def friendly_error(error):
    """Turn an exception into a short, readable message."""

    if isinstance(error, OSError) and error.errno in _ERRNO_MESSAGES:
        return _ERRNO_MESSAGES[error.errno]

    message = ANSI_PATTERN.sub("", str(error)).strip()
    message = re.sub(r"^ERROR:\s*", "", message)

    for pattern, text in _ERROR_RULES:
        if pattern.search(message):
            return text

    return f"Download failed: {message[:300]}"


# --------------------------------------------------------------------------
# Safe file and folder names (Windows, Linux and macOS)
# --------------------------------------------------------------------------

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


def sanitize_name(name, max_bytes=MAX_TITLE_BYTES, fallback="untitled"):
    """Make a string safe as a file or folder name on every platform."""

    name = unicodedata.normalize("NFC", str(name or ""))

    chars = (" " if c.isspace() else c for c in name)
    name = "".join(
        c for c in chars if unicodedata.category(c) not in ("Cc", "Cf")
    )

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
        room = (
            WINDOWS_PATH_LIMIT
            - len(str(directory))
            - 1
            - RESERVED_SUFFIX_BYTES
        )
        limit = min(limit, room)

    return max(limit, 30)


def build_outtmpl(directory, title, video_id, tag):
    """Build a safe name template, e.g. 'Title [720p] [id].%(ext)s'."""

    safe_title = sanitize_name(
        title, max_bytes=max_title_bytes(directory), fallback=video_id
    )

    # A literal % would be read as a template field by yt-dlp.
    safe_title = safe_title.replace("%", "%%")

    return f"{safe_title} {tag} [{video_id}].%(ext)s"


def safe_folder_name(name, fallback="Playlist"):
    """Make a string safe to use as a folder name."""

    return sanitize_name(name, max_bytes=80, fallback=fallback)


# --------------------------------------------------------------------------
# Fetching information
# --------------------------------------------------------------------------

_THREADS_HEADERS = {
    "User-Agent": "curl/8.5.0",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}
_THREADS_MEDIA_HEADERS = {
    "User-Agent": "Mozilla/5.0",
    "Referer": "https://www.threads.com/",
}
_THREADS_DASH_RE = re.compile(
    r'"video_dash_manifest"\s*:\s*"((?:\\.|[^"\\])*)"'
)
_THREADS_VIDEO_VERSIONS_RE = re.compile(
    r'"video_versions"\s*:\s*(\[[^\]]+\])'
)
_THREADS_VIDEO_URL_RE = re.compile(
    r'"video_url"\s*:\s*"((?:\\.|[^"\\])*)"'
)
_THREADS_DIMS_RE = re.compile(
    r'"original_(width|height)"\s*:\s*(\d+)'
)


def _decode_json_str(raw):
    try:
        return json.loads(f'"{raw}"')
    except Exception:
        return raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")


def _parse_threads_dash_manifest(xml_text):
    """Extract DASH video and audio format dicts from Threads MPD XML."""

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []

    formats = []
    seen_urls = set()
    for elem in root.iter():
        if elem.tag.rsplit("}", 1)[-1] != "Representation":
            continue
        mime = (elem.attrib.get("mimeType") or "").lower()
        base_url = None
        for child in elem:
            if child.tag.rsplit("}", 1)[-1] == "BaseURL" and child.text:
                base_url = html.unescape(child.text.strip())
                break
        if not base_url or not base_url.startswith("https://") or base_url in seen_urls:
            continue
        seen_urls.add(base_url)

        fmt_id = elem.attrib.get("id") or hashlib.sha1(base_url.encode()).hexdigest()[:10]
        bw = elem.attrib.get("bandwidth")
        tbr = int(bw) / 1000.0 if (bw and bw.isdigit()) else None
        fb_len = elem.attrib.get("FBContentLength")
        filesize = int(fb_len) if (fb_len and fb_len.isdigit() and int(fb_len) > 0) else None
        codecs = elem.attrib.get("codecs") or ""

        if mime.startswith("video/"):
            w = elem.attrib.get("width")
            h = elem.attrib.get("height")
            width = int(w) if (w and w.isdigit()) else None
            height = int(h) if (h and h.isdigit()) else None
            fb_label = elem.attrib.get("FBQualityLabel")
            if not fb_label and width and height:
                fb_label = f"{min(width, height)}p"
            formats.append({
                "format_id": fmt_id,
                "url": base_url,
                "ext": "mp4",
                "protocol": "https",
                "vcodec": codecs or "avc1",
                "acodec": "none",
                "width": width,
                "height": height,
                "tbr": tbr,
                "format_note": fb_label,
                "filesize": filesize,
                "http_headers": dict(_THREADS_MEDIA_HEADERS),
            })
        elif mime.startswith("audio/"):
            formats.append({
                "format_id": fmt_id,
                "url": base_url,
                "ext": "m4a",
                "protocol": "https",
                "vcodec": "none",
                "acodec": codecs or "mp4a.40.5",
                "tbr": tbr,
                "filesize": filesize,
                "http_headers": dict(_THREADS_MEDIA_HEADERS),
            })
    return formats


def _extract_threads_media(url):
    """Extract video (and fallback image) info directly from Threads HTML."""

    if detect_platform(url) != "Threads":
        return None

    try:
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname:
            return None
        req = urllib.request.Request(url, headers=dict(_THREADS_HEADERS))
        with urllib.request.urlopen(req, timeout=10) as resp:
            final_url = resp.geturl()
            final_parsed = urlparse(final_url)
            if (
                final_parsed.scheme != "https"
                or detect_platform(final_url) != "Threads"
            ):
                return None
            ctype = (resp.headers.get("Content-Type") or "").lower()
            if "html" not in ctype:
                return None
            body = resp.read(3 * 1024 * 1024).decode("utf-8", errors="replace")
    except Exception:
        return None

    meta = {}
    for match in _OG_TAG_RE.finditer(body):
        key = (match.group(1) or match.group(4) or "").lower()
        val = html.unescape(match.group(2) or match.group(3) or "").strip()
        if key and val and key not in meta:
            meta[key] = val

    sc_match = re.search(r"/post/([A-Za-z0-9_-]+)", final_url)
    shortcode = sc_match.group(1) if sc_match else None

    search_windows = []
    if shortcode:
        for m in re.finditer(rf'"code"\s*:\s*"{re.escape(shortcode)}"', body):
            start = max(0, m.start() - 15000)
            end = min(len(body), m.end() + 15000)
            search_windows.append(body[start:end])
    search_windows.append(body)

    formats = []
    seen_urls = set()
    orig_w, orig_h = None, None

    for window in search_windows:
        if not orig_w or not orig_h:
            for dim_name, dim_val in _THREADS_DIMS_RE.findall(window):
                if dim_name == "width" and orig_w is None:
                    orig_w = int(dim_val)
                elif dim_name == "height" and orig_h is None:
                    orig_h = int(dim_val)

    for window in search_windows:
        for dm in _THREADS_DASH_RE.finditer(window):
            xml_text = _decode_json_str(dm.group(1))
            dash_fmts = _parse_threads_dash_manifest(xml_text)
            if dash_fmts:
                for f in dash_fmts:
                    if f["url"] not in seen_urls:
                        seen_urls.add(f["url"])
                        formats.append(f)
                break
        if formats:
            break

    if not formats:
        for window in search_windows:
            for vm in _THREADS_VIDEO_VERSIONS_RE.finditer(window):
                try:
                    versions = json.loads(vm.group(1))
                except Exception:
                    continue
                if isinstance(versions, list):
                    for idx, item in enumerate(versions):
                        if not isinstance(item, dict):
                            continue
                        v_url = item.get("url")
                        if (
                            isinstance(v_url, str)
                            and v_url.startswith("https://")
                            and "rsrc.php" not in v_url.lower()
                            and v_url not in seen_urls
                        ):
                            seen_urls.add(v_url)
                            vw = item.get("width") or orig_w
                            vh = item.get("height") or orig_h
                            note = f"{min(vw, vh)}p" if (vw and vh) else None
                            formats.append({
                                "format_id": f"threads-prog-{idx}",
                                "url": v_url,
                                "ext": "mp4",
                                "protocol": "https",
                                "vcodec": "h264",
                                "acodec": "aac",
                                "width": vw,
                                "height": vh,
                                "format_note": note,
                                "http_headers": dict(_THREADS_MEDIA_HEADERS),
                            })
                    if formats:
                        break
            if formats:
                break

    if not formats:
        og_video = (
            meta.get("og:video:secure_url")
            or meta.get("og:video")
            or meta.get("twitter:player:stream")
        )
        direct_candidates = [og_video] if og_video else []
        for vm in _THREADS_VIDEO_URL_RE.finditer(search_windows[0]):
            direct_candidates.append(_decode_json_str(vm.group(1)))
        for cand in direct_candidates:
            if (
                isinstance(cand, str)
                and cand.startswith("https://")
                and "rsrc.php" not in cand.lower()
                and cand not in seen_urls
            ):
                seen_urls.add(cand)
                vw = int(meta["og:video:width"]) if (meta.get("og:video:width") or "").isdigit() else orig_w
                vh = int(meta["og:video:height"]) if (meta.get("og:video:height") or "").isdigit() else orig_h
                formats.append({
                    "format_id": "threads-direct",
                    "url": cand,
                    "ext": "mp4",
                    "protocol": "https",
                    "vcodec": "h264",
                    "acodec": "aac",
                    "width": vw,
                    "height": vh or 720,
                    "format_note": f"{min(vw, vh)}p" if (vw and vh) else "720p",
                    "http_headers": dict(_THREADS_MEDIA_HEADERS),
                })
                break

    # Ensure progressive fallback always exposes at least one quality tier when
    # Threads omits dimensions and DASH manifest on that post.
    has_video_stream = any(f.get("vcodec") not in (None, "none") for f in formats)
    if has_video_stream and not any(resolution_label(f) for f in formats if f.get("vcodec") not in (None, "none")):
        for f in formats:
            if f.get("vcodec") not in (None, "none"):
                f["height"] = 720
                f["format_note"] = "720p"
                break

    img_url = meta.get("og:image:secure_url") or meta.get("og:image") or meta.get("twitter:image")
    title = meta.get("og:description") or meta.get("og:title") or meta.get("twitter:title") or "Threads video"
    author = meta.get("og:title") or "Threads"
    media_id = shortcode or hashlib.sha1(url.encode("utf-8", errors="ignore")).hexdigest()[:11]

    if formats and has_video_stream:
        return {
            "id": media_id,
            "title": title,
            "uploader": author,
            "thumbnail": img_url,
            "webpage_url": final_url,
            "original_url": url,
            "extractor": "threads",
            "extractor_key": "Threads",
            "ext": "mp4",
            "formats": formats,
        }

    if img_url and img_url.startswith("https://"):
        width = int(meta["og:image:width"]) if (meta.get("og:image:width") or "").isdigit() else None
        height = int(meta["og:image:height"]) if (meta.get("og:image:height") or "").isdigit() else None
        return _make_image_info(
            img_url,
            title=title,
            channel="Threads",
            media_id=media_id,
            width=width,
            height=height,
        )

    return None


def _try_image_fallback(url):
    """Attempt to extract image post/file metadata when yt-dlp has no video."""

    direct = _probe_direct_image(url)
    if direct is not None:
        return direct

    threads_info = _extract_threads_media(url)
    if threads_info is not None:
        return threads_info

    reddit_info = _extract_reddit_images(url)
    if reddit_info is not None:
        return reddit_info

    fb_info = _extract_facebook_images(url)
    if fb_info is not None:
        return fb_info

    return _extract_og_image(url)


def fetch_video_info(url):
    """Return yt-dlp's information for a single video, image, or media post."""

    if not validate_url(url):
        raise DownloadError("Invalid media URL.")

    direct = _probe_direct_image(url)
    if direct is not None:
        return direct

    started = time.monotonic()

    try:
        with _ydl(
            noplaylist=True,
            skip_download=True,
            ignore_no_formats_error=True,
        ) as ydl:
            info = ydl.extract_info(url, download=False)

    except Exception as error:
        err_text = str(error).lower()
        if detect_platform(url) == "Threads" or any(
            token in err_text
            for token in (
                "there is no video",
                "no video formats",
                "only images",
                "unsupported url",
                "cannot parse data",
            )
        ):
            fallback = _try_image_fallback(url)
            if fallback is not None:
                if fallback.get("_type") == "playlist":
                    raise PlaylistLinkError(
                        "This post contains multiple items."
                    ) from error
                return fallback

        log.warning("Could not fetch %s: %s", url, error, exc_info=True)
        raise DownloadError(friendly_error(error)) from error

    log.info(
        "Fetched video information in %.1fs: %s",
        time.monotonic() - started, url,
    )

    if info.get("_type") == "playlist":
        raise PlaylistLinkError(
            "This is a playlist link. Use the playlist option instead."
        )

    if info.get("live_status") in ("is_live", "is_upcoming"):
        raise DownloadError(
            "Live streams and upcoming premieres can't be downloaded. "
            "Try again after the stream has ended."
        )

    if not available_qualities(info):
        if detect_platform(url) == "Threads":
            threads_info = _extract_threads_media(url)
            if threads_info is not None:
                return threads_info
        img_url, width, height = best_image_details(info)
        if img_url:
            info["_zardit_media_kind"] = "image"
            info["image_url"] = img_url
            if width and not info.get("width"):
                info["width"] = width
            if height and not info.get("height"):
                info["height"] = height
        else:
            fallback = _try_image_fallback(url)
            if fallback is not None:
                if fallback.get("_type") == "playlist":
                    raise PlaylistLinkError("This post contains multiple items.")
                return fallback

    return info

def summarize_video(info):
    """Pick out the details an interface shows about a video."""

    upload = info.get("upload_date") or ""

    if len(upload) == 8:
        upload = f"{upload[:4]}-{upload[4:6]}-{upload[6:]}"

    return VideoSummary(
        title=info.get("title") or "Unknown",
        channel=info.get("uploader") or info.get("channel") or "Unknown channel",
        duration=info.get("duration"),
        upload_date=upload or "Unknown",
        thumbnail=info.get("thumbnail"),
    )


def is_video_entry(entry):
    """True for a playlist or carousel entry that is a downloadable video."""

    ie_key = entry.get("ie_key")
    if entry.get("_type") == "playlist" or ie_key == "YoutubeTab":
        return False

    if ie_key in (None, "Youtube"):
        if VIDEO_ID_PATTERN.match(entry.get("id") or ""):
            return True
        return bool(entry.get("url") or entry.get("webpage_url")) and ie_key is None

    return bool(entry.get("id") or entry.get("url") or entry.get("webpage_url"))


def fetch_playlist(url):
    """Return a playlist's title and entries (fast, no per-video details)."""

    if not validate_url(url):
        raise DownloadError("Invalid media URL.")

    list_id = _list_id(url)

    if is_mix_list(list_id):
        raise DownloadError(
            "YouTube Mix lists are personalised and can't be downloaded as "
            "a playlist. Use a normal playlist link, or download the video "
            "on its own."
        )

    if list_id in ("WL", "LL"):
        raise DownloadError(
            "Watch Later and Liked videos need you to be signed in, so they "
            "can't be downloaded here."
        )

    reddit_gallery = _extract_reddit_images(url)
    if reddit_gallery is not None and reddit_gallery.get("_type") == "playlist":
        return PlaylistInfo(
            title=reddit_gallery.get("title") or "Gallery",
            entries=reddit_gallery.get("entries") or [],
        )

    try:
        with _ydl(
            extract_flat="in_playlist",
            skip_download=True,
            ignore_no_formats_error=True,
        ) as ydl:
            info = ydl.extract_info(url, download=False)

    except Exception as error:
        fb_gallery = _extract_facebook_images(url)
        if fb_gallery is not None:
            entries = (
                fb_gallery.get("entries")
                if fb_gallery.get("_type") == "playlist"
                else [fb_gallery]
            )
            return PlaylistInfo(
                title=fb_gallery.get("title") or "Facebook Photos",
                entries=entries,
            )
        log.warning("Could not fetch playlist %s: %s", url, error, exc_info=True)
        raise DownloadError(friendly_error(error)) from error

    found = [entry for entry in (info.get("entries") or []) if entry]
    entries = [entry for entry in found if is_video_entry(entry)]
    playlist_title = info.get("title") or "Playlist"

    _normalize_playlist_entries(entries, playlist_title, url)

    log.info(
        "Playlist %s: %d entries, %d items", url, len(found), len(entries)
    )

    if not entries:
        fb_gallery = _extract_facebook_images(url)
        if fb_gallery is not None:
            fb_entries = (
                fb_gallery.get("entries")
                if fb_gallery.get("_type") == "playlist"
                else [fb_gallery]
            )
            return PlaylistInfo(
                title=fb_gallery.get("title") or "Facebook Photos",
                entries=fb_entries,
            )

        if found:
            raise DownloadError(
                "This looks like a channel or a page, not a playlist. "
                "Open one of its playlists and paste that link."
            )

        raise DownloadError("No videos or photos found. Is this a playlist or post link?")

    return PlaylistInfo(title=playlist_title, entries=entries)


def parse_range(text, total):
    """Turn a selection like '1-3, 5, 8-10' into a set of 1-based numbers.

    An empty text selects everything. '-4' means 1-4 and '7-' means 7 to the
    end. Returns None if any part is invalid or outside 1..total.
    """

    text = text.strip()

    if not text:
        return set(range(1, total + 1))

    indices = set()

    for part in text.split(","):
        part = part.strip()

        if not part:
            continue

        try:
            if "-" in part:
                first, last = part.split("-", 1)
                start = int(first) if first.strip() else 1
                end = int(last) if last.strip() else total
            else:
                start = end = int(part)

        except ValueError:
            return None

        if start < 1 or start > total or start > end:
            return None

        indices.update(range(start, min(end, total) + 1))

    return indices or None


# --------------------------------------------------------------------------
# Qualities and size estimates
# --------------------------------------------------------------------------

# Leftovers from yt-dlp's default format choice that must not
# influence a new selection.
STALE_FORMAT_KEYS = (
    "requested_formats", "requested_downloads", "format", "format_id",
    "format_note", "ext", "url", "filesize", "filesize_approx", "tbr",
    "vbr", "abr", "asr", "fps", "width", "height", "resolution",
    "vcodec", "acodec", "dynamic_range", "protocol", "container",
    "language", "http_headers", "fragments",
)


NOTE_PATTERN = re.compile(r"^(\d{3,4})p")


def resolution_label(video_format):
    """The resolution YouTube calls a format, such as 1080 for '1080p60'.

    YouTube's own label is used when there is one. Otherwise the shorter
    side of the picture is used.
    """

    match = NOTE_PATTERN.match(video_format.get("format_note") or "")

    if match:
        return int(match.group(1))

    height = video_format.get("height")
    width = video_format.get("width")

    if height and width:
        return min(height, width)

    return height or None


_IMAGE_EXTS = {"mhtml", "jpg", "jpeg", "png", "webp"}
_VIDEO_CONTAINERS = {"mp4", "webm", "mov", "mkv", "m3u8"}


def available_qualities(info):
    """Find available video resolutions, highest first.

    Prefers labelled MP4 formats when present (avoiding HLS/odd duplicates
    on YouTube), and falls back to other video containers for platforms
    such as Instagram, Facebook, Reddit, X/Twitter, and Threads.
    """

    mp4_labelled, mp4_guessed, other_video = set(), set(), set()
    formats = info.get("formats") or (
        [info] if info.get("height") or info.get("width") else []
    )

    for video_format in formats:
        ext = (video_format.get("ext") or "").lower()
        vcodec = video_format.get("vcodec")
        note = video_format.get("format_note") or ""

        if (
            ext in _IMAGE_EXTS
            or vcodec in ("none", "images")
            or "storyboard" in note.lower()
        ):
            continue

        if vcodec is None and not (
            video_format.get("height") and ext in _VIDEO_CONTAINERS
        ):
            continue

        label = resolution_label(video_format)

        if not label:
            continue

        if ext == "mp4":
            has_note = NOTE_PATTERN.match(note)
            (mp4_labelled if has_note else mp4_guessed).add(label)
        else:
            other_video.add(label)

    return sorted(mp4_labelled or mp4_guessed or other_video, reverse=True)


def video_selection(quality, ceiling=False):
    """yt-dlp options that pick the streams for a resolution.

    "res" compares the picture size YouTube advertises, so wide and tall
    videos work like normal ones. "proto" puts direct downloads ahead of
    HLS streaming variants, and "vcodec:h264" then prefers H.264, which
    plays almost everywhere. YouTube has no H.264 above 1080p, so there
    the direct AV1 stream is used.
    """

    spec = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]"

    if ceiling:
        # Fallback for videos that have no MP4 streams.
        spec += "/bestvideo+bestaudio/best"

    return {
        "format": spec,
        "format_sort": [f"res:{quality}", "proto", "vcodec:h264"],
    }

def _without_selection(info):
    """A copy of the info without yt-dlp's earlier format choice."""

    clean = copy.deepcopy(info)

    for key in STALE_FORMAT_KEYS:
        clean.pop(key, None)

    return clean


def select_formats(info, selection):
    """What yt-dlp would pick for a selection, or None if it can't pick.

    Only used for estimates, so the formats are not test-downloaded.
    """

    try:
        with _ydl(noplaylist=True, check_formats=False, **selection) as ydl:
            return ydl.process_ie_result(
                _without_selection(info), download=False
            )
    except Exception:
        log.debug("Format selection failed for %s", selection, exc_info=True)
        return None
    
_SIZE_PROBE_TIMEOUT = 3
_MANIFEST_EXTS = (".m3u8", ".mpd")


def _probe_stream_size(stream, probe_cache):
    """Query Content-Length via HEAD (or 1-byte Range) for direct HTTPS streams."""

    url = stream.get("url")
    if not isinstance(url, str) or not url.startswith("https://"):
        return None

    protocol = (stream.get("protocol") or "").lower()
    if protocol and protocol not in ("http", "https"):
        return None

    path = urlparse(url).path.lower()
    if path.endswith(_MANIFEST_EXTS):
        return None

    if url in probe_cache:
        return probe_cache[url]

    headers = dict(stream.get("http_headers") or {})
    headers.setdefault("User-Agent", "Mozilla/5.0")

    size = None
    try:
        req = urllib.request.Request(url, headers=headers, method="HEAD")
        with urllib.request.urlopen(req, timeout=_SIZE_PROBE_TIMEOUT) as resp:
            if urlparse(resp.geturl()).scheme == "https":
                length = resp.headers.get("Content-Length")
                if length and length.isdigit() and int(length) > 0:
                    size = int(length)
    except Exception:
        size = None

    if size is None:
        try:
            range_headers = {**headers, "Range": "bytes=0-0"}
            req = urllib.request.Request(url, headers=range_headers, method="GET")
            with urllib.request.urlopen(req, timeout=_SIZE_PROBE_TIMEOUT) as resp:
                if urlparse(resp.geturl()).scheme == "https":
                    content_range = resp.headers.get("Content-Range", "")
                    if "/" in content_range:
                        total_str = content_range.rsplit("/", 1)[1].strip()
                        if total_str.isdigit() and int(total_str) > 0:
                            size = int(total_str)
                    if size is None:
                        length = resp.headers.get("Content-Length")
                        if length and length.isdigit() and int(length) > 1:
                            size = int(length)
        except Exception:
            size = None

    probe_cache[url] = size
    return size


def _streams_size(selected, probe_cache=None):
    """Combined size in bytes of a selection's streams, or None."""

    streams = selected.get("requested_formats") or [selected]
    duration = selected.get("duration")
    total = 0

    for stream in streams:
        size = stream.get("filesize") or stream.get("filesize_approx")

        # Fall back to bitrate x duration (tbr/vbr/abr are in kbit/s).
        if not size:
            bitrate = stream.get("tbr") or (
                (stream.get("vbr") or 0) + (stream.get("abr") or 0)
            )
            stream_duration = stream.get("duration") or duration
            if bitrate and stream_duration:
                size = bitrate * 125 * stream_duration

        if not size and probe_cache is not None:
            size = _probe_stream_size(stream, probe_cache)

        if not size:
            return None  # A partial total would be misleading.

        total += size

    return int(total)


def estimate_size(info, selection):
    """Estimated download size in bytes for a selection, or None."""

    selected = select_formats(info, selection)

    return _streams_size(selected) if selected else None


def quality_options(info):
    """List (resolution, estimated_size_or_None) for each available quality."""

    return [(opt.value, opt.size) for opt in video_format_options(info)]


_QUALITY_BADGES = {
    2160: "4K UHD",
    1440: "2K QHD",
    1080: "Full HD",
    720: "HD",
    480: "SD",
}


def _readable_vcodec(vcodec):
    """Turn raw yt-dlp vcodec strings into clean user-facing codec names."""

    code = (vcodec or "").lower()
    if code.startswith(("avc1", "h264")):
        return "H.264"
    if code.startswith(("av01", "av1")):
        return "AV1"
    if code.startswith(("vp9", "vp09")):
        return "VP9"
    if code.startswith(("hev1", "hvc1", "hevc")):
        return "HEVC"
    return ""


def video_format_options(info):
    """Return structured FormatOption objects for available video qualities."""

    started = time.monotonic()
    options = []
    probe_cache = {}

    for quality in available_qualities(info):
        selected = select_formats(info, video_selection(quality))
        if selected is None:
            selected = select_formats(info, video_selection(quality, ceiling=True))
        size = _streams_size(selected, probe_cache=probe_cache) if selected else None

        details_parts = ["MP4"]
        if selected:
            for stream in selected.get("requested_formats") or [selected]:
                if stream.get("vcodec") not in (None, "none"):
                    codec = _readable_vcodec(stream.get("vcodec"))
                    if codec:
                        details_parts.append(codec)
                    fps = stream.get("fps")
                    if fps and fps > 30:
                        details_parts.append(f"{int(round(fps))}fps")
                    break

        options.append(
            FormatOption(
                value=quality,
                label=f"{quality}p",
                badge=_QUALITY_BADGES.get(quality, ""),
                details=" · ".join(details_parts),
                size=size,
            )
        )

    log.info(
        "Estimated sizes for %d qualities in %.1fs",
        len(options), time.monotonic() - started,
    )

    return options


def playlist_video_format_options():
    """Return structured FormatOption objects for playlist max-quality ceilings."""

    return [
        FormatOption(
            value=quality,
            label=f"Up to {quality}p",
            badge=_QUALITY_BADGES.get(quality, ""),
            details="MP4 · Best available up to limit",
            size=None,
        )
        for quality in PLAYLIST_QUALITIES
    ]


_AUDIO_META = {
    128: ("Compact", "MP3 · Small file size · Cover art & tags"),
    192: ("Recommended", "MP3 · Balanced quality · Cover art & tags"),
    256: ("High Quality", "MP3 · Crisp audio · Cover art & tags"),
    320: ("Maximum", "MP3 · Studio bitrate · Cover art & tags"),
    ORIGINAL_AUDIO: ("No Conversion", "M4A · Native source stream · Cover art & tags"),
}


def audio_format_options(info=None):
    """Return structured FormatOption objects for available audio choices."""

    duration = (info or {}).get("duration")
    original_size = None
    if info:
        selected = select_formats(info, {"format": "bestaudio[ext=m4a]"})
        if selected is None:
            selected = select_formats(info, {"format": "bestaudio/best"})
        original_size = _streams_size(selected, probe_cache={}) if selected else None

    options = []
    for choice in AUDIO_CHOICES:
        badge, details = _AUDIO_META.get(choice, ("", "Audio"))

        if choice == ORIGINAL_AUDIO:
            label = "Original Audio"
            size = original_size
        else:
            label = f"{choice} kbps MP3"
            size = int(choice * 125 * duration) if duration else None

        options.append(
            FormatOption(
                value=choice,
                label=label,
                badge=badge,
                details=details,
                size=size,
            )
        )

    return options


_IMAGE_FORMAT_META = {
    ORIGINAL_IMAGE: ("No Conversion", "Native image format and resolution"),
    "png": ("Lossless", "PNG · High-quality lossless image"),
    "jpg": ("Universal", "JPG · Compact & widely compatible"),
    "webp": ("Modern", "WebP · Small file size"),
}

IMAGE_MAX_BYTES = 50 * 1024 * 1024
_IMAGE_MIME_TO_EXT = {
    "image/jpeg": "jpg",
    "image/jpg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
    "image/gif": "gif",
    "image/bmp": "bmp",
    "image/avif": "avif",
}
_DIRECT_IMAGE_EXTS = ("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
_OG_TAG_RE = re.compile(
    r'<meta[^>]+(?:property|name)=["\']([^"\']+)["\'][^>]+content=["\']([^"\']*)["\']'
    r'|<meta[^>]+content=["\']([^"\']*)["\'][^>]+(?:property|name)=["\']([^"\']+)["\']',
    re.IGNORECASE,
)


def _image_ext_from_url(url):
    try:
        parsed = urlparse(url)
    except ValueError:
        return None

    path = parsed.path.lower()
    for ext in _DIRECT_IMAGE_EXTS:
        if path.endswith(f".{ext}"):
            return "jpg" if ext == "jpeg" else ext

    fmt = (parse_qs(parsed.query).get("format") or [None])[0]
    if fmt and fmt.lower() in _DIRECT_IMAGE_EXTS:
        return "jpg" if fmt.lower() == "jpeg" else fmt.lower()

    return None


def _make_image_info(
    url, title="", ext=None, size=None, channel="", media_id="", width=None, height=None
):
    ext = ext or _image_ext_from_url(url) or "jpg"
    vid = media_id or hashlib.sha1(url.encode("utf-8", errors="ignore")).hexdigest()[:11]
    return {
        "_zardit_media_kind": "image",
        "id": vid,
        "title": title or Path(urlparse(url).path).stem or "Image",
        "uploader": channel or detect_platform(url),
        "thumbnail": url,
        "image_url": url,
        "ext": ext,
        "filesize": size,
        "width": width,
        "height": height,
        "webpage_url": url,
        "formats": [
            {
                "format_id": "image",
                "url": url,
                "ext": ext,
                "vcodec": "images",
                "filesize": size,
                "width": width,
                "height": height,
            }
        ],
    }


def _probe_direct_image(url):
    if not isinstance(url, str) or not url.startswith("https://"):
        return None

    ext_hint = _image_ext_from_url(url)
    try:
        req = urllib.request.Request(
            url, headers={"User-Agent": "Mozilla/5.0"}, method="HEAD"
        )
        with urllib.request.urlopen(req, timeout=_SIZE_PROBE_TIMEOUT) as resp:
            if urlparse(resp.geturl()).scheme != "https":
                return None
            ctype = (resp.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            length = resp.headers.get("Content-Length")
            size = int(length) if length and length.isdigit() else None
            if ctype in _IMAGE_MIME_TO_EXT or ctype.startswith("image/"):
                ext = _IMAGE_MIME_TO_EXT.get(ctype) or ext_hint or "jpg"
                return _make_image_info(url, ext=ext, size=size)
    except Exception:
        pass

    if ext_hint:
        return _make_image_info(url, ext=ext_hint)

    return None


def _extract_reddit_images(url):
    if detect_platform(url) != "Reddit":
        return None

    try:
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname:
            return None
        clean_path = parsed.path.rstrip("/")
        if not clean_path or clean_path.endswith(".json"):
            json_url = f"https://{parsed.hostname}{clean_path}.json" if not clean_path.endswith(".json") else url
        else:
            json_url = f"https://{parsed.hostname}{clean_path}.json"

        req = urllib.request.Request(
            json_url, headers={"User-Agent": f"Zardit/{APP_VERSION}"}
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            if urlparse(resp.geturl()).scheme != "https":
                return None
            data = json.loads(resp.read(2 * 1024 * 1024).decode("utf-8", errors="replace"))
    except Exception:
        return None

    if not isinstance(data, list) or not data:
        return None

    try:
        post = data[0]["data"]["children"][0]["data"]
    except (KeyError, IndexError, TypeError):
        return None

    title = post.get("title") or "Reddit Image"
    author = post.get("author") or "Reddit"
    post_id = post.get("id") or ""

    if post.get("is_gallery") and isinstance(post.get("media_metadata"), dict):
        items = (post.get("gallery_data") or {}).get("items") or []
        media_meta = post["media_metadata"]
        ordered_ids = [it.get("media_id") for it in items if it.get("media_id")] or list(media_meta.keys())
        entries = []
        for idx, mid in enumerate(ordered_ids, start=1):
            meta = media_meta.get(mid) or {}
            src = (meta.get("s") or {}).get("u") or ""
            img_url = html.unescape(src)
            if img_url.startswith("https://"):
                entries.append({
                    "_zardit_media_kind": "image",
                    "id": mid,
                    "title": f"{title} ({idx})",
                    "uploader": author,
                    "url": img_url,
                    "webpage_url": img_url,
                    "thumbnail": img_url,
                })
        if len(entries) == 1:
            return _make_image_info(
                entries[0]["url"],
                title=title,
                channel=author,
                media_id=entries[0]["id"],
            )
        if entries:
            return {
                "_type": "playlist",
                "title": title,
                "entries": entries,
            }

    dest = html.unescape(post.get("url_overridden_by_dest") or post.get("url") or "")
    if dest.startswith("https://") and _image_ext_from_url(dest):
        return _make_image_info(dest, title=title, channel=author, media_id=post_id)

    try:
        preview_url = html.unescape(post["preview"]["images"][0]["source"]["url"])
        if preview_url.startswith("https://"):
            return _make_image_info(
                preview_url, title=title, channel=author, media_id=post_id
            )
    except (KeyError, IndexError, TypeError):
        pass

    return None


def _extract_og_image(url):
    try:
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname:
            return None
        req = urllib.request.Request(
            url, headers={"User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            if urlparse(resp.geturl()).scheme != "https":
                return None
            ctype = (resp.headers.get("Content-Type") or "").lower()
            if ctype.startswith("image/"):
                ext = _IMAGE_MIME_TO_EXT.get(ctype.split(";", 1)[0].strip()) or "jpg"
                return _make_image_info(resp.geturl(), ext=ext)
            if "html" not in ctype:
                return None
            body = resp.read(512 * 1024).decode("utf-8", errors="replace")
    except Exception:
        return None

    meta = {}
    for match in _OG_TAG_RE.finditer(body):
        key = (match.group(1) or match.group(4) or "").lower()
        val = html.unescape(match.group(2) or match.group(3) or "").strip()
        if key and val and key not in meta:
            meta[key] = val

    img_url = meta.get("og:image:secure_url") or meta.get("og:image") or meta.get("twitter:image")
    if not img_url or not img_url.startswith("https://"):
        return None

    title = meta.get("og:title") or meta.get("twitter:title") or ""
    width = int(meta["og:image:width"]) if (meta.get("og:image:width") or "").isdigit() else None
    height = int(meta["og:image:height"]) if (meta.get("og:image:height") or "").isdigit() else None
    return _make_image_info(
        img_url,
        title=title,
        channel=detect_platform(url),
        width=width,
        height=height,
    )


_FB_IMAGE_RE = re.compile(
    r'"(?:viewer_image|photo_image|image)"\s*:\s*\{\s*"uri"\s*:\s*"(https?:\\/\\/[^"]+)"'
    r'(?:[^}]*?"height"\s*:\s*(\d+))?(?:[^}]*?"width"\s*:\s*(\d+))?',
    re.IGNORECASE,
)


def _extract_facebook_images(url):
    """Extract full-resolution uncropped images from a Facebook post/photo page."""

    if detect_platform(url) != "Facebook":
        return None

    try:
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname:
            return None
        req = urllib.request.Request(
            url, headers={"User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            if urlparse(resp.geturl()).scheme != "https":
                return None
            ctype = (resp.headers.get("Content-Type") or "").lower()
            if "html" not in ctype:
                return None
            body = resp.read(1024 * 1024).decode("utf-8", errors="replace")
    except Exception:
        return None

    meta = {}
    for match in _OG_TAG_RE.finditer(body):
        key = (match.group(1) or match.group(4) or "").lower()
        val = html.unescape(match.group(2) or match.group(3) or "").strip()
        if key and val and key not in meta:
            meta[key] = val

    title = meta.get("og:title") or meta.get("twitter:title") or "Facebook Photos"

    seen_keys = set()
    entries = []
    for match in _FB_IMAGE_RE.finditer(body):
        raw_uri = match.group(1).replace("\\/", "/")
        try:
            img_url = bytes(raw_uri, "utf-8").decode("unicode_escape")
        except Exception:
            img_url = raw_uri

        if not img_url.startswith("https://") or _is_cropped_thumb_url(img_url):
            continue

        h = int(match.group(2)) if match.group(2) else None
        w = int(match.group(3)) if match.group(3) else None
        if (w and w < 250) or (h and h < 250):
            continue

        dedup_key = urlparse(img_url).path
        if dedup_key in seen_keys:
            continue
        seen_keys.add(dedup_key)

        idx = len(entries) + 1
        dim = f" ({w}×{h})" if w and h else ""
        entries.append({
            "_zardit_media_kind": "image",
            "id": hashlib.sha1(dedup_key.encode("utf-8", errors="ignore")).hexdigest()[:11],
            "title": f"Photo {idx}{dim}",
            "uploader": "Facebook",
            "url": img_url,
            "webpage_url": img_url,
            "image_url": img_url,
            "thumbnail": img_url,
            "width": w,
            "height": h,
        })

    if len(entries) == 1:
        e = entries[0]
        return _make_image_info(
            e["image_url"],
            title=title,
            channel="Facebook",
            media_id=e["id"],
            width=e.get("width"),
            height=e.get("height"),
        )
    if entries:
        return {
            "_type": "playlist",
            "title": title,
            "entries": entries,
        }

    return None


def _is_deferred_video_entry(entry):
    """True for flat/lazy playlist items that still need per-video extraction."""

    if entry.get("_zardit_media_kind") == "image":
        return False
    if available_qualities(entry):
        return True

    ext = (entry.get("ext") or "").lower()
    if ext in _DIRECT_IMAGE_EXTS or entry.get("vcodec") == "images":
        return False

    if entry.get("_type") in ("url", "url_transparent") or entry.get("ie_key"):
        return True
    if entry.get("duration"):
        return True

    entry_url = entry.get("url") or entry.get("webpage_url") or ""
    if (
        entry_url
        and not _image_ext_from_url(entry_url)
        and detect_platform(entry_url) == "YouTube"
    ):
        return True

    return False


def _normalize_playlist_entries(entries, playlist_title, source_url):
    """Ensure multi-photo and mixed carousel slides carry clean metadata & URLs."""

    for idx, entry in enumerate(entries, start=1):
        if not _is_deferred_video_entry(entry):
            img_url, width, height = best_image_details(entry)
            if img_url:
                entry["_zardit_media_kind"] = "image"
                entry["image_url"] = img_url
                entry["thumbnail"] = img_url
                if width and not entry.get("width"):
                    entry["width"] = width
                if height and not entry.get("height"):
                    entry["height"] = height
                dim = f" ({width}×{height})" if width and height else ""
                raw_title = (entry.get("title") or "").strip()
                generic = (
                    not raw_title
                    or raw_title == playlist_title
                    or raw_title.startswith(("Video by ", "Post by "))
                )
                if generic:
                    entry["title"] = f"Photo {idx}{dim}"
                elif dim and dim not in raw_title:
                    entry["title"] = f"{raw_title} · Photo {idx}{dim}"

        if not entry.get("webpage_url") and not entry.get("url"):
            entry["webpage_url"] = source_url

        if not entry.get("title"):
            entry["title"] = f"Item {idx}"


_CROPPED_URL_RE = re.compile(
    r"(?:/[sp]\d+x\d+/|/c\d+\.\d+\.\d+\.\d+|dst-jpg_e\d+_[sp]|sqp=)",
    re.IGNORECASE,
)


def _is_cropped_thumb_url(url):
    return bool(url and _CROPPED_URL_RE.search(url))


def best_image_details(info):
    """Return (url, width, height) for the best uncropped full-ratio image."""

    if not info:
        return (None, None, None)

    if info.get("image_url"):
        return (info["image_url"], info.get("width"), info.get("height"))

    def _score(item):
        url = item.get("url") or ""
        w = item.get("width") or 0
        h = item.get("height") or 0
        uncropped = 1 if not _is_cropped_thumb_url(url) else 0
        return (uncropped, w * h, item.get("preference") or 0)

    image_formats = [
        f for f in info.get("formats", [])
        if f.get("url") and (
            (f.get("ext") or "").lower() in _DIRECT_IMAGE_EXTS
            or f.get("vcodec") == "images"
        )
    ]
    if image_formats:
        best = max(image_formats, key=_score)
        return (best.get("url"), best.get("width"), best.get("height"))

    thumbs = [t for t in info.get("thumbnails", []) if t.get("url")]
    if thumbs:
        best = max(thumbs, key=_score)
        return (best.get("url"), best.get("width"), best.get("height"))

    return (info.get("thumbnail"), info.get("width"), info.get("height"))


def best_image_url(info):
    """Return the highest-resolution uncropped image URL found in info."""

    return best_image_details(info)[0]


def is_image_only_info(info):
    """True when the fetched item is an image post without video streams."""

    if not info:
        return False
    if info.get("_zardit_media_kind") == "image":
        return True
    return not available_qualities(info) and bool(best_image_url(info))


def image_format_options(info=None):
    """Return structured FormatOption objects for image downloads (without size estimates)."""

    img_url = best_image_url(info)
    url_ext = _image_ext_from_url(img_url or "") if img_url else None
    info_ext = ((info or {}).get("ext") or "").lower()
    if is_image_only_info(info) and info_ext in _DIRECT_IMAGE_EXTS:
        source_ext = info_ext
    elif url_ext in _DIRECT_IMAGE_EXTS:
        source_ext = url_ext
    elif info_ext in _DIRECT_IMAGE_EXTS:
        source_ext = info_ext
    else:
        source_ext = "jpg"

    options = []
    for fmt in IMAGE_FORMATS:
        badge, details = _IMAGE_FORMAT_META.get(fmt, ("", "Image"))
        if fmt == ORIGINAL_IMAGE:
            label = f"Original ({source_ext.upper()})"
            details = f"{source_ext.upper()} · Native resolution (no conversion)"
        else:
            label = f"{fmt.upper()} Image"

        options.append(
            FormatOption(
                value=fmt,
                label=label,
                badge=badge,
                details=details,
                size=None,
            )
        )

    return options


def _video_tag(selected, requested):
    """The '[1080p]' part of a file name, from the picture actually chosen."""

    selected = selected or {}
    label = None

    for stream in selected.get("requested_formats") or [selected]:
        if stream.get("vcodec") not in (None, "none"):
            label = resolution_label(stream)
            break

    return f"[{label or requested}p]"

# --------------------------------------------------------------------------
# Files and folders
# --------------------------------------------------------------------------

# Files yt-dlp leaves behind when a download stops part-way: partial and
# resume files, separate streams (.f137.mp4), conversion files (.temp.mp3)
# and thumbnails.
TEMP_FILE_PATTERN = re.compile(
    r"(\.part(-Frag\d+)?"
    r"|\.ytdl"
    r"|\.temp\.\w+"
    r"|\.f[\w-]+\.\w+(\.part)?"
    r"|\.(webp|jpg|jpeg|png))$"
)

# Disk space needed, as a multiple of the estimated download size. A video
# needs room for its separate streams and the merged file at the same time.
SPACE_FACTOR = {"video": 2.1, "audio": 1.6}
SPACE_SLACK = 50 * 1024 * 1024


def get_output_directory(output_dir=None):
    """Resolve, validate, and create the destination directory."""

    directory = (
        Path(output_dir).expanduser().resolve()
        if output_dir is not None
        else get_download_dir()
    )

    if directory.exists() and not directory.is_dir():
        raise NotADirectoryError(
            f"Download location is not a directory: {directory}"
        )

    directory.mkdir(parents=True, exist_ok=True)

    return directory


def _check_disk_space(folder, expected_size, kind):
    """Raise DownloadError if the download clearly won't fit on the disk."""

    if not expected_size:
        return

    try:
        free = shutil.disk_usage(folder).free
    except OSError:
        return

    needed = int(expected_size * SPACE_FACTOR[kind]) + SPACE_SLACK

    if free < needed:
        raise DownloadError(
            f"Not enough free disk space in {folder}: about "
            f"{format_file_size(needed)} needed, "
            f"{format_file_size(free)} available."
        )


def find_existing_download(output_dir, video_id, extension, quality_label=None):
    """Find an earlier download of this video.

    extension is one extension or a list of them. With a quality_label, only
    that quality matches. Without one, any quality of the video matches.
    """

    extensions = (extension,) if isinstance(extension, str) else tuple(extension)
    suffixes = []

    for ext in extensions:
        if quality_label:
            suffixes.append(f"[{quality_label}] [{video_id}].{ext}")
            suffixes.append(f"[{video_id}] [{quality_label}].{ext}")
        else:
            suffixes.append(f"[{video_id}].{ext}")

    suffixes = tuple(suffixes)

    for file in output_dir.iterdir():
        if file.is_file() and file.name.endswith(suffixes):
            return file

    return None

def cleanup_incomplete_files(output_dir, video_id):
    """Remove leftover files of one video. Returns the removed names."""

    removed = []

    if output_dir is None or not video_id:
        return removed

    marker = f"[{video_id}]"

    try:
        for file in output_dir.iterdir():
            if (
                file.is_file()
                and marker in file.name
                and TEMP_FILE_PATTERN.search(file.name)
            ):
                try:
                    file.unlink()
                    removed.append(file.name)
                except OSError:
                    pass

    except OSError:
        pass

    return removed


# --------------------------------------------------------------------------
# Downloading
# --------------------------------------------------------------------------

class ProgressTracker:
    """yt-dlp progress hook: one overall progress across all streams."""

    def __init__(self, reporter, expected_total=None, cancel=None):
        self.reporter = reporter
        self.expected_total = expected_total
        self.cancel = cancel
        self.streams = {}

    def __call__(self, d):
        if _is_cancelled(self.cancel):
            raise DownloadCancelled()

        status = d.get("status")
        key = d.get("filename") or "stream"

        if status == "finished":
            size = d.get("total_bytes") or d.get("downloaded_bytes")

            if size:
                self.streams[key] = size

        elif status == "downloading":
            self.streams[key] = d.get("downloaded_bytes") or 0

        else:
            return

        downloaded = sum(self.streams.values())

        if self.expected_total:
            self.expected_total = max(self.expected_total, downloaded)
            total = self.expected_total
        else:
            # Unknown total: fall back to the current stream's numbers.
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            downloaded = self.streams[key]

        speed = d.get("speed")
        eta = (
            max(0.0, (total - downloaded) / speed)
            if total and speed
            else None
        )

        self.reporter.progress(downloaded, total, speed, eta)


def _postprocessor_hook(reporter):
    messages = {
        "Merger": "Merging video and audio...",
        "ExtractAudio": "Converting to MP3...",
        "FFmpegExtractAudio": "Converting to MP3...",
    }
    shown = set()

    def hook(d):
        if d.get("status") != "started":
            return

        text = messages.get(d.get("postprocessor", ""))

        if text and text not in shown:
            shown.add(text)
            reporter.stage(text)

    return hook


RETRY_DELAYS = (3, 8)   # seconds to wait before the 2nd and 3rd attempt
RETRYABLE = re.compile(r"HTTP Error (403|429|5\d\d)")


def _run_once(options, url, info):
    """Download once.

    With info, the video isn't extracted again, which saves several seconds.
    If reusing the information fails unexpectedly, fall back to the normal way.
    """

    with _ydl(**options) as ydl:
        if info is not None:
            try:
                ydl.process_ie_result(_without_selection(info), download=True)
                return

            except UnavailableVideoError as error:
                ydl.report_error(error)   # Raises yt-dlp's DownloadError.

            except (KeyError, TypeError, AttributeError, ValueError):
                log.warning(
                    "Could not reuse the fetched information; "
                    "extracting again",
                    exc_info=True,
                )

        if ydl.download([url]):
            raise DownloadError("Download failed. See the log for details.")


def _run_with_retries(options, url, reporter, cancel, info=None):
    """Run yt-dlp, retrying temporary HTTP errors.

    The first attempt reuses the already fetched information. A retry
    extracts again, because the download links may be what failed.
    Partial files are kept, so a retry resumes where it stopped.
    """

    for attempt in range(len(RETRY_DELAYS) + 1):
        try:
            _run_once(options, url, info if attempt == 0 else None)
            return

        except _YtdlpDownloadError as error:
            match = RETRYABLE.search(ANSI_PATTERN.sub("", str(error)))

            if not match or attempt == len(RETRY_DELAYS):
                raise

            delay = RETRY_DELAYS[attempt]

            log.warning(
                "HTTP %s for %s; retrying in %s seconds (attempt %d)",
                match.group(1), url, delay, attempt + 2,
            )
            reporter.message(
                f"Temporary error (HTTP {match.group(1)}). "
                f"Retrying in {delay} seconds...",
                "warning",
            )

            if (cancel or threading.Event()).wait(delay):
                raise DownloadCancelled()

def _check_request(url, kind, setting):
    """Raise DownloadError if the request can't be carried out."""

    if not validate_url(url):
        raise DownloadError("Invalid media URL.")

    if kind == "video":
        if (
            not isinstance(setting, int)
            or isinstance(setting, bool)
            or setting <= 0
        ):
            raise DownloadError("Invalid video resolution.")

    elif kind == "audio":
        if setting != ORIGINAL_AUDIO and setting not in AUDIO_BITRATES:
            raise DownloadError("Unsupported audio quality.")

    elif kind == "image":
        if setting not in IMAGE_FORMATS:
            raise DownloadError("Unsupported image format.")

    else:
        raise DownloadError("Unsupported download type.")

    if kind in ("video", "audio") and not shutil.which("ffmpeg"):
        raise DownloadError("FFmpeg is required but was not found.")


def _plan(kind, setting, ceiling):
    """What to download and how, for a video or an audio request."""

    if kind == "video":
        return {
            "extension": "mp4",
            "quality_label": None if ceiling else f"{setting}p",
            "description": (
                f"MP4 (up to {setting}p)" if ceiling else f"MP4 ({setting}p)"
            ),
            "selection": video_selection(setting, ceiling),
            # In ceiling mode the name uses the resolution actually chosen.
            "tag": None if ceiling else f"[{setting}p]",
            "options": {"merge_output_format": "mp4"},
        }

    # Tags and cover art, for both kinds of audio.
    finishing = [
        {"key": "FFmpegMetadata", "add_metadata": True},
        {
            "key": "FFmpegThumbnailsConvertor",
            "format": "jpg",
            "when": "before_dl",
        },
        {"key": "EmbedThumbnail"},
    ]

    if setting == ORIGINAL_AUDIO:
        return {
            "extension": "m4a",
            "quality_label": ORIGINAL_AUDIO,
            "description": "original audio (M4A, no conversion)",
            "selection": {"format": "bestaudio[ext=m4a]"},
            "tag": f"[{ORIGINAL_AUDIO}]",
            "options": {
                "writethumbnail": True,
                "postprocessors": finishing,
            },
        }

    return {
        "extension": "mp3",
        "quality_label": f"{setting}kbps",
        "description": f"MP3 ({setting} kbps)",
        "selection": {"format": "bestaudio/best"},
        "tag": f"[{setting}kbps]",
        "options": {
            "writethumbnail": True,
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": str(setting),
                },
                *finishing,
            ],
        },
    }

def _existing_result(folder, video_id, plan):
    """A DownloadResult("exists") if this was downloaded before, else None."""

    if not video_id:
        return None

    existing = find_existing_download(
        folder, video_id, plan["extension"], plan["quality_label"]
    )

    if existing:
        log.debug("Already downloaded: %s", existing.name)
        return DownloadResult("exists", folder, existing.name, existing)

    return None


def _download(
    url, kind, setting, output_dir, info, ceiling, reporter, cancel,
    video_id=None,
):
    """Download one video ("video") or audio track ("audio").

    A known video_id lets a finished item be skipped without any network
    request.
    """

    reporter = reporter or Reporter()

    _check_request(url, kind, setting)

    plan = _plan(kind, setting, ceiling)
    folder = None

    try:
        folder = get_output_directory(output_dir)

        if _is_cancelled(cancel):
            return DownloadResult("cancelled", folder)

        video_id = video_id or (info or {}).get("id")

        found = _existing_result(folder, video_id, plan)

        if found:
            return found

        if info is None:
            info = fetch_video_info(url)
            video_id = info.get("id")

            found = _existing_result(folder, video_id, plan)

            if found:
                return found

        if not video_id:
            raise DownloadError("Could not identify this video.")

        log.info(
            "Downloading %s (%s) from %s into %s",
            kind, setting, url, folder,
        )
        reporter.message(f"Downloading {plan['description']}...")

        selected = select_formats(info, plan["selection"])
        active_selection = plan["selection"]
        active_options = plan["options"]
        if kind == "video" and selected is None and not ceiling:
            fallback_selection = video_selection(setting, ceiling=True)
            selected = select_formats(info, fallback_selection)
            if selected is not None:
                active_selection = fallback_selection
        elif kind == "audio" and setting == ORIGINAL_AUDIO and selected is None:
            fallback_selection = {"format": "bestaudio/best"}
            selected = select_formats(info, fallback_selection)
            if selected is not None:
                active_selection = fallback_selection
                if (selected.get("ext") or "").lower() != "m4a":
                    active_options = {
                        **plan["options"],
                        "postprocessors": [
                            {"key": "FFmpegExtractAudio", "preferredcodec": "m4a"},
                            *plan["options"]["postprocessors"],
                        ],
                    }

        expected = _streams_size(selected) if selected else None
        _check_disk_space(folder, expected, kind)

        tag = plan["tag"] or _video_tag(selected, setting)
        tracker = ProgressTracker(reporter, expected, cancel)

        options = {
            **active_selection,
            "paths": {"home": str(folder)},
            "outtmpl": build_outtmpl(folder, info.get("title"), video_id, tag),
            "windowsfilenames": True,
            "noplaylist": True,
            "noprogress": True,
            "nooverwrites": True,
            "continuedl": True,
            "retries": 5,
            "fragment_retries": 10,
            "extractor_retries": 3,
            "socket_timeout": 30,
            "progress_hooks": [tracker],
            "postprocessor_hooks": [_postprocessor_hook(reporter)],
        }
        options.update(active_options)

        started = time.monotonic()

        try:
            _run_with_retries(options, url, reporter, cancel, info)
        finally:
            reporter.progress_done()

        saved_file = find_existing_download(
            folder, video_id, plan["extension"], tag.strip("[]")
        ) or find_existing_download(folder, video_id, plan["extension"])

        log.info(
            "Completed: %s in %.1fs", url, time.monotonic() - started
        )

        return DownloadResult(
            "completed",
            folder,
            saved_file.name if saved_file else None,
            saved_file,
        )

    except (DownloadCancelled, KeyboardInterrupt):
        removed = cleanup_incomplete_files(folder, video_id)

        log.info("Cancelled: %s (removed %d leftover files)", url, len(removed))

        if removed:
            reporter.message(
                f"Removed {len(removed)} incomplete file(s).", "warning"
            )

        return DownloadResult("cancelled", folder)

    except DownloadError as error:
        log.warning("Stopped: %s (%s)", error, url)
        raise

    except Exception as error:
        log.error("Download failed for %s", url, exc_info=True)
        raise DownloadError(friendly_error(error)) from error


def download_video(
    url, quality, output_dir=None, info=None, ceiling=False,
    reporter=None, cancel=None,
):
    """Download and merge video and audio into an MP4 file.

    With ceiling=True, quality is a maximum height rather than an exact one.
    Returns a DownloadResult, or raises DownloadError.
    """

    return _download(
        url, "video", quality, output_dir, info, ceiling, reporter, cancel
    )


def download_audio(
    url, bitrate, output_dir=None, info=None, reporter=None, cancel=None,
):
    """Download audio with cover art and tags.

    bitrate is an MP3 bitrate (such as 192), or ORIGINAL_AUDIO to save
    source audio as it is, without converting it.
    Returns a DownloadResult, or raises DownloadError.
    """

    return _download(
        url, "audio", bitrate, output_dir, info, False, reporter, cancel
    )


def download_image(
    url, fmt=ORIGINAL_IMAGE, output_dir=None, info=None, reporter=None, cancel=None,
):
    """Download an image (or video thumbnail) as original, PNG, JPG, or WebP."""

    reporter = reporter or Reporter()
    _check_request(url, "image", fmt)

    folder = None
    media_id = None

    try:
        folder = get_output_directory(output_dir)

        if _is_cancelled(cancel):
            return DownloadResult("cancelled", folder)

        if info is None:
            info = fetch_video_info(url)

        image_url = best_image_url(info)
        if not image_url:
            raise DownloadError("No downloadable image found for this link.")

        parsed = urlparse(image_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise DownloadError("Image link is not valid.")

        media_id = sanitize_name(
            info.get("id")
            or hashlib.sha1(image_url.encode("utf-8", errors="ignore")).hexdigest()[:11],
            max_bytes=32,
            fallback="image",
        )
        source_ext = (
            (info.get("ext") or _image_ext_from_url(image_url) or "jpg").lower()
        )
        if source_ext not in _DIRECT_IMAGE_EXTS:
            source_ext = "jpg"

        target_ext = source_ext if fmt == ORIGINAL_IMAGE else fmt
        quality_label = ORIGINAL_IMAGE if fmt == ORIGINAL_IMAGE else fmt

        existing = find_existing_download(
            folder, media_id, target_ext, quality_label
        )
        if existing:
            return DownloadResult("exists", folder, existing.name, existing)

        if fmt != ORIGINAL_IMAGE and target_ext != source_ext and not shutil.which("ffmpeg"):
            raise DownloadError(
                "FFmpeg is required to convert images. "
                "Try Original image instead or install FFmpeg."
            )

        title = info.get("title") or "Image"
        tag = f"[{quality_label}]"
        safe_title = sanitize_name(
            title, max_bytes=max_title_bytes(folder), fallback=media_id
        )
        final_path = folder / f"{safe_title} {tag} [{media_id}].{target_ext}"
        temp_path = folder / f"{safe_title} {tag} [{media_id}].tmp.{source_ext}"

        reporter.message(
            f"Downloading {target_ext.upper()} image..."
        )

        headers = dict(info.get("http_headers") or {})
        headers.setdefault("User-Agent", "Mozilla/5.0")
        req = urllib.request.Request(image_url, headers=headers)
        started = time.monotonic()
        downloaded = 0

        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                ctype = (resp.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
                if ctype in _IMAGE_MIME_TO_EXT and not info.get("ext"):
                    detected = _IMAGE_MIME_TO_EXT[ctype]
                    if fmt == ORIGINAL_IMAGE and detected != target_ext:
                        target_ext = detected
                        final_path = folder / f"{safe_title} {tag} [{media_id}].{target_ext}"
                    source_ext = detected

                total_hdr = resp.headers.get("Content-Length")
                total = int(total_hdr) if total_hdr and total_hdr.isdigit() else None

                if total and total > IMAGE_MAX_BYTES:
                    raise DownloadError("Image file is larger than the 50 MB limit.")

                with open(temp_path, "wb") as handle:
                    while True:
                        if _is_cancelled(cancel):
                            raise DownloadCancelled()
                        chunk = resp.read(64 * 1024)
                        if not chunk:
                            break
                        downloaded += len(chunk)
                        if downloaded > IMAGE_MAX_BYTES:
                            raise DownloadError(
                                "Image file is larger than the 50 MB limit."
                            )
                        handle.write(chunk)

                        elapsed = max(0.05, time.monotonic() - started)
                        speed = downloaded / elapsed
                        eta = (
                            max(0.0, (total - downloaded) / speed)
                            if total and speed
                            else None
                        )
                        reporter.progress(downloaded, total, speed, eta)
        finally:
            reporter.progress_done()

        if target_ext == source_ext:
            temp_path.replace(final_path)
        else:
            reporter.stage(f"Converting to {target_ext.upper()}...")
            options = {"creationflags": 0x08000000} if sys.platform == "win32" else {}
            proc = subprocess.run(
                [
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-i", str(temp_path), str(final_path),
                ],
                capture_output=True,
                text=True,
                **options,
            )
            try:
                temp_path.unlink()
            except OSError:
                pass
            if proc.returncode != 0 or not final_path.is_file():
                raise DownloadError("Could not convert image format.")

        log.info("Completed image download: %s -> %s", image_url, final_path.name)
        return DownloadResult("completed", folder, final_path.name, final_path)

    except (DownloadCancelled, KeyboardInterrupt):
        for candidate in list(folder.glob(f"*[{media_id}]*.tmp.*")) if folder and media_id else []:
            try:
                candidate.unlink()
            except OSError:
                pass
        return DownloadResult("cancelled", folder)

    except DownloadError:
        raise

    except Exception as error:
        log.error("Image download failed for %s", url, exc_info=True)
        raise DownloadError(friendly_error(error)) from error


def download_playlist(
    entries, kind, setting, folder_name, reporter=None, cancel=None,
):
    """Download playlist entries one by one into their own folder.

    kind is "video" (setting = maximum height) or "audio" (setting = bitrate).
    A failing item is recorded and the playlist continues.
    """

    reporter = reporter or Reporter()

    try:
        folder = get_output_directory() / safe_folder_name(folder_name)
        folder.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        log.error("Could not create playlist folder", exc_info=True)
        raise DownloadError(friendly_error(error)) from error

    log.info("Playlist of %d items into %s", len(entries), folder)
    reporter.message(f"Folder: {folder}")

    result = PlaylistResult(total=len(entries), folder=folder)

    for number, entry in enumerate(entries, start=1):
        if _is_cancelled(cancel):
            result.cancelled = True
            break

        video_id = entry.get("id")
        title = entry.get("title") or video_id or f"Item {number}"

        reporter.playlist_item(number, result.total, title)

        if not video_id and not (entry.get("webpage_url") or entry.get("url")):
            reporter.message(f"{title}: missing video ID.", "error")
            result.failed.append((title, "Missing video ID."))
            continue

        entry_url = (
            entry.get("webpage_url")
            or entry.get("url")
            or f"https://www.youtube.com/watch?v={video_id}"
        )

        try:
            is_image_entry = entry.get("_zardit_media_kind") == "image"
            if kind == "image" or (kind == "video" and is_image_entry):
                entry_info = (
                    entry
                    if is_image_entry or best_image_url(entry)
                    else None
                )
                img_fmt = setting if kind == "image" else ORIGINAL_IMAGE
                outcome = download_image(
                    entry_url,
                    img_fmt,
                    output_dir=folder,
                    info=entry_info,
                    reporter=reporter,
                    cancel=cancel,
                )
            else:
                pre_info = entry if entry.get("formats") else None
                outcome = _download(
                    entry_url,
                    kind, setting, folder, pre_info, True, reporter, cancel,
                    video_id=video_id,
                )

        except DownloadError as error:
            reporter.message(str(error), "error")
            result.failed.append((title, str(error)))
            continue

        if outcome.status == "completed":
            result.completed += 1
            if outcome.file_path is not None:
                actual_kind = "image" if (kind == "image" or is_image_entry) else kind
                desc = (
                    f"{(setting if kind == 'image' else ORIGINAL_IMAGE).upper()} image"
                    if actual_kind == "image"
                    else _plan(kind, setting, True)["description"]
                )
                result.completed_items.append({
                    "title": title,
                    "video_id": video_id,
                    "file_path": outcome.file_path,
                    "kind": actual_kind,
                    "quality": desc,
                    "duration": entry.get("duration"),
                })

        elif outcome.status == "exists":
            result.skipped += 1
            reporter.message(
                f"Already downloaded: {outcome.filename}", "warning"
            )

        else:
            result.cancelled = True
            break

    log.info(
        "Playlist done: %d completed, %d skipped, %d failed%s",
        result.completed, result.skipped, len(result.failed),
        " (cancelled)" if result.cancelled else "",
    )

    return result