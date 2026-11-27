package io.github.cptmaverick_22.zardit.downloader

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

data class ParsedDashManifest(
    val videoOptions: List<VideoOption>,
    val audioUrl: String?,
    val audioBytes: Long?,
)

object ZarditParser {

    private val noteResRegex = Regex("""^(\d{3,4})p""", RegexOption.IGNORE_CASE)
    private val dashRepRegex = Regex("""<Representation\b([^>]*)>(.*?)</Representation>""", RegexOption.DOT_MATCHES_ALL)
    private val dashAttrRegex = Regex("""([A-Za-z0-9_:-]+)="([^"]*)"""")
    private val dashBaseUrlRegex = Regex("""<BaseURL[^>]*>(.*?)</BaseURL>""", RegexOption.DOT_MATCHES_ALL)
    private val blockedHostSuffixes = listOf(".localhost", ".localdomain", ".local", ".internal", ".home", ".lan")
    private val numericIpOrV6Regex = Regex("""^(?:\d{1,3}(?:\.\d{1,3}){3}|.*:.*)$""")
    private val allowedIgCookies = setOf("sessionid", "csrftoken", "ds_user_id")

    fun isSafePublicUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!uri.rawUserInfo.isNullOrEmpty()) return false

        val rawHost = uri.host ?: return false
        val host = rawHost.trim('[', ']').lowercase(Locale.US).trimEnd('.')
        if (host.isEmpty() || host == "localhost" || blockedHostSuffixes.any { host.endsWith(it) }) {
            return false
        }

