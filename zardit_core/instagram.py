"""Meta (Instagram & Threads) DASH MPD and offline Instagram source extraction."""

from __future__ import annotations

import hashlib
import html
import json
import re
import time
from urllib.parse import urlparse
import xml.etree.ElementTree as ET

from zardit_core.errors import DownloadError
from zardit_core.formats import _make_image_info, _OG_TAG_RE, resolution_label
from zardit_core.naming import clean_ig_title
from zardit_core.urls import is_cropped_thumb_url

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


def _parse_meta_dash_manifest(xml_text, headers=None):
    """Extract DASH video and audio format dicts from Meta (Threads/IG) MPD XML."""

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []

    req_headers = dict(headers or _THREADS_MEDIA_HEADERS)
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
                "http_headers": dict(req_headers),
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
                "http_headers": dict(req_headers),
            })
    return formats


def _parse_threads_dash_manifest(xml_text):
    """Extract DASH video and audio format dicts from Threads MPD XML."""

    return _parse_meta_dash_manifest(xml_text, _THREADS_MEDIA_HEADERS)


def _ig_best_candidate_from_obj(candidates):
    """Return (url, width, height) for the largest uncropped Instagram image candidate."""

    if not isinstance(candidates, list):
        return (None, None, None)
    best = None
    best_score = (-1, -1)
    for item in candidates:
        if not isinstance(item, dict):
            continue
        u = item.get("url")
        if not isinstance(u, str) or not u.startswith("https://"):
            continue
        w = item.get("width") or 0
        h = item.get("height") or 0
        uncropped = 0 if is_cropped_thumb_url(u) else 1
        score = (uncropped, int(w) * int(h))
        if score >= best_score:
            best_score = score
            best = (u, int(w) if w else None, int(h) if h else None)
    return best or (None, None, None)


def _ig_formats_from_media_item(item, ig_headers):
    """Build video/audio yt-dlp-compatible format list from one IG media JSON object."""

    formats = []
    seen = set()

    dash_xml = item.get("video_dash_manifest")
    if isinstance(dash_xml, str) and "<Representation" in dash_xml:
        for f in _parse_meta_dash_manifest(dash_xml, ig_headers):
            if f["url"] not in seen:
                seen.add(f["url"])
                formats.append(f)

    for idx, v in enumerate(item.get("video_versions") or []):
        if not isinstance(v, dict):
            continue
        v_url = v.get("url")
        if (
            isinstance(v_url, str)
            and v_url.startswith("https://")
            and v_url not in seen
        ):
            seen.add(v_url)
            vw = v.get("width")
            vh = v.get("height")
            formats.append({
                "format_id": f"ig-prog-{idx}",
                "url": v_url,
                "ext": "mp4",
                "protocol": "https",
                "vcodec": "h264",
                "acodec": "aac",
                "width": vw,
                "height": vh,
                "format_note": f"{min(vw, vh)}p" if (vw and vh) else "720p",
                "http_headers": dict(ig_headers),
            })

    direct_video = item.get("video_url")
    if (
        not formats
        and isinstance(direct_video, str)
        and direct_video.startswith("https://")
        and direct_video not in seen
    ):
        dims = item.get("dimensions") or {}
        vw = dims.get("width") or item.get("original_width")
        vh = dims.get("height") or item.get("original_height")
        formats.append({
            "format_id": "ig-direct",
            "url": direct_video,
            "ext": "mp4",
            "protocol": "https",
            "vcodec": "h264",
            "acodec": "aac",
            "width": vw,
            "height": vh or 720,
            "format_note": f"{min(vw, vh)}p" if (vw and vh) else "720p",
            "http_headers": dict(ig_headers),
        })

    return formats


def _build_alt_ids(primary_id, *extra_ids):
    alts = []
    for cid in (primary_id, *extra_ids):
        if isinstance(cid, str) and cid:
            if cid not in alts and cid != primary_id:
                alts.append(cid)
            if len(cid) >= 30 and cid[:11] not in alts and cid[:11] != primary_id:
                alts.append(cid[:11])
    return alts


