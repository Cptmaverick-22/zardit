"""Facebook video, reel, photo, and multi-photo HTML/JSON extraction (public & private)."""

from __future__ import annotations

import hashlib
import html
import http.cookiejar
import json
import re
import urllib.request
from urllib.parse import parse_qs, urlparse

from zardit_core.errors import DownloadError
from zardit_core.formats import _OG_TAG_RE, _make_image_info
from zardit_core.instagram import _decode_json_str, _parse_meta_dash_manifest
from zardit_core.urls import detect_platform, is_cropped_thumb_url, validate_url

FB_MAX_HTML_BYTES = 4 * 1024 * 1024

_FB_HEADER_PROFILES = (
    {
        "User-Agent": "curl/8.5.0",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    },
    {
        "User-Agent": "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
        "Accept": "text/html,*/*",
    },
    {
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Sec-Fetch-Mode": "navigate",
    },
)

FB_MEDIA_HEADERS = {
    "User-Agent": "Mozilla/5.0",
    "Referer": "https://www.facebook.com/",
}

_FB_VIEWER_IMAGE_RE = re.compile(
    r'"viewer_image"\s*:\s*\{[^}]*?"height"\s*:\s*(\d+)[^}]*?"width"\s*:\s*(\d+)[^}]*?\}',
    re.IGNORECASE,
)
_FB_PHOTO_IMAGE_RE = re.compile(
    r'"(?:viewer_image|photo_image)"\s*:\s*\{([^}]{1,900}?)\}',
    re.IGNORECASE,
)
_FB_ANY_IMAGE_OBJ_RE = re.compile(
    r'"image"\s*:\s*\{([^}]{1,900}?)\}',
    re.IGNORECASE,
)
_FB_URI_FIELD_RE = re.compile(
    r'"uri"\s*:\s*"(https?:\\/\\/[^"]+|https?://[^"]+)"',
    re.IGNORECASE,
)
_FB_DIM_W_RE = re.compile(r'"width"\s*:\s*(\d+)', re.IGNORECASE)
_FB_DIM_H_RE = re.compile(r'"height"\s*:\s*(\d+)', re.IGNORECASE)
_FB_PRELOAD_IMG_RE = re.compile(
    r'<link[^>]+rel=["\']preload["\'][^>]+>',
    re.IGNORECASE,
)
_FB_HREF_RE = re.compile(r'href=["\'](https?://[^"\']+)["\']', re.IGNORECASE)
_FB_DASH_RE = re.compile(r'"video_dash_manifest"\s*:\s*"((?:\\.|[^"\\])*)"')
_FB_VIDEO_KEYS = (
    ("browser_native_hd_url", 720, "HD"),
    ("playable_url_quality_hd", 720, "HD"),
    ("browser_native_sd_url", 480, "SD"),
    ("playable_url", 480, "SD"),
)


def _unescape_fb_url(raw: str) -> str:
    txt = str(raw or "").strip()
    if "\\/" in txt or "\\u" in txt:
        txt = _decode_json_str(txt)
    return html.unescape(txt).strip()


def _is_fb_noise_image(url: str, pre_context: str = "") -> bool:
    low = url.lower()
    if not low.startswith("https://") or "fbcdn.net" not in low:
        return True
    if any(
        tok in low
        for tok in (
            "/t39.30808-1/",
            "/t1.30497-1/",
            "/t39.1997-6/",
            "/m1/v/t6/",
            "rsrc.php",
            "emoji.php",
            ".kf",
            "_fb30_",
            "fb30_",
        )
    ):
        return True
    if is_cropped_thumb_url(url):
        return True
    immediate_key = pre_context.rsplit("}", 1)[-1].rsplit(",", 1)[-1].lower()
    if any(
        ctx in immediate_key
        for ctx in (
            "blurred_image",
            "profile_picture",
            "neutralsilhouette",
            "silhouette",
            "actor_photo",
            "favicon",
        )
    ):
        return True
    return False


def _extract_fb_cookie_header(browser: str | None) -> str | None:
    if not browser:
        return None
    try:
        from yt_dlp.cookies import extract_cookies_from_browser

        jar = extract_cookies_from_browser(browser)
        pairs = []
        for c in jar:
            dom = (c.domain or "").lower()
            if "facebook.com" in dom and c.name and c.value:
                pairs.append(f"{c.name}={c.value}")
        return "; ".join(pairs) if pairs else None
    except Exception:
        return None