        if (numericIpOrV6Regex.matches(host)) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (isNonPublicAddress(addr)) return false
        } else {
            if ('.' !in host || host.startsWith('.') || ".." in host) return false
        }
        return true
    }

    private fun isNonPublicAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }
        if (addr is Inet4Address) {
            val b = addr.address
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            // 0.0.0.0/8 ("this network") & 100.64.0.0/10 (CGNAT)
            if (first == 0) return true
            if (first == 100 && second in 64..127) return true
            if (first == 198 && (second == 18 || second == 19)) return true
        } else if (addr is Inet6Address) {
            val b0 = addr.address[0].toInt() and 0xFF
            // fc00::/7 Unique Local IPv6
            if ((b0 and 0xFE) == 0xFC) return true
        }
        return false
    }

    fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("/playlist?") ||
            (lower.contains("list=") && !lower.contains("watch?v=") && !lower.contains("youtu.be/")) ||
            lower.contains("/sets/")
    }

    fun detectPlatformName(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            "instagram.com" in lower || "instagr.am" in lower -> "Instagram"
            "youtube.com" in lower || "youtu.be" in lower -> "YouTube"
            "facebook.com" in lower || "fb.watch" in lower || "fb.com" in lower -> "Facebook"
            "reddit.com" in lower || "redd.it" in lower || "v.redd.it" in lower -> "Reddit"
            "twitter.com" in lower || "x.com" in lower -> "X"
            "threads.net" in lower || "threads.com" in lower -> "Threads"
            else -> "Web"
        }
    }

    fun normalizeThreadsUrl(url: String): String {
        var current = url
        val lower = current.lowercase(Locale.US)
        if ("threads.com" in lower || "threads.net" in lower) {
            current = current.replace("threads.com", "threads.net", ignoreCase = true)
            val qIdx = current.indexOf('?')
            if (qIdx > 0 && "/post/" in current) {
                current = current.substring(0, qIdx)
            }
        }
        return current
    }

    fun decodeBasicHtml(raw: String): String {
        if (!raw.contains("&")) return raw
        return raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")
            .trim()
    }

    fun String.unescapeJsonUrl(): String =
        replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

    fun cleanInstagramTitle(rawTitle: String, username: String? = null, mediaId: String = "ig"): String {
        var text = Regex("""\s+""").replace(decodeBasicHtml(rawTitle), " ").trim()
        Regex("""^(?:\d[\d,.]*\s+likes?(?:,\s*\d[\d,.]*\s+comments?)?\s*-\s*[^:]+:\s*)(.+)$""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.let { text = it.trim() }
        Regex("""^[^:\n]{1,60}\s+on\s+Instagram:\s*(.+)$""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.let { text = it.trim() }
        text = text.trim('"', '\'', '\u201C', '\u201D', ' ', '.')

        val noTags = Regex("""(?:\s+#[A-Za-z0-9_]+){2,}\.?$""").replace(text, "").trim(' ', '.')
        if (noTags.isNotEmpty()) {
            text = noTags
        } else if (text.startsWith("#")) {
            text = ""
        }
        if (text.length > 90) {
            text = text.take(89).trimEnd(' ', ',', ';', ':', '-') + "…"
        }
        return when {
            text.isNotEmpty() -> text
            !username.isNullOrBlank() && username != "Instagram" -> "Video by $username"
            else -> "Instagram Post ($mediaId)"
        }
    }

    fun sanitizeTitle(raw: String, fallback: String = "Zardit"): String {
        val decoded = decodeBasicHtml(raw)
        val noHashtags = decoded.replace(Regex("""#\S+"""), " ")
        val safe = noHashtags
            .replace(":", " -")
            .replace(Regex("""[\\/|]"""), "-")
            .replace(Regex("""[*?"<>]"""), "")
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '-')
        return safe.take(65).trim().ifBlank { fallback }
    }

    fun qualityBadge(height: Int): String = when {
        height >= 2160 -> "4K UHD"
        height >= 1440 -> "2K QHD"
        height >= 1080 -> "Full HD"
        height >= 720 -> "HD"
        height >= 480 -> "SD"
        else -> ""
    }

    fun formatFileSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "~%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "~%.1f MB", mb)
        }
    }

    fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun classifyVideoTier(width: Int, height: Int, note: String, buckets: List<Int>): Int? {
        noteResRegex.find(note.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { n ->
            buckets.firstOrNull { n in (it - 20)..(it + 20) }?.let { return it }
        }
        val shortSide = if (width > 0 && height > 0) minOf(width, height) else height
        buckets.firstOrNull { shortSide in (it - 20)..(it + 20) }?.let { return it }

        val longSide = maxOf(width, height)
        return when {
            longSide >= 3700 -> 2160
            longSide >= 2450 -> 1440
            longSide >= 1850 -> 1080
            longSide >= 1200 -> 720
            longSide >= 800 -> 480
            longSide >= 600 -> 360
            longSide >= 400 -> 240
            longSide >= 220 -> 144
            else -> null
        }
    }

    fun effectiveVideoHeight(width: Int, height: Int): Int {
        if (width > 0 && height > 0) return minOf(width, height)
        return height
    }

    fun parseRangeSelection(input: String, total: Int): Set<Int> {
        if (total <= 0) return emptySet()
        if (input.isBlank()) return (1..total).toSet()
        val result = linkedSetOf<Int>()
        input.split(",").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-", limit = 2)
                val start = bounds[0].trim().toIntOrNull()
                val end = bounds[1].trim().toIntOrNull()
                if (start != null && end != null) {
                    val lo = start.coerceIn(1, total)
                    val hi = end.coerceIn(1, total)
                    for (i in minOf(lo, hi)..maxOf(lo, hi)) result.add(i)
                }
            } else {
                trimmed.toIntOrNull()?.takeIf { it in 1..total }?.let { result.add(it) }
            }
        }
        return result
    }

    fun parseDashManifest(rawXml: String): ParsedDashManifest? {
        if (!rawXml.contains("<Representation")) return null
        val buckets = listOf(1440, 1080, 720, 480, 360)

        var audioUrl: String? = null
        var audioBytes: Long? = null
        var bestAudioBw = -1L

        val bestVideoByBucket = mutableMapOf<Int, Pair<Int, VideoOption>>()

        dashRepRegex.findAll(rawXml).forEach { match ->
            val attrs = mutableMapOf<String, String>()
            dashAttrRegex.findAll(match.groupValues[1]).forEach { a ->
                attrs[a.groupValues[1]] = a.groupValues[2]
            }
            val mime = attrs["mimeType"]?.lowercase(Locale.US).orEmpty()
            val codecs = attrs["codecs"].orEmpty()
            val baseUrl = dashBaseUrlRegex.find(match.groupValues[2])?.groupValues?.getOrNull(1)
                ?.trim()?.replace("&amp;", "&")
                ?.takeIf { isSafePublicUrl(it) }
                ?: return@forEach
            val bw = attrs["bandwidth"]?.toLongOrNull() ?: 0L
            val contentLen = attrs["FBContentLength"]?.toLongOrNull()?.takeIf { it > 0L }

            if (mime.startsWith("audio/")) {
                if (bw >= bestAudioBw) {
                    bestAudioBw = bw
                    audioUrl = baseUrl
                    audioBytes = contentLen
                }
            } else if (mime.startsWith("video/")) {
                val w = attrs["width"]?.toIntOrNull() ?: 0
                val h = attrs["height"]?.toIntOrNull() ?: 0
                val labelHint = attrs["FBQualityLabel"].orEmpty()
                val tier = classifyVideoTier(w, h, labelHint, buckets) ?: return@forEach
                val isH264 = codecs.startsWith("avc1", true) || codecs.startsWith("h264", true)
                val isHevc = codecs.startsWith("hev1", true) || codecs.startsWith("hvc1", true)
                val isVp9 = codecs.startsWith("vp09", true) || codecs.startsWith("vp9", true)
                val isAv1 = codecs.startsWith("av01", true) || codecs.startsWith("av1", true)
                if (!(isH264 || isHevc || isVp9 || isAv1)) return@forEach

                val score = (when {
                    isH264 -> 2_000
                    isHevc -> 1_500
                    isVp9 -> 1_000
                    isAv1 -> 500
                    else -> 0
                }) + (bw / 10_000L).toInt().coerceIn(0, 400)
                val codecHint = when {
                    isH264 -> "MP4 · H.264"
                    isHevc -> "MP4 · HEVC"
                    isVp9 -> "MP4 · VP9"
                    isAv1 -> "MP4 · AV1"
                    else -> "MP4 · Video"
                }
                val opt = VideoOption(
                    height = tier,
                    label = "${tier}p",
                    badge = qualityBadge(tier).ifEmpty { "MP4" },
                    codecHint = codecHint,
                    approxSizeBytes = contentLen,
                    needsAudioMux = true,
                    directUrl = baseUrl,
                )
                val prev = bestVideoByBucket[tier]
                if (prev == null || score > prev.first) {
                    bestVideoByBucket[tier] = score to opt
                }
            }
        }

        if (bestVideoByBucket.isEmpty()) return null
        val options = bestVideoByBucket.values
            .map {
                it.second.copy(
                    needsAudioMux = audioUrl != null,
                    approxSizeBytes = it.second.approxSizeBytes?.let { vs -> vs + (audioBytes ?: 0L) },
                )
            }
            .sortedByDescending { it.height }
        return ParsedDashManifest(options, audioUrl, audioBytes)
    }

    fun matchesExistingDownloadName(
        fileName: String,
        mediaId: String,
        qualityTag: String,
        extension: String,
    ): Boolean {
        if (mediaId.isBlank()) return false
        val shortPrefix = if (mediaId.length >= 11) mediaId.take(11) else mediaId
        val idMarker = "[$mediaId]"
        val qualityMarker = "[$qualityTag]"
        if (!fileName.contains(qualityMarker) || !fileName.endsWith(".$extension", ignoreCase = true)) {
            return false
        }
        return fileName.contains(idMarker) || (shortPrefix.length >= 10 && fileName.contains("[$shortPrefix"))
    }

    fun extractMinimalInstagramCookies(rawCookieHeader: String?): Map<String, String> {
        if (rawCookieHeader.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawCookieHeader.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name in allowedIgCookies && value.isNotEmpty()) {
                result[name] = value
            }
        }
        return if ("sessionid" in result) result else emptyMap()
    }
}