def parse_instagram_source(raw_text, source_url=None):
    """Extract downloadable video, image, or carousel locally from pasted HTML or JSON.

    Supports both ?__a=1&__d=dis JSON responses and browser view-source HTML pages
    for private Instagram posts the user already has access to in their browser.
    """

    text = (raw_text or "").strip()
    if not text:
        raise DownloadError("Paste the Instagram or Facebook page source or ?__a=1 JSON first.")
    if len(text) > 20 * 1024 * 1024:
        raise DownloadError("Pasted source text is too large (limit 20 MB).")

    src_low = (source_url or "").lower()
    is_fb_source = (
        "facebook.com" in src_low
        or "fb.watch" in src_low
        or "fb.com" in src_low
        or (
            ("fbcdn.net" in text or '"__isNode":"Photo"' in text or "browser_native_" in text)
            and "instagram.com" not in text
            and not text.lstrip().startswith("{")
        )
    )
    if is_fb_source:
        from zardit_core.facebook import parse_facebook_source

        return parse_facebook_source(text, source_url)

    ig_headers = {
        "User-Agent": "Mozilla/5.0",
        "Referer": "https://www.instagram.com/",
    }
    clean_url = (source_url or "").strip()
    if clean_url.startswith("view-source:"):
        clean_url = clean_url[len("view-source:"):].strip()
    try:
        parsed_clean = urlparse(clean_url) if clean_url else None
    except ValueError:
        parsed_clean = None
    if not parsed_clean or parsed_clean.scheme != "https" or not parsed_clean.hostname:
        clean_url = "https://www.instagram.com/"

    sc_match = re.search(
        r"instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", clean_url
    )
    shortcode = sc_match.group(1) if sc_match else None

    # 1. Try structured ?__a=1&__d=dis JSON directly.
    if text.lstrip().startswith("{"):
        try:
            root = json.loads(text)
        except ValueError:
            root = None

        if isinstance(root, dict):
            item = None
            items = root.get("items")
            if isinstance(items, list) and items and isinstance(items[0], dict):
                item = items[0]
            elif isinstance(root.get("graphql"), dict) and isinstance(
                root["graphql"].get("shortcode_media"), dict
            ):
                item = root["graphql"]["shortcode_media"]
            elif isinstance(root.get("data"), dict):
                data_obj = root["data"]
                if isinstance(data_obj.get("shortcode_media"), dict):
                    item = data_obj["shortcode_media"]
                elif isinstance(
                    data_obj.get("xdt_api__v1__media__shortcode__web_info"), dict
                ):
                    edges = (
                        data_obj["xdt_api__v1__media__shortcode__web_info"].get("items")
                        or []
                    )
                    if edges and isinstance(edges[0], dict):
                        item = edges[0]

            if item is not None:
                item_code = (item.get("code") or item.get("shortcode") or "").strip()
                media_id = (
                    item_code
                    or shortcode
                    or str(item.get("pk") or item.get("id") or "ig_private")
                ).split("_", 1)[0]
                alt_ids = _build_alt_ids(media_id, shortcode, item_code)
                caption_obj = item.get("caption")
                raw_title = (
                    caption_obj.get("text")
                    if isinstance(caption_obj, dict)
                    else None
                )
                if not raw_title:
                    cap_edges = (
                        item.get("edge_media_to_caption", {}).get("edges") or []
                    )
                    if cap_edges and isinstance(cap_edges[0], dict):
                        raw_title = (cap_edges[0].get("node") or {}).get("text")
                author = (
                    (item.get("user") or {}).get("full_name")
                    or (item.get("user") or {}).get("username")
                    or (item.get("owner") or {}).get("full_name")
                    or (item.get("owner") or {}).get("username")
                    or "Instagram"
                )
                username = (
                    (item.get("user") or {}).get("username")
                    or (item.get("owner") or {}).get("username")
                )
                title = clean_ig_title(raw_title, username=username or author, media_id=media_id)
                post_url = (
                    clean_url
                    if shortcode
                    else f"https://www.instagram.com/p/{media_id}/"
                )
                duration = item.get("video_duration")
                if duration is not None:
                    try:
                        duration = int(float(duration))
                    except (TypeError, ValueError):
                        duration = None
                upload_date = None
                taken_at = item.get("taken_at") or item.get("taken_at_timestamp")
                if isinstance(taken_at, (int, float)) and taken_at > 1_000_000_000:
                    upload_date = time.strftime("%Y%m%d", time.gmtime(int(taken_at)))

                # Carousel slides (carousel_media or GraphQL edge_sidecar_to_children)
                raw_slides = item.get("carousel_media") or [
                    e.get("node")
                    for e in (
                        item.get("edge_sidecar_to_children", {}).get("edges") or []
                    )
                    if isinstance(e, dict) and isinstance(e.get("node"), dict)
                ]
                if isinstance(raw_slides, list) and raw_slides:
                    entries = []
                    for idx, slide in enumerate(raw_slides, start=1):
                        if not isinstance(slide, dict):
                            continue
                        slide_id = f"{media_id}_{idx}"
                        img_u, img_w, img_h = _ig_best_candidate_from_obj(
                            (slide.get("image_versions2") or {}).get("candidates")
                        )
                        if not img_u and isinstance(slide.get("display_url"), str):
                            img_u = slide["display_url"]
                            dims = slide.get("dimensions") or {}
                            img_w = dims.get("width")
                            img_h = dims.get("height")

                        slide_fmts = _ig_formats_from_media_item(slide, ig_headers)
                        if slide_fmts:
                            entries.append({
                                "id": slide_id,
                                "_alt_ids": _build_alt_ids(slide_id),
                                "title": f"Slide {idx}",
                                "uploader": author,
                                "thumbnail": img_u,
                                "webpage_url": post_url,
                                "extractor": "instagram",
                                "extractor_key": "Instagram",
                                "ext": "mp4",
                                "formats": slide_fmts,
                            })
                        elif img_u and img_u.startswith("https://"):
                            dim = f" ({img_w}×{img_h})" if img_w and img_h else ""
                            entries.append({
                                "_zardit_media_kind": "image",
                                "id": slide_id,
                                "title": f"Photo {idx}{dim}",
                                "uploader": author,
                                "url": img_u,
                                "webpage_url": img_u,
                                "image_url": img_u,
                                "thumbnail": img_u,
                                "width": img_w,
                                "height": img_h,
                                "http_headers": dict(ig_headers),
                            })
                    if len(entries) == 1:
                        return entries[0]
                    if entries:
                        return {
                            "_type": "playlist",
                            "title": title,
                            "entries": entries,
                        }

                # Single video / reel
                thumb_u, thumb_w, thumb_h = _ig_best_candidate_from_obj(
                    (item.get("image_versions2") or {}).get("candidates")
                )
                if not thumb_u and isinstance(item.get("display_url"), str):
                    thumb_u = item["display_url"]
                    dims = item.get("dimensions") or {}
                    thumb_w = dims.get("width")
                    thumb_h = dims.get("height")

                fmts = _ig_formats_from_media_item(item, ig_headers)
                if fmts:
                    return {
                        "id": media_id,
                        "_alt_ids": alt_ids,
                        "title": title,
                        "uploader": author,
                        "duration": duration,
                        "upload_date": upload_date,
                        "thumbnail": thumb_u,
                        "webpage_url": post_url,
                        "original_url": post_url,
                        "extractor": "instagram",
                        "extractor_key": "Instagram",
                        "ext": "mp4",
                        "formats": fmts,
                    }

                if thumb_u and thumb_u.startswith("https://"):
                    return _make_image_info(
                        thumb_u,
                        title=title,
                        channel=author,
                        media_id=media_id,
                        width=thumb_w,
                        height=thumb_h,
                        headers=ig_headers,
                    )

    # 2. Parse pasted view-source HTML from browser.
    meta = {}
    for match in _OG_TAG_RE.finditer(text):
        key = (match.group(1) or match.group(4) or "").lower()
        val = html.unescape(match.group(2) or match.group(3) or "").strip()
        if key and val and key not in meta:
            meta[key] = val

    canon_match = re.search(
        r'<link[^>]+rel=["\']canonical["\'][^>]+href=["\'](https://[^"\']+)["\']',
        text,
        re.IGNORECASE,
    )
    resolved_url = (
        canon_match.group(1)
        if canon_match
        else meta.get("og:url") or clean_url
    )
    sc2 = re.search(
        r"instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", resolved_url
    )
    canon_sc = sc2.group(1) if sc2 else None
    code_match = re.search(r'"code"\s*:\s*"([A-Za-z0-9_-]{6,64})"', text)
    json_sc = code_match.group(1) if code_match else None

    media_id = (
        shortcode
        or canon_sc
        or json_sc
        or hashlib.sha1(text[:4096].encode("utf-8", errors="ignore")).hexdigest()[:11]
    )
    alt_ids = _build_alt_ids(media_id, canon_sc, json_sc)

    search_windows = []
    for focus_code in dict.fromkeys(c for c in (canon_sc, json_sc, media_id[:11]) if c):
        for m in re.finditer(rf'"code"\s*:\s*"{re.escape(focus_code)}"', text):
            search_windows.append(text[max(0, m.start() - 25000):min(len(text), m.end() + 25000)])
    search_windows.append(text)

    scoped = search_windows[0]
    username_m = re.search(r'"username"\s*:\s*"([A-Za-z0-9_.]{1,40})"', scoped)
    fullname_m = re.search(r'"full_name"\s*:\s*"((?:\\.|[^"\\])*)"', scoped)
    username = username_m.group(1) if username_m else None
    fullname = _decode_json_str(fullname_m.group(1)) if fullname_m else None
    author = (fullname or "").strip() or username
    if not author:
        og_t = meta.get("og:title") or ""
        author_m = re.match(r"^([^:(]+?)\s+(?:on Instagram|\(@)", og_t)
        author = author_m.group(1).strip() if author_m else "Instagram"

    dur_m = re.search(r'"video_duration"\s*:\s*([0-9]+(?:\.[0-9]+)?)', scoped)
    duration = int(float(dur_m.group(1))) if dur_m else None
    taken_m = re.search(r'"taken_at"\s*:\s*(\d{10})', scoped)
    upload_date = (
        time.strftime("%Y%m%d", time.gmtime(int(taken_m.group(1))))
        if taken_m
        else None
    )

    cap_m = re.search(r'"caption"\s*:\s*\{\s*"text"\s*:\s*"((?:\\.|[^"\\])*)"', scoped)
    raw_caption = _decode_json_str(cap_m.group(1)) if cap_m else None
    raw_title = (
        raw_caption
        or meta.get("og:description")
        or meta.get("og:title")
        or meta.get("twitter:title")
        or ""
    )
    title = clean_ig_title(raw_title, username=username or author, media_id=media_id)

    formats = []
    seen_urls = set()

    for win in search_windows:
        for dm in _THREADS_DASH_RE.finditer(win):
            xml_str = _decode_json_str(dm.group(1))
            for f in _parse_meta_dash_manifest(xml_str, ig_headers):
                if f["url"] not in seen_urls:
                    seen_urls.add(f["url"])
                    formats.append(f)
            if formats:
                break
        if formats:
            break

    for win in search_windows:
        for vm in _THREADS_VIDEO_VERSIONS_RE.finditer(win):
            try:
                versions = json.loads(vm.group(1))
            except Exception:
                try:
                    versions = json.loads(_decode_json_str(vm.group(1).strip('"')))
                except Exception:
                    versions = None
            if isinstance(versions, list):
                for idx, v in enumerate(versions):
                    if not isinstance(v, dict):
                        continue
                    v_url = v.get("url")
                    if (
                        isinstance(v_url, str)
                        and v_url.startswith("https://")
                        and "rsrc.php" not in v_url.lower()
                        and v_url not in seen_urls
                    ):
                        seen_urls.add(v_url)
                        vw = v.get("width")
                        vh = v.get("height")
                        formats.append({
                            "format_id": f"ig-html-prog-{idx}",
                            "url": v_url,
                            "ext": "mp4",
                            "protocol": "https",
                            "vcodec": "h264",
                            "acodec": "aac",
                            "width": vw,
                            "height": vh,
                            "format_note": f"{min(vw, vh)}p" if (vw and vh) else "720p",
                            "http_headers": dict(ig_headers),
                        })
                if any(f.get("acodec") != "none" for f in formats):
                    break
        if any(f.get("acodec") != "none" for f in formats):
            break

    if not any(f.get("vcodec") not in (None, "none") for f in formats):
        og_vid = meta.get("og:video:secure_url") or meta.get("og:video")
        cands = [og_vid] if og_vid else []
        for vm in _THREADS_VIDEO_URL_RE.finditer(scoped):
            cands.append(_decode_json_str(vm.group(1)))
        for cand in cands:
            if (
                isinstance(cand, str)
                and cand.startswith("https://")
                and "rsrc.php" not in cand.lower()
                and cand not in seen_urls
            ):
                seen_urls.add(cand)
                formats.append({
                    "format_id": "ig-html-direct",
                    "url": cand,
                    "ext": "mp4",
                    "protocol": "https",
                    "vcodec": "h264",
                    "acodec": "aac",
                    "height": 720,
                    "format_note": "720p",
                    "http_headers": dict(ig_headers),
                })
                break

    img_urls = []
    seen_imgs = set()
    for dm in re.finditer(r'"display_url"\s*:\s*"((?:\\.|[^"\\])*)"', scoped):
        u = _decode_json_str(dm.group(1))
        if (
            u.startswith("https://")
            and not is_cropped_thumb_url(u)
            and "rsrc.php" not in u.lower()
            and u not in seen_imgs
        ):
            seen_imgs.add(u)
            img_urls.append(u)

    og_img = (
        meta.get("og:image:secure_url")
        or meta.get("og:image")
        or meta.get("twitter:image")
    )
    if (
        isinstance(og_img, str)
        and og_img.startswith("https://")
        and not is_cropped_thumb_url(og_img)
        and og_img not in seen_imgs
    ):
        if not img_urls:
            img_urls.append(og_img)

    has_video = any(f.get("vcodec") not in (None, "none") for f in formats)
    if has_video:
        if not any(
            resolution_label(f) for f in formats if f.get("vcodec") not in (None, "none")
        ):
            for f in formats:
                if f.get("vcodec") not in (None, "none"):
                    f["height"] = 720
                    f["format_note"] = "720p"
                    break
        return {
            "id": media_id,
            "_alt_ids": alt_ids,
            "title": title,
            "uploader": author,
            "duration": duration,
            "upload_date": upload_date,
            "thumbnail": og_img or (img_urls[0] if img_urls else None),
            "webpage_url": resolved_url,
            "original_url": resolved_url,
            "extractor": "instagram",
            "extractor_key": "Instagram",
            "ext": "mp4",
            "formats": formats,
        }

    if len(img_urls) > 1:
        entries = []
        for idx, u in enumerate(img_urls, start=1):
            entries.append({
                "_zardit_media_kind": "image",
                "id": f"{media_id}_{idx}",
                "title": f"Photo {idx}",
                "uploader": author,
                "url": u,
                "webpage_url": u,
                "image_url": u,
                "thumbnail": u,
                "http_headers": dict(ig_headers),
            })
        return {"_type": "playlist", "title": title, "entries": entries}

    if len(img_urls) == 1:
        return _make_image_info(
            img_urls[0],
            title=title,
            channel=author,
            media_id=media_id,
            headers=ig_headers,
        )

    raise DownloadError(
        "No Instagram video or image was found in the pasted source. "
        "Make sure you are signed in and viewing the post in your browser before copying."
    )