def fetch_facebook_media(url: str, browser: str | None = None, urlopen=None) -> dict | None:
    """Fetch & parse Facebook HTML across profiles until media is extracted cleanly."""

    if detect_platform(url) != "Facebook" or not validate_url(url):
        return None

    if urlopen is not None:
        open_fn = urlopen
    else:
        cj = http.cookiejar.CookieJar()
        open_fn = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)).open

    cookie_header = _extract_fb_cookie_header(browser)
    profiles = []
    if cookie_header:
        fb_hdr = dict(_FB_HEADER_PROFILES[2])
        fb_hdr["Cookie"] = cookie_header
        profiles.append(fb_hdr)
    # Try desktop Firefox first (with CookieJar it gets full Comet SSR Relay media on /share/),
    # then curl and facebookexternalhit.
    profiles.extend((_FB_HEADER_PROFILES[2], _FB_HEADER_PROFILES[0], _FB_HEADER_PROFILES[1]))

    for hdrs in profiles:
        try:
            req = urllib.request.Request(url, headers=dict(hdrs))
            with open_fn(req, timeout=10) as resp:
                final_url = resp.geturl()
                if urlparse(final_url).scheme != "https" or not validate_url(final_url):
                    continue
                ctype = (resp.headers.get("Content-Type") or "").lower()
                if "html" not in ctype:
                    continue
                body = resp.read(FB_MAX_HTML_BYTES).decode("utf-8", errors="replace")
                if not body:
                    continue
                parsed = parse_facebook_html(body, source_url=final_url or url)
                if parsed is not None:
                    return parsed
        except Exception:
            continue

    return None


def fetch_facebook_html(url: str, browser: str | None = None, urlopen=None) -> tuple[str, str] | tuple[None, None]:
    """Download Facebook HTML safely with browser/crawler profiles and optional local cookies."""

    if detect_platform(url) != "Facebook" or not validate_url(url):
        return (None, None)

    open_fn = urlopen or urllib.request.urlopen
    cookie_header = _extract_fb_cookie_header(browser)
    profiles = []
    if cookie_header:
        fb_hdr = dict(_FB_HEADER_PROFILES[2])
        fb_hdr["Cookie"] = cookie_header
        profiles.append(fb_hdr)
    profiles.extend((_FB_HEADER_PROFILES[2], _FB_HEADER_PROFILES[0], _FB_HEADER_PROFILES[1]))

    for hdrs in profiles:
        try:
            req = urllib.request.Request(url, headers=dict(hdrs))
            with open_fn(req, timeout=10) as resp:
                final_url = resp.geturl()
                if urlparse(final_url).scheme != "https" or not validate_url(final_url):
                    continue
                ctype = (resp.headers.get("Content-Type") or "").lower()
                if "html" not in ctype:
                    continue
                body = resp.read(FB_MAX_HTML_BYTES).decode("utf-8", errors="replace")
                if body:
                    return (body, final_url)
        except Exception:
            continue

    return (None, None)


def _extract_media_id(url: str) -> str:
    try:
        qs = parse_qs(urlparse(url).query)
        fbid = (qs.get("fbid") or qs.get("v") or [None])[0]
        if fbid and fbid.isdigit():
            return fbid
    except ValueError:
        pass
    m = re.search(r"/(?:videos|reel|posts|photo|share/[vp])/([A-Za-z0-9._-]+)", url)
    if m:
        return m.group(1)
    return hashlib.sha1(url.encode("utf-8", errors="ignore")).hexdigest()[:11]


