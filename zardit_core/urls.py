"""URL validation, platform detection, and local-network SSRF protection."""

from __future__ import annotations

import ipaddress
import re
import socket
from urllib.parse import parse_qs, urlparse

SUPPORTED_PLATFORMS = (
    ("YouTube", ("youtube.com", "youtu.be")),
    ("Instagram", ("instagram.com", "instagr.am")),
    ("Facebook", ("facebook.com", "fb.watch", "fb.com")),
    ("X / Twitter", ("twitter.com", "x.com", "t.co")),
    ("Threads", ("threads.net", "threads.com")),
)

_REDDIT_DOMAINS = ("reddit.com", "redd.it", "v.redd.it")

_URL_IN_TEXT = re.compile(r"https?://[^\s<>\"']+")
_CGNAT_NET = ipaddress.ip_network("100.64.0.0/10")
_BLOCKED_HOST_SUFFIXES = (".localhost", ".localdomain", ".local", ".internal", ".home", ".lan")

CROPPED_URL_RE = re.compile(
    r"(?:/[sp]\d+x\d+/|/c\d+\.\d+\.\d+\.\d+|dst-jpg_e\d+_[sp]|sqp=)",
    re.IGNORECASE,
)


def is_cropped_thumb_url(url):
    return bool(url and CROPPED_URL_RE.search(url))


def host_matches(hostname, domain):
    return hostname == domain or hostname.endswith(f".{domain}")


def is_unsupported_reddit_url(url):
    """True when URL points to Reddit (removed due to Reddit login/anti-bot blocking)."""

    try:
        hostname = (urlparse(str(url or "").strip()).hostname or "").lower()
    except ValueError:
        return False

    return any(host_matches(hostname, domain) for domain in _REDDIT_DOMAINS)


def detect_platform(url):
    """Return a readable platform name for a media URL."""

    try:
        hostname = (urlparse(url).hostname or "").lower()
    except ValueError:
        return "Web"

    for name, domains in SUPPORTED_PLATFORMS:
        if any(host_matches(hostname, d) for d in domains):
            return name

    return "Web"


def _is_public_ip_address(addr):
    """True when ipaddress object is a globally routable IP (not private/loopback/CGNAT)."""

    if not addr.is_global:
        return False
    if addr.version == 4 and addr in _CGNAT_NET:
        return False
    return True


def is_public_hostname(hostname):
    """True when hostname is a syntactically public host (not localhost or private IP)."""

    host = (hostname or "").strip().lower().rstrip(".")
    if (
        not host
        or host == "localhost"
        or any(host.endswith(suffix) for suffix in _BLOCKED_HOST_SUFFIXES)
    ):
        return False

    try:
        addr = ipaddress.ip_address(host)
    except ValueError:
        # Domain name: require at least one dot and no empty labels or leading/trailing dots.
        if "." not in host or host.startswith(".") or ".." in host:
            return False
        return True

    return _is_public_ip_address(addr)


def has_private_dns_resolution(hostname):
    """True when hostname resolves via DNS to any private, loopback, link-local, or CGNAT IP.

    Returns False on DNS lookup errors (e.g. offline environments or unresolvable test domains)
    so syntactic checks still work without network access.
    """

    host = (hostname or "").strip().lower().rstrip(".")
    if not host:
        return True

    try:
        addr = ipaddress.ip_address(host)
        return not _is_public_ip_address(addr)
    except ValueError:
        pass

    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        return False

    for info in infos:
        sockaddr = info[4] if len(info) >= 5 else ()
        if not sockaddr:
            continue
        ip_str = str(sockaddr[0]).split("%", 1)[0]
        try:
            if not _is_public_ip_address(ipaddress.ip_address(ip_str)):
                return True
        except ValueError:
            return True
    return False


def validate_url(url, check_dns=False):
    """Validate a public HTTP/HTTPS media URL (blocking credentials and local IPs)."""

    if not isinstance(url, str) or any(ch.isspace() for ch in url.strip()):
        return False

    try:
        parsed = urlparse(url.strip())
        if parsed.scheme not in ("http", "https"):
            return False
        if parsed.username is not None or parsed.password is not None:
            return False
        if not is_public_hostname(parsed.hostname):
            return False
        if check_dns and has_private_dns_resolution(parsed.hostname):
            return False
        return True
    except (ValueError, AttributeError):
        return False


def validate_youtube_url(url):
    """Validate supported YouTube URLs."""

    if not validate_url(url):
        return False

    try:
        hostname = (urlparse(url).hostname or "").lower()
        return (
            hostname == "youtu.be"
            or hostname == "youtube.com"
            or hostname.endswith(".youtube.com")
        )
    except (ValueError, AttributeError):
        return False


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


def list_id(url):
    """The playlist ID in a link's query string, or None."""

    try:
        values = parse_qs(urlparse(url).query).get("list")
    except ValueError:
        return None

    return values[0] if values else None


def is_mix_list(lid):
    """True for YouTube's personalised Mix lists (list=RD...)."""

    return bool(lid) and lid.startswith("RD") and not lid.startswith("RDCLAK")


def has_playlist_and_video(url):
    """True if a link points at a video that is inside a real playlist."""

    if not validate_youtube_url(url):
        return False

    lid = list_id(url)
    if not lid or is_mix_list(lid):
        return False

    parsed = urlparse(url)
    hostname = (parsed.hostname or "").lower()
    if hostname == "youtu.be":
        return bool(parsed.path.strip("/"))

    return (
        "v" in parse_qs(parsed.query)
        or parsed.path.startswith(("/shorts/", "/live/"))
    )
