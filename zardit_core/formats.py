"""Shared format resolution and image-info helpers."""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from config import ORIGINAL_IMAGE
from zardit_core.urls import detect_platform

NOTE_PATTERN = re.compile(r"^(\d{3,4})p")

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


def resolution_label(video_format):
    """The resolution a source calls a format, such as 1080 for '1080p60'."""

    match = NOTE_PATTERN.match(video_format.get("format_note") or "")
    if match:
        return int(match.group(1))

    height = video_format.get("height")
    width = video_format.get("width")
    if height and width:
        return min(height, width)

    return height or None


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
    url, title="", ext=None, size=None, channel="", media_id="", width=None, height=None, headers=None
):
    ext = ext or _image_ext_from_url(url) or "jpg"
    vid = media_id or hashlib.sha1(url.encode("utf-8", errors="ignore")).hexdigest()[:11]
    fmt_entry = {
        "format_id": "image",
        "url": url,
        "ext": ext,
        "vcodec": "images",
        "filesize": size,
        "width": width,
        "height": height,
    }
    if headers:
        fmt_entry["http_headers"] = dict(headers)
    info = {
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
        "formats": [fmt_entry],
    }
    if headers:
        info["http_headers"] = dict(headers)
    return info
