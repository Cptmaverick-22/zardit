"""Error types and user-facing error translation."""

from __future__ import annotations

import errno
import re

from yt_dlp.utils import DownloadCancelled as _YtdlpCancelled

from config import get_ig_browser


class DownloadError(Exception):
    """A problem described by a message that is safe to show to the user."""


class PlaylistLinkError(DownloadError):
    """A playlist link was given where a single video was expected."""


class DownloadCancelled(_YtdlpCancelled):
    """Raised inside a progress hook to stop yt-dlp."""


ANSI_PATTERN = re.compile(r"\x1b\[[0-9;]*m")

DISK_FULL = "The disk is full. Free some space or choose another download folder."
NO_PERMISSION = "No permission to write to the download folder. Choose another folder."
NAME_TOO_LONG = (
    "The file name or folder path is too long. "
    "Use a shorter folder name or location."
)

_ERRNO_MESSAGES = {
    errno.ENOSPC: DISK_FULL,
    errno.EACCES: NO_PERMISSION,
    errno.EPERM: NO_PERMISSION,
    errno.EROFS: NO_PERMISSION,
    errno.ENAMETOOLONG: NAME_TOO_LONG,
}

_ERROR_RULES = [
    (re.compile(p, re.IGNORECASE), text) for p, text in (
        (r"confirm your age|age[- ]restricted|age verification",
         "This video is age-restricted and requires a signed-in account. "
         "For Instagram, click IG in the top bar (or choose [G] in the CLI)."),
        (r"not a bot",
         "The site is asking to confirm you're not a bot. Wait a while, "
         "update yt-dlp, or try another network."),
        (r"members[- ]only|join this channel",
         "This video is only available to channel members."),
        (r"private video|private post",
         "This video or post is private."),
        (r"sign in|log in|login required|login_required|cookies|empty media response",
         "This post or video requires a signed-in account."),
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


def friendly_error(error, ig_browser_lookup=None):
    """Turn an exception into a short, readable message."""

    if isinstance(error, OSError) and error.errno in _ERRNO_MESSAGES:
        return _ERRNO_MESSAGES[error.errno]

    message = ANSI_PATTERN.sub("", str(error)).strip()
    message = re.sub(r"^ERROR:\s*", "", message)

    low_msg = message.lower()
    if any(
        tok in low_msg
        for tok in (
            "could not find cookies",
            "failed to decrypt",
            "app-bound",
            "dpapi",
            "keyring",
            "cookie database",
        )
    ):
        return (
            "Could not read Instagram cookies from your browser (the browser may "
            "encrypt cookies on this OS or is not installed). Try Firefox, or use "
            "IG -> Paste Page Source (offline)."
        )

    is_fb_auth = (
        ("facebook" in low_msg or "pfbid" in low_msg)
        and any(
            tok in low_msg
            for tok in (
                "login",
                "log in",
                "sign in",
                "registered users",
                "cookies",
                "private",
                "no video found",
                "http error 404",
                "http error 400",
                "cannot parse",
            )
        )
    )
    if is_fb_auth:
        lookup = ig_browser_lookup or get_ig_browser
        if lookup():
            return (
                "Facebook refused access or could not extract this private post with "
                "your browser cookies. Try IG / FB -> Paste Page Source (offline)."
            )
        return (
            "This Facebook post or reel may be private or restricted. Click IG / FB in the "
            "top bar (or choose [G] in the CLI) to connect a browser or paste the "
            "Page Source offline."
        )

    is_ig_auth = (
        "facebook" not in low_msg
        and any(
            tok in low_msg
            for tok in (
                "instagram",
                "registered users",
                "empty media response",
            )
        )
        and any(
            tok in low_msg
            for tok in (
                "login",
                "sign in",
                "log in",
                "registered users",
                "empty media response",
                "not granting access",
                "cookies",
                "private",
            )
        )
    )
    if is_ig_auth:
        lookup = ig_browser_lookup or get_ig_browser
        if lookup():
            return (
                "Instagram refused access with the connected browser cookies. "
                "Make sure you're logged into an account that follows this profile, "
                "or click IG / FB -> Paste Page Source (offline)."
            )
        return (
            "This Instagram post is private or requires sign-in. Click IG / FB in the "
            "top bar (or choose [G] in the CLI) to connect a browser or paste the "
            "Page Source offline."
        )

    for pattern, text in _ERROR_RULES:
        if pattern.search(message):
            return text

    return f"Download failed: {message[:300]}"