def parse_facebook_html(raw_html: str, source_url: str = "https://www.facebook.com/") -> dict | None:
    """Parse video/reel, single photo, or multi-photo carousel from Facebook HTML."""

    body = raw_html or ""
    if not body.strip():
        return None

    meta: dict[str, str] = {}
    for match in _OG_TAG_RE.finditer(body):
        key = (match.group(1) or match.group(4) or "").lower()
        val = html.unescape(match.group(2) or match.group(3) or "").strip()
        if key and val and key not in meta:
            meta[key] = val

    canon = meta.get("og:url") or source_url
    media_id = _extract_media_id(canon if validate_url(canon) else source_url)
    title = meta.get("og:description") or meta.get("og:title") or meta.get("twitter:title") or ""
    if not title:
        t_match = re.search(r"<title[^>]*>(.*?)</title>", body, re.IGNORECASE | re.DOTALL)
        if t_match:
            title = html.unescape(re.sub(r"\s+", " ", t_match.group(1))).strip()
    if title.lower() in ("facebook", "log in or sign up to view", "log into facebook"):
        title = ""
    title = title or f"Facebook Post ({media_id})"
    author = meta.get("og:title") or "Facebook"
    if author.lower() in ("facebook", "watch", "reels"):
        author = "Facebook"

    # 1. Check for Video / Reel streams first unless URL is an explicit photo/post photo target
    src_low = f"{source_url} {canon}".lower()
    is_explicit_video_url = any(tok in src_low for tok in ("/reel/", "/videos/", "/share/v/", "fb.watch"))
    is_explicit_photo_url = "/photo" in src_low or "fbid=" in src_low or "/share/p/" in src_low

    formats: list[dict] = []
    seen_vid_urls: set[str] = set()
    first_photo_hit = _FB_PHOTO_IMAGE_RE.search(body)
    first_vid_hit = (
        _FB_DASH_RE.search(body)
        or re.search(r'"(?:browser_native_hd_url|playable_url_quality_hd|browser_native_sd_url|playable_url)"\s*:', body)
    )
    prefer_photos_over_sidebar_video = bool(
        is_explicit_photo_url
        or (
            not is_explicit_video_url
            and first_photo_hit is not None
            and (first_vid_hit is None or first_photo_hit.start() < first_vid_hit.start())
        )
    )

    if not prefer_photos_over_sidebar_video:
        for dm in _FB_DASH_RE.finditer(body):
            xml_text = _decode_json_str(dm.group(1))
            for fmt in _parse_meta_dash_manifest(xml_text, FB_MEDIA_HEADERS):
                if fmt["url"] not in seen_vid_urls:
                    seen_vid_urls.add(fmt["url"])
                    formats.append(fmt)

        for key, tier, badge in _FB_VIDEO_KEYS:
            pat = re.compile(rf'"{key}"\s*:\s*"((?:\\.|[^"\\])*)"')
            for vm in pat.finditer(body):
                v_url = _unescape_fb_url(vm.group(1))
                if (
                    v_url.startswith("https://")
                    and validate_url(v_url)
                    and v_url not in seen_vid_urls
                ):
                    seen_vid_urls.add(v_url)
                    formats.append({
                        "format_id": f"fb-{key}-{len(formats)}",
                        "url": v_url,
                        "ext": "mp4",
                        "protocol": "https",
                        "vcodec": "h264",
                        "acodec": "aac",
                        "height": tier,
                        "format_note": f"{tier}p",
                        "http_headers": dict(FB_MEDIA_HEADERS),
                    })

        if not formats:
            og_vid = meta.get("og:video:secure_url") or meta.get("og:video")
            if og_vid and og_vid.startswith("https://") and validate_url(og_vid):
                formats.append({
                    "format_id": "fb-og",
                    "url": og_vid,
                    "ext": "mp4",
                    "protocol": "https",
                    "vcodec": "h264",
                    "acodec": "aac",
                    "height": 720,
                    "format_note": "720p",
                    "http_headers": dict(FB_MEDIA_HEADERS),
                })

    thumb_url = meta.get("og:image:secure_url") or meta.get("og:image") or meta.get("twitter:image")
    if thumb_url and _is_fb_noise_image(thumb_url):
        thumb_url = None

    has_video = any(f.get("vcodec") not in (None, "none") for f in formats)
    if formats and has_video:
        return {
            "id": media_id,
            "title": title,
            "uploader": author,
            "thumbnail": thumb_url,
            "webpage_url": canon,
            "original_url": source_url,
            "extractor": "facebook",
            "extractor_key": "Facebook",
            "ext": "mp4",
            "formats": formats,
            "_zardit_alt_ids": [media_id],
        }

    # 2. Extract post / album photos
    seen_keys: set[str] = set()
    entries: list[dict] = []

    def _add_photo(raw_uri: str, w: int | None = None, h: int | None = None, pre_ctx: str = ""):
        img_url = _unescape_fb_url(raw_uri)
        if _is_fb_noise_image(img_url, pre_ctx) or not validate_url(img_url):
            return
        if (w and w < 220) or (h and h < 220):
            return
        dedup_key = urlparse(img_url).path
        if dedup_key in seen_keys:
            return
        seen_keys.add(dedup_key)
        idx = len(entries) + 1
        dim = f" ({w}×{h})" if w and h else ""
        entries.append({
            "_zardit_media_kind": "image",
            "id": hashlib.sha1(dedup_key.encode("utf-8", errors="ignore")).hexdigest()[:11],
            "title": f"Photo {idx}{dim}",
            "uploader": author,
            "url": img_url,
            "webpage_url": img_url,
            "image_url": img_url,
            "thumbnail": img_url,
            "width": w,
            "height": h,
            "http_headers": dict(FB_MEDIA_HEADERS),
        })

    # Pass A: `"photo_image"` blocks (posts & multi-photo posts), using preceding `"viewer_image"` dims
    for match in _FB_PHOTO_IMAGE_RE.finditer(body):
        inner = match.group(1)
        uri_m = _FB_URI_FIELD_RE.search(inner)
        if not uri_m:
            continue
        pre_ctx = body[max(0, match.start() - 220):match.start()]
        w_m = _FB_DIM_W_RE.search(inner)
        h_m = _FB_DIM_H_RE.search(inner)
        w = int(w_m.group(1)) if w_m else None
        h = int(h_m.group(1)) if h_m else None
        if not (w and h):
            vw = _FB_VIEWER_IMAGE_RE.search(pre_ctx)
            if vw:
                h, w = int(vw.group(1)), int(vw.group(2))
        _add_photo(uri_m.group(1), w=w, h=h, pre_ctx=pre_ctx)

    # Pass B: `"image":{"uri":...}` blocks (e.g. /photo/?fbid=... pages)
    if not entries:
        for match in _FB_ANY_IMAGE_OBJ_RE.finditer(body):
            inner = match.group(1)
            uri_m = _FB_URI_FIELD_RE.search(inner)
            if not uri_m:
                continue
            pre_ctx = body[max(0, match.start() - 120):match.start()]
            w_m = _FB_DIM_W_RE.search(inner)
            h_m = _FB_DIM_H_RE.search(inner)
            _add_photo(
                uri_m.group(1),
                w=int(w_m.group(1)) if w_m else None,
                h=int(h_m.group(1)) if h_m else None,
                pre_ctx=pre_ctx,
            )

    # Pass C: High-res <link rel="preload" as="image"> tags or og:image
    if not entries:
        for tag_m in _FB_PRELOAD_IMG_RE.finditer(body):
            tag = tag_m.group(0)
            if 'as="image"' not in tag and "as='image'" not in tag:
                continue
            href_m = _FB_HREF_RE.search(tag)
            if href_m and ("/t51." in href_m.group(1) or "/t39.99422-6/" in href_m.group(1) or "/t39.30808-6/" in href_m.group(1)):
                _add_photo(href_m.group(1))

    if not entries and thumb_url:
        _add_photo(thumb_url)

    if len(entries) == 1:
        e = entries[0]
        return _make_image_info(
            e["image_url"],
            title=title,
            channel=author,
            media_id=media_id,
            width=e.get("width"),
            height=e.get("height"),
            headers=FB_MEDIA_HEADERS,
        )
    if entries:
        return {
            "_type": "playlist",
            "title": title,
            "entries": entries,
        }
    return None


def parse_facebook_source(raw_text: str, source_url: str | None = None) -> dict:
    """Offline Facebook HTML parser for Option 2 (Paste Page Source)."""

    text = (raw_text or "").strip()
    if not text:
        raise DownloadError("Paste the Facebook page source HTML first.")
    clean_url = (source_url or "").strip()
    if clean_url.startswith("view-source:"):
        clean_url = clean_url[len("view-source:"):].strip()
    if not validate_url(clean_url):
        clean_url = "https://www.facebook.com/"

    if text.lstrip().startswith("{"):
        try:
            json.loads(text)
        except ValueError:
            pass

    parsed = parse_facebook_html(text, clean_url)
    if parsed is None:
        raise DownloadError(
            "No downloadable video or image was found in the pasted Facebook source. "
            "Make sure you copied the full page source (Ctrl+U -> Ctrl+A -> Ctrl+C) while signed in."
        )
    return parsed
