package io.github.cptmaverick_22.zardit.downloader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Html
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.cptmaverick_22.zardit.data.DiagnosticLog
import io.github.cptmaverick_22.zardit.data.FacebookAuth
import io.github.cptmaverick_22.zardit.data.HistoryStore
import io.github.cptmaverick_22.zardit.data.InstagramAuth
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

object ZarditEngine {

    val defaultAudioOptions = listOf(
        AudioOption("orig_m4a", "Best Audio", "M4A / AAC · Native quality", null, isOriginal = true),
        AudioOption("compact_m4a", "Compact Audio", "M4A · Smaller file", 128, isOriginal = false),
    )

    val ffmpegAudioOptions = listOf(
        AudioOption(
            id = "mp3_320",
            label = "320 kbps MP3",
            subtitle = "MP3 · Maximum quality + cover art (FFmpeg)",
            bitrateKbps = 320,
            isOriginal = false,
            requiresFfmpeg = true,
            ext = "mp3",
        ),
        AudioOption(
            id = "mp3_256",
            label = "256 kbps MP3",
            subtitle = "MP3 · High quality + cover art (FFmpeg)",
            bitrateKbps = 256,
            isOriginal = false,
            requiresFfmpeg = true,
            ext = "mp3",
        ),
        AudioOption(
            id = "mp3_192",
            label = "192 kbps MP3",
            subtitle = "MP3 · Balanced quality + cover art (FFmpeg)",
            bitrateKbps = 192,
            isOriginal = false,
            requiresFfmpeg = true,
            ext = "mp3",
        ),
        AudioOption(
            id = "mp3_128",
            label = "128 kbps MP3",
            subtitle = "MP3 · Compact size + cover art (FFmpeg)",
            bitrateKbps = 128,
            isOriginal = false,
            requiresFfmpeg = true,
            ext = "mp3",
        ),
    )

    val allAudioOptions: List<AudioOption> = defaultAudioOptions + ffmpegAudioOptions

    fun findAudioOption(audioId: String?): AudioOption? =
        allAudioOptions.find { it.id == audioId }

    fun audioOptionsWithAddon(context: Context, base: List<AudioOption>, durationSec: Int = 0): List<AudioOption> {
        if (base.isEmpty() || !FfmpegAddonManager.isInstalled(context)) return base
        val existingIds = base.map { it.id }.toSet()
        val extras = ffmpegAudioOptions
            .filter { it.id !in existingIds }
            .map { opt ->
                val est = if (opt.bitrateKbps != null && durationSec > 0) {
                    (durationSec.toLong() * opt.bitrateKbps.toLong() * 1000L) / 8L
                } else {
                    null
                }
                opt.copy(approxSizeBytes = est)
            }
        return base + extras
    }

    private var igRateLimitedUntilMs: Long = 0L

    private fun genuineBrowserUserAgent(context: Context?): String {
        val fallback = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
        if (context == null) return fallback
        return runCatching {
            WebSettings.getDefaultUserAgent(context.applicationContext)
                .replace("; wv", "")
                .ifBlank { fallback }
        }.getOrDefault(fallback)
    }

    private fun isIgRateLimitMessage(msg: String): Boolean {
        val low = msg.lowercase(Locale.US)
        return "try again later" in low ||
            "please wait a few minutes" in low ||
            "challenge_required" in low ||
            "feedback_required" in low ||
            "429" in low ||
            "rate limit" in low
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
    private val videoContainers = setOf("mp4", "webm", "mov", "mkv", "m3u8")
    private val croppedUrlRegex = Regex(
        """(?:/[sp]\d+x\d+/|/c\d+\.\d+\.\d+\.\d+|dst-jpg_e\d+_[sp]|sqp=|profile_pic)""",
        RegexOption.IGNORE_CASE,
    )
    private val ogTagRegex = Regex(
        """<meta\s+(?:property|name)=["']([^"']+)["']\s+content=["']([^"']+)["']|<meta\s+content=["']([^"']+)["']\s+(?:property|name)=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun isPlaylistUrl(url: String): Boolean = ZarditParser.isPlaylistUrl(url)

    fun fetchPreview(context: Context, url: String): MediaPreview {
        val rawUrl = url.trim()
        require(ZarditParser.isSafePublicUrl(rawUrl)) {
            "Enter a valid public http/https link (local or private network addresses are not allowed)."
        }
        if (ZarditParser.isUnsupportedRedditUrl(rawUrl)) {
            error("Reddit downloads were removed because Reddit now blocks automated access without login. Save Reddit videos directly in the official Reddit app.")
        }
        val cleanUrl = normalizeAndResolveUrl(rawUrl, context)
        DiagnosticLog.log("Fetch", "URL resolved: $cleanUrl")

        if (isPlaylistUrl(cleanUrl)) {
            fetchPlaylistPreview(cleanUrl)?.let { return it }
        }

        val info = try {
            YoutubeDL.getInstance().getInfo(cleanUrl)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            DiagnosticLog.log("getInfoError", msg)
            val platform = detectPlatformName(cleanUrl)
            val isIg = platform == "Instagram"
            val isFb = platform == "Facebook"
            val isThreads = platform == "Threads"
            val isNoVideo = msg.contains("No video formats found", ignoreCase = true) ||
                msg.contains("no video in this post", ignoreCase = true) ||
                msg.contains("No video found", ignoreCase = true) ||
                msg.contains("HTTP Error 404", ignoreCase = true) ||
                msg.contains("HTTP Error 400", ignoreCase = true)

            if (isThreads) {
                fetchWebMetaFallback(cleanUrl)?.let { return it }
            }

            if (isFb) {
                val hasFb = FacebookAuth.hasSession(context)
                if (hasFb && !isNoVideo) {
                    fetchWithFacebookCookies(context, cleanUrl)?.let { return it }
                }
                fetchFacebookHtmlFallback(context, cleanUrl, useFbCookies = hasFb)?.let { return it }
                if (hasFb) {
                    fetchWithFacebookCookies(context, cleanUrl)?.let { return it }
                }
            }

            if (isIg) {
                // Always try unauthenticated public embed BEFORE touching user's Instagram account
                fetchInstagramEmbedFallback(cleanUrl, context)?.let { return it }

                val hasIg = InstagramAuth.hasSession(context)
                if (hasIg) {
                    val now = System.currentTimeMillis()
                    if (isIgRateLimitMessage(msg)) {
                        igRateLimitedUntilMs = now + 30 * 60_000L
                    }
                    if (now < igRateLimitedUntilMs) {
                        throw IllegalStateException(
                            "Instagram temporarily limited requests to protect your account. Wait 15–30 minutes before fetching with your IG session, or use public links.",
                        )
                    }
                    if (!isNoVideo) {
                        fetchWithInstagramCookies(context, cleanUrl)?.let { return it }
                    }
                    if (now < igRateLimitedUntilMs) {
                        throw IllegalStateException(
                            "Instagram asked to slow down ('Try Again Later'). Paused IG account fetches to protect your account — wait a while before retrying.",
                        )
                    }
                    fetchInstagramViaWebView(context, cleanUrl)?.let { return it }
                } else if (isNoVideo) {
                    fetchImageFallback(context, cleanUrl, useIgCookies = false)?.let { return it }
                }
            } else if (!isFb && isNoVideo) {
                fetchImageFallback(context, cleanUrl, useIgCookies = false)?.let { return it }
            }

            if (msg.contains("generic", ignoreCase = true) || msg.contains("Unsupported URL", ignoreCase = true)) {
                fetchWebMetaFallback(cleanUrl)?.let { return it }
            }

            throw IllegalStateException(friendlyError(msg, isInstagram = isIg, isFacebook = isFb))
        }

        val formats = info.formats.orEmpty()
        val durationSec = info.duration.coerceAtLeast(0)

        var bestAudioSize = formats
            .filter { f -> f.vcodec == "none" && f.acodec != "none" }
            .maxOfOrNull { f ->
                val raw = f.fileSize.coerceAtLeast(f.fileSizeApproximate)
                if (raw > 0L) raw else if (durationSec > 0 && f.tbr > 0) (durationSec * f.tbr * 1000.0 / 8.0).toLong() else 0L
            } ?: 0L

        if (bestAudioSize <= 0L) {
            val audioUrl = formats
                .filter { f -> f.vcodec == "none" && f.acodec != "none" && !f.url.isNullOrBlank() }
                .maxByOrNull { f -> if (f.ext == "m4a") 1 else 0 }
                ?.url
            if (!audioUrl.isNullOrBlank()) {
                bestAudioSize = probeUrlSize(audioUrl) ?: 0L
            }
        }

        val heightBuckets = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        val bestByTier = mutableMapOf<Int, Pair<Int, VideoOption>>()
        for (f in formats) {
            val ext = (f.ext ?: "").lowercase(Locale.US)
            val vcodec = f.vcodec ?: ""
            val note = f.formatNote ?: ""
            if (ext in imageExts || ext == "mhtml" || vcodec == "none" || vcodec == "images") continue
            if ("storyboard" in note.lowercase(Locale.US)) continue
            if (vcodec.isBlank() && f.height <= 0 && f.width <= 0) continue

            val tier = classifyVideoTier(f.width, f.height, note, heightBuckets) ?: continue
            val isMp4 = ext == "mp4"
            val isH264 = vcodec.startsWith("avc1", ignoreCase = true) || vcodec.startsWith("h264", ignoreCase = true)
            val isHevc = vcodec.startsWith("hev1", ignoreCase = true) || vcodec.startsWith("hvc1", ignoreCase = true)
            val isVp9 = vcodec.startsWith("vp09", ignoreCase = true) || vcodec.startsWith("vp9", ignoreCase = true)
            val isAv1 = vcodec.startsWith("av01", ignoreCase = true) || vcodec.startsWith("av1", ignoreCase = true)
            val isMuxableCodec = isH264 || isHevc || isVp9 || isAv1
            val isMuxed = f.acodec != "none" && !f.acodec.isNullOrBlank()
            val needsMux = !isMuxed

            // Media3 Mp4Muxer remuxes MP4 H.264, HEVC, VP9, and AV1 video-only DASH streams without FFmpeg
            if (needsMux && !(isMp4 && isMuxableCodec)) continue

            // Score: MP4 container first, H.264 > HEVC > VP9 > AV1 within the same tier, pre-muxed tie-breaker
            val score = (if (isMp4) 10_000 else 0) +
                (when {
                    isH264 -> 2_000
                    isHevc -> 1_500
                    isVp9 -> 1_000
                    isAv1 -> 500
                    else -> 0
                }) +
                (if (isMuxed) 400 else 0) +
                f.tbr.toInt().coerceIn(0, 350)

            var videoBytes = f.fileSize.coerceAtLeast(f.fileSizeApproximate)
            if (videoBytes <= 0L && durationSec > 0 && f.tbr > 0) {
                videoBytes = (durationSec * f.tbr * 1000.0 / 8.0).toLong()
            }

            val existing = bestByTier[tier]
            if (existing == null || score > existing.first) {
                val approx = if (videoBytes > 0L) videoBytes + (if (needsMux) bestAudioSize else 0L) else null
                val codecHint = when {
                    isH264 -> "MP4 · H.264"
                    isHevc -> "MP4 · HEVC"
                    isVp9 -> "MP4 · VP9"
                    isAv1 -> "MP4 · AV1"
                    else -> "${ext.uppercase(Locale.US).ifBlank { "MP4" }} · Video"
                }
                val opt = VideoOption(
                    height = tier,
                    label = "${tier}p",
                    badge = qualityBadge(tier),
                    codecHint = codecHint,
                    approxSizeBytes = approx,
                    formatId = f.formatId?.takeIf { it.isNotBlank() },
                    needsAudioMux = needsMux,
                )
                bestByTier[tier] = score to opt
            }
        }

        // Probe size only for the winning format per tier when size wasn't in metadata
        var videoOptions = bestByTier.values
            .map { it.second }
            .sortedByDescending { it.height }
            .map { opt ->
                if (opt.approxSizeBytes != null || opt.formatId == null) {
                    opt
                } else {
                    val matchedFmt = formats.firstOrNull { it.formatId == opt.formatId }
                    val probed = matchedFmt?.url?.takeIf { it.startsWith("http") }?.let { probeUrlSize(it) }
                    val approx = probed?.let { it + (if (opt.needsAudioMux) bestAudioSize else 0L) }
                    opt.copy(approxSizeBytes = approx)
                }
            }

        val hasAnyVideoFormat = formats.any { f ->
            val fExt = (f.ext ?: "").lowercase(Locale.US)
            val vc = f.vcodec
            val nl = (f.formatNote ?: "").lowercase(Locale.US)
            fExt !in imageExts && fExt != "mhtml" && vc != "none" && vc != "images" &&
                "storyboard" !in nl && ((!vc.isNullOrBlank()) || f.height > 0 || f.width > 0 || fExt in videoContainers)
        }
        val isPureImage = (info.ext?.lowercase(Locale.US) in imageExts) ||
            (videoOptions.isEmpty() && !hasAnyVideoFormat)

        val imageUrls = buildList {
            if (isPureImage && !info.url.isNullOrBlank() && info.url!!.startsWith("http")) {
                add(info.url!!)
            } else {
                val thumb = info.thumbnail?.takeIf { it.startsWith("http") }
                if (thumb != null) add(thumb)
            }
        }

        if (videoOptions.isEmpty() && !isPureImage) {
            // Pre-muxed MP4 fallback (e.g. Facebook sd/hd or Instagram formats with width=None, height=None)
            val candidates = formats.filter { f ->
                (f.ext ?: "").lowercase(Locale.US) in videoContainers &&
                    f.vcodec != "none" &&
                    !f.url.isNullOrBlank()
            }
            val seenTiers = mutableSetOf<Int>()
            val parsedFallbacks = buildList<VideoOption> {
                for (f in candidates.reversed()) {
                    val fidLow = (f.formatId ?: "").lowercase(Locale.US)
                    val noteLow = (f.formatNote ?: "").lowercase(Locale.US)
                    val tier = when {
                        fidLow in setOf("hd", "dash_hd") || "hd" in noteLow -> 720
                        fidLow in setOf("sd", "dash_sd") || "sd" in noteLow -> 480
                        else -> 0
                    }
                    if (!seenTiers.add(tier)) continue
                    val probeTarget = f.url?.takeIf { it.startsWith("http") }
                    val size = f.fileSize.takeIf { it > 0L }
                        ?: f.fileSizeApproximate.takeIf { it > 0L }
                        ?: probeTarget?.let { probeUrlSize(it) }
                    val label = if (tier > 0) "${tier}p" else "Original"
                    val badge = if (tier > 0) qualityBadge(tier).ifEmpty { "MP4" } else "MP4"
                    add(
                        VideoOption(
                            height = tier,
                            label = label,
                            badge = badge,
                            codecHint = "MP4 · H.264 + AAC",
                            approxSizeBytes = size,
                            formatId = f.formatId?.takeIf { it.isNotBlank() },
                            needsAudioMux = false,
                        ),
                    )
                }
            }.sortedByDescending { it.height }

            if (parsedFallbacks.isNotEmpty()) {
                videoOptions = parsedFallbacks
            } else {
                val probeTarget = info.url?.takeIf { it.startsWith("http") }
                val fallbackSize = probeTarget?.let { probeUrlSize(it) }
                videoOptions = listOf(
                    VideoOption(
                        height = 0,
                        label = "Original",
                        badge = "MP4",
                        codecHint = "MP4 · H.264 + AAC",
                        approxSizeBytes = fallbackSize,
                        formatId = null,
                        needsAudioMux = false,
                    ),
                )
            }
        }

        val fallbackVideoSize = videoOptions.firstOrNull { it.approxSizeBytes != null }?.approxSizeBytes
        val baseAudioOptions = defaultAudioOptions.map { opt ->
            val estimated = when {
                opt.isOriginal -> bestAudioSize.takeIf { it > 0L }
                    ?: if (durationSec > 0) (durationSec.toLong() * 128_000L) / 8L
                    else fallbackVideoSize?.let { (it / 5L).coerceAtLeast(100_000L) }
                opt.bitrateKbps != null && durationSec > 0 ->
                    (durationSec.toLong() * opt.bitrateKbps.toLong() * 1000L) / 8L
                opt.bitrateKbps != null && bestAudioSize > 0L ->
                    (bestAudioSize * 8L) / 10L
                opt.bitrateKbps != null && fallbackVideoSize != null ->
                    (fallbackVideoSize / 6L).coerceAtLeast(80_000L)
                else -> null
            }
            opt.copy(approxSizeBytes = estimated)
        }
        val audioOptions = audioOptionsWithAddon(context, baseAudioOptions, durationSec)

        val platform = (info.extractorKey ?: info.extractor ?: "Web")
            .replace("Tab", "")
            .uppercase(Locale.US)
        val kindLabel = if (isPureImage && imageUrls.isNotEmpty()) "IMAGE" else "VIDEO"

        val rawTitle = decodeHtml(info.title?.ifBlank { null } ?: "Untitled media")
        val cleanTitle = sanitizeTitle(rawTitle, fallback = platform)

        return MediaPreview(
            mediaId = info.id?.ifBlank { null } ?: cleanUrl.hashCode().toString(),
            url = cleanUrl,
            title = rawTitle,
            author = decodeHtml(info.uploader?.ifBlank { null } ?: "Unknown creator"),
            platform = "$platform · $kindLabel",
            durationText = formatDuration(durationSec),
            thumbnailUrl = info.thumbnail?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            videoOptions = videoOptions,
            audioOptions = audioOptions,
            imageUrls = imageUrls,
            sanitizedTitle = cleanTitle,
        )
    }

    private fun classifyVideoTier(width: Int, height: Int, note: String, buckets: List<Int>): Int? =
        ZarditParser.classifyVideoTier(width, height, note, buckets)

    private fun effectiveVideoHeight(width: Int, height: Int): Int =
        ZarditParser.effectiveVideoHeight(width, height)

    private fun normalizeAndResolveUrl(url: String, context: Context? = null): String {
        var current = url
        val lower = current.lowercase(Locale.US)
        val isThreadsShare = ("threads.com" in lower || "threads.net" in lower) && "/share/" in lower
        val isIgShare = ("instagram.com" in lower || "instagr.am" in lower) && "/share/" in lower
        val isFbShare = ("facebook.com" in lower || "fb.watch" in lower || "fb.com" in lower) && "/share/" in lower
        if (isThreadsShare || isIgShare || isFbShare) {
            runCatching {
                val igCookie = if (isIgShare && context != null && InstagramAuth.hasSession(context)) {
                    InstagramAuth.getCookieHeader(context)
                } else {
                    null
                }
                val fbCookie = if (isFbShare && context != null && FacebookAuth.hasSession(context)) {
                    FacebookAuth.getCookieHeader(context)
                } else {
                    null
                }
                var hop = current
                repeat(5) {
                    if (!ZarditParser.isSafePublicUrl(hop)) return@repeat
                    val hopUrl = URL(hop)
                    val conn = (hopUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "curl/8.5.0")
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        val hLow = hopUrl.host.lowercase(Locale.US)
                        if (igCookie != null && hLow.endsWith("instagram.com")) {
                            setRequestProperty("Cookie", igCookie)
                        } else if (fbCookie != null && hLow.endsWith("facebook.com")) {
                            setRequestProperty("Cookie", fbCookie)
                        }
                    }
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (code in 300..399 && !loc.isNullOrBlank()) {
                        val nextUrl = if (loc.startsWith("http")) loc else URL(hopUrl, loc).toString()
                        if ("/accounts/login" in nextUrl && "next=" in nextUrl) {
                            val nextParam = Regex("""[?&]next=([^&]+)""").find(nextUrl)?.groupValues?.getOrNull(1)
                            val decoded = nextParam?.let { URLDecoder.decode(it, "UTF-8") }
                            if (!decoded.isNullOrBlank()) {
                                val resolved = if (decoded.startsWith("http")) decoded else "https://www.instagram.com$decoded"
                                if (ZarditParser.isSafePublicUrl(resolved) && ("/p/" in resolved || "/reel/" in resolved || "/tv/" in resolved)) {
                                    hop = resolved
                                    return@repeat
                                }
                            }
                            return@repeat
                        }
                        if (!ZarditParser.isSafePublicUrl(nextUrl)) return@repeat
                        hop = nextUrl
                    } else {
                        return@repeat
                    }
                }
                if (ZarditParser.isSafePublicUrl(hop)) current = hop
            }
        }
        return ZarditParser.normalizeThreadsUrl(current)
    }

    fun decodeHtml(raw: String): String {
        if (!raw.contains("&")) return raw
        return runCatching {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }.getOrDefault(raw)
    }

    fun sanitizeTitle(raw: String, fallback: String = "Zardit"): String {
        val decoded = decodeHtml(raw)
        val noHashtags = decoded.replace(Regex("""#\S+"""), " ")
        val safe = noHashtags
            .replace(Regex("""[\\/:*?"<>|]"""), "-")
            .replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '-')
        return safe.take(65).trim().ifBlank { fallback }
    }

    private fun bestUncroppedImageFromJson(entry: JSONObject): String? {
        val candidates = mutableListOf<Triple<String, Int, Int>>()
        fun collect(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val u = obj.optString("url").takeIf { it.startsWith("http") } ?: continue
                val ext = obj.optString("ext").lowercase(Locale.US)
                val vcodec = obj.optString("vcodec")
                if (arr === entry.optJSONArray("formats") && ext !in imageExts && vcodec != "images") continue
                val w = obj.optInt("width", 0)
                val h = obj.optInt("height", 0)
                val uncroppedScore = if (croppedUrlRegex.containsMatchIn(u)) 0 else 1
                candidates.add(Triple(u, uncroppedScore, w * h))
            }
        }
        collect(entry.optJSONArray("formats"))
        collect(entry.optJSONArray("thumbnails"))
        val best = candidates.maxWithOrNull(compareBy({ it.second }, { it.third }))
        if (best != null) return best.first

        val direct = entry.optString("url").takeIf { it.startsWith("http") }
        val thumb = entry.optString("thumbnail").takeIf { it.startsWith("http") }
        return listOfNotNull(direct, thumb)
            .sortedByDescending { if (croppedUrlRegex.containsMatchIn(it)) 0 else 1 }
            .firstOrNull()
    }

    private fun bestCandidateImage(candidates: JSONArray?): String? {
        if (candidates == null) return null
        var best: String? = null
        var bestArea = -1
        for (j in 0 until candidates.length()) {
            val c = candidates.optJSONObject(j) ?: continue
            val u = c.optString("url").takeIf { it.startsWith("http") } ?: continue
            val area = c.optInt("width", 0) * c.optInt("height", 0)
            if (area >= bestArea) {
                bestArea = area
                best = u
            }
        }
        return best
    }

    private fun bestVideoVersionUrl(versions: JSONArray?): Pair<String, Int>? {
        if (versions == null) return null
        var bestUrl: String? = null
        var bestArea = -1
        var bestHeight = 0
        for (i in 0 until versions.length()) {
            val v = versions.optJSONObject(i) ?: continue
            val u = v.optString("url").takeIf { it.startsWith("http") } ?: continue
            val w = v.optInt("width", 0)
            val h = v.optInt("height", 0)
            val area = w * h
            if (area >= bestArea) {
                bestArea = area
                bestUrl = u
                bestHeight = if (w > 0 && h > 0) minOf(w, h) else h
            }
        }
        return bestUrl?.let { it to bestHeight }
    }

    private fun parseInstagramJsonItem(
        item: JSONObject,
        shortcode: String,
        url: String,
        usedAuth: Boolean,
    ): MediaPreview? {
        val rawTitle = item.optJSONObject("caption")?.optString("text")?.ifBlank { null }
            ?: item.optJSONObject("edge_media_to_caption")
                ?.optJSONArray("edges")?.optJSONObject(0)
                ?.optJSONObject("node")?.optString("text")?.ifBlank { null }
            ?: "Instagram Post ($shortcode)"
        val author = item.optJSONObject("user")?.optString("username")?.ifBlank { null }
            ?: item.optJSONObject("owner")?.optString("username")?.ifBlank { null }
            ?: "Instagram"
        val durationSec = item.optDouble("video_duration", 0.0).toInt()
        val thumbUrl = bestCandidateImage(item.optJSONObject("image_versions2")?.optJSONArray("candidates"))
            ?: item.optString("display_url").takeIf { it.startsWith("http") }

        val imageUrls = linkedSetOf<String>()
        var firstCarouselVideo: JSONObject? = null
        val carousel = item.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                val slide = carousel.optJSONObject(i) ?: continue
                val hasSlideVid = slide.optString("video_dash_manifest").contains("<Representation") ||
                    (slide.optJSONArray("video_versions")?.length() ?: 0) > 0 ||
                    slide.optString("video_url").startsWith("http")
                if (hasSlideVid && firstCarouselVideo == null) {
                    firstCarouselVideo = slide
                }
                val bestImg = bestCandidateImage(slide.optJSONObject("image_versions2")?.optJSONArray("candidates"))
                    ?: slide.optString("display_url").takeIf { it.startsWith("http") }
                if (bestImg != null && !hasSlideVid) {
                    imageUrls.add(bestImg)
                }
            }
        } else {
            val sidecarEdges = item.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            if (sidecarEdges != null && sidecarEdges.length() > 0) {
                for (i in 0 until sidecarEdges.length()) {
                    val node = sidecarEdges.optJSONObject(i)?.optJSONObject("node") ?: continue
                    val hasNodeVid = node.optBoolean("is_video", false) ||
                        node.optString("video_url").startsWith("http")
                    if (hasNodeVid && firstCarouselVideo == null) {
                        firstCarouselVideo = node
                    }
                    val bestImg = node.optString("display_url").takeIf { it.startsWith("http") }
                    if (bestImg != null && !hasNodeVid) {
                        imageUrls.add(bestImg)
                    }
                }
            }
        }

        val videoTarget = firstCarouselVideo ?: item
        val dashManifest = videoTarget.optString("video_dash_manifest")
            .takeIf { it.contains("<Representation") }
        val videoVersions = videoTarget.optJSONArray("video_versions")
        val graphqlVideoUrl = videoTarget.optString("video_url").takeIf { it.startsWith("http") }
        val bestDirectVideo = bestVideoVersionUrl(videoVersions)
            ?: graphqlVideoUrl?.let { it to 0 }

        if ((dashManifest != null || bestDirectVideo != null) && imageUrls.isEmpty()) {
            val slideThumb = bestCandidateImage(videoTarget.optJSONObject("image_versions2")?.optJSONArray("candidates"))
                ?: videoTarget.optString("display_url").takeIf { it.startsWith("http") }
                ?: thumbUrl
            val parsedDash = dashManifest?.let { parseDashManifest(it) }
            if (parsedDash != null && parsedDash.videoOptions.isNotEmpty()) {
                return MediaPreview(
                    mediaId = shortcode,
                    url = url,
                    title = rawTitle,
                    author = author,
                    platform = "INSTAGRAM · VIDEO",
                    durationText = formatDuration(durationSec),
                    thumbnailUrl = slideThumb,
                    videoOptions = parsedDash.videoOptions,
                    audioOptions = if (parsedDash.audioUrl != null) {
                        listOf(AudioOption("orig_m4a", "Best Audio", "M4A / AAC · Native quality", null, isOriginal = true, approxSizeBytes = parsedDash.audioBytes))
                    } else {
                        emptyList()
                    },
                    directVideoUrl = parsedDash.videoOptions.first().directUrl,
                    directAudioUrl = parsedDash.audioUrl,
                    imageUrls = listOfNotNull(slideThumb),
                    usedInstagramAuth = usedAuth,
                    sanitizedTitle = sanitizeTitle(rawTitle, "Instagram_$shortcode"),
                )
            }
            if (bestDirectVideo != null) {
                val (vidUrl, vidTier) = bestDirectVideo
                val size = probeUrlSize(vidUrl)
                val optLabel = if (vidTier > 0) "${vidTier}p" else "Original"
                val optBadge = if (vidTier > 0) qualityBadge(vidTier).ifEmpty { "HD" } else "HD"
                return MediaPreview(
                    mediaId = shortcode,
                    url = url,
                    title = rawTitle,
                    author = author,
                    platform = "INSTAGRAM · VIDEO",
                    durationText = formatDuration(durationSec),
                    thumbnailUrl = slideThumb,
                    videoOptions = listOf(
                        VideoOption(if (vidTier > 0) vidTier else 1080, optLabel, optBadge, "MP4 · Direct stream", size),
                    ),
                    audioOptions = defaultAudioOptions.map {
                        it.copy(approxSizeBytes = size?.let { s -> (s / 5L).coerceAtLeast(80_000L) })
                    },
                    imageUrls = listOfNotNull(slideThumb),
                    directVideoUrl = vidUrl,
                    usedInstagramAuth = usedAuth,
                    sanitizedTitle = sanitizeTitle(rawTitle, "Instagram_$shortcode"),
                )
            }
        }

        if (imageUrls.isEmpty() && thumbUrl != null) {
            imageUrls.add(thumbUrl)
        }
        if (imageUrls.isNotEmpty()) {
            val list = imageUrls.toList()
            return MediaPreview(
                mediaId = shortcode,
                url = url,
                title = rawTitle,
                author = "$author · ${list.size} image${if (list.size == 1) "" else "s"}",
                platform = "INSTAGRAM · IMAGE",
                durationText = "${list.size} image${if (list.size == 1) "" else "s"}",
                thumbnailUrl = list.first(),
                videoOptions = emptyList(),
                audioOptions = emptyList(),
                imageUrls = list,
                usedInstagramAuth = usedAuth,
                sanitizedTitle = sanitizeTitle(rawTitle, "Instagram_$shortcode"),
            )
        }
        return null
    }

    private fun fetchInstagramMediaInfoById(
        cookieHeader: String,
        mediaId: String,
        shortcode: String,
        url: String,
    ): MediaPreview? = runCatching {
        val infoUrl = "https://i.instagram.com/api/v1/media/$mediaId/info/"
        val conn = (URL(infoUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("Cookie", cookieHeader)
            setRequestProperty("X-IG-App-ID", "936619743392459")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
        conn.disconnect()
        DiagnosticLog.log("IGMediaInfo", "code=$code len=${body.length}")
        if (body.isBlank() || !body.trimStart().startsWith("{")) return@runCatching null
        val root = JSONObject(body)
        val item = root.optJSONArray("items")?.optJSONObject(0) ?: return@runCatching null
        parseInstagramJsonItem(item, shortcode, url, usedAuth = true)
    }.getOrNull()

    private fun extractInstagramFromHtml(
        html: String,
        shortcode: String,
        url: String,
        cookieHeader: String?,
        usedAuth: Boolean,
    ): MediaPreview? {
        if ("checkpoint_required" in html || "challenge_required" in html || "Please wait a few minutes" in html) {
            igRateLimitedUntilMs = System.currentTimeMillis() + 30 * 60_000L
            return null
        }

        val windows = buildList {
            Regex(""""code"\s*:\s*"${Regex.escape(shortcode)}"""").findAll(html).forEach { m ->
                val start = (m.range.first - 20_000).coerceAtLeast(0)
                val end = (m.range.last + 20_000).coerceAtMost(html.length)
                add(html.substring(start, end))
            }
            add(html)
        }

        val meta = mutableMapOf<String, String>()
        ogTagRegex.findAll(html).forEach { m ->
            val key = (m.groupValues[1].ifEmpty { m.groupValues[4] }).lowercase(Locale.US)
            val valText = decodeHtml(m.groupValues[2].ifEmpty { m.groupValues[3] }).trim()
            if (key.isNotEmpty() && valText.isNotEmpty() && key !in meta) {
                meta[key] = valText
            }
        }
        val title = decodeHtml(meta["og:description"] ?: meta["og:title"] ?: "Instagram Post ($shortcode)")
        val dashRegex = Regex(""""video_dash_manifest"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val urlRegex = Regex(""""url"\s*:\s*"(https?:[^"]+)"""")

        for (win in windows) {
            dashRegex.findAll(win).forEach { dm ->
                val xml = dm.groupValues[1].unescapeJsonUrl().replace("\\n", "\n")
                val parsedDash = parseDashManifest(xml)
                if (parsedDash != null && parsedDash.videoOptions.isNotEmpty()) {
                    return MediaPreview(
                        mediaId = shortcode,
                        url = url,
                        title = title,
                        author = "Instagram",
                        platform = "INSTAGRAM · VIDEO",
                        durationText = "",
                        thumbnailUrl = meta["og:image"],
                        videoOptions = parsedDash.videoOptions,
                        audioOptions = if (parsedDash.audioUrl != null) {
                            listOf(AudioOption("orig_m4a", "Best Audio", "M4A / AAC · Native quality", null, isOriginal = true, approxSizeBytes = parsedDash.audioBytes))
                        } else {
                            emptyList()
                        },
                        directVideoUrl = parsedDash.videoOptions.first().directUrl,
                        directAudioUrl = parsedDash.audioUrl,
                        imageUrls = listOfNotNull(meta["og:image"]?.takeIf { it.startsWith("http") }),
                        usedInstagramAuth = usedAuth,
                        sanitizedTitle = sanitizeTitle(title, "Instagram_$shortcode"),
                    )
                }
            }
        }

        val scoped = windows.first()
        val videoVersionsBlockRegex = Regex(""""video_versions"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val versionsVid = videoVersionsBlockRegex.findAll(scoped)
            .flatMap { m -> urlRegex.findAll(m.groupValues[1]).map { it.groupValues[1].unescapeJsonUrl() } }
            .firstOrNull { it.startsWith("http") }
        val videoUrl = versionsVid
            ?: Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""").find(scoped)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
            ?: meta["og:video:secure_url"]?.takeIf { it.startsWith("http") }
            ?: meta["og:video"]?.takeIf { it.startsWith("http") }
        if (!videoUrl.isNullOrBlank()) {
            val size = probeUrlSize(videoUrl)
            val thumb = meta["og:image"]?.takeIf { it.startsWith("http") }
            return MediaPreview(
                mediaId = shortcode,
                url = url,
                title = title,
                author = "Instagram",
                platform = "INSTAGRAM · VIDEO",
                durationText = "",
                thumbnailUrl = thumb,
                videoOptions = listOf(VideoOption(1080, "Original", "HD", "MP4 · Direct stream", size)),
                audioOptions = defaultAudioOptions.map {
                    it.copy(approxSizeBytes = size?.let { s -> (s / 5L).coerceAtLeast(80_000L) })
                },
                imageUrls = listOfNotNull(thumb),
                directVideoUrl = videoUrl,
                usedInstagramAuth = usedAuth,
                sanitizedTitle = sanitizeTitle(title, "Instagram_$shortcode"),
            )
        }

        val images = linkedSetOf<String>()
        Regex(""""image_versions2"\s*:\s*\{\s*"candidates"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .findAll(scoped)
            .forEach { block ->
                urlRegex.find(block.groupValues[1])?.groupValues?.getOrNull(1)
                    ?.unescapeJsonUrl()
                    ?.takeIf { it.startsWith("http") && !croppedUrlRegex.containsMatchIn(it) }
                    ?.let { images.add(it) }
            }
        if (images.isEmpty()) {
            Regex(""""display_url"\s*:\s*"(https?:[^"]+)"""").findAll(scoped).forEach { m ->
                val u = m.groupValues[1].unescapeJsonUrl()
                if (u.startsWith("http") && !croppedUrlRegex.containsMatchIn(u)) images.add(u)
            }
        }
        if (images.isEmpty()) {
            meta["og:image"]
                ?.takeIf {
                    it.startsWith("http") &&
                        !croppedUrlRegex.containsMatchIn(it) &&
                        !ZarditParser.isBrandLogoOrLoginPage(title, it)
                }
                ?.let { images.add(it) }
        }
        if (images.isNotEmpty()) {
            val list = images.toList()
            return MediaPreview(
                mediaId = shortcode,
                url = url,
                title = title,
                author = "Instagram · ${list.size} image${if (list.size == 1) "" else "s"}",
                platform = "INSTAGRAM · IMAGE",
                durationText = "${list.size} image${if (list.size == 1) "" else "s"}",
                thumbnailUrl = list.first(),
                videoOptions = emptyList(),
                audioOptions = emptyList(),
                imageUrls = list,
                usedInstagramAuth = usedAuth,
                sanitizedTitle = sanitizeTitle(title, "Instagram_$shortcode"),
            )
        }
        return null
    }

    private fun fetchInstagramApiWithCookies(context: Context, url: String): MediaPreview? {
        val shortcode = Regex("""instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1) ?: return null

        return runCatching {
            val cookieHeader = InstagramAuth.getCookieHeader(context) ?: return@runCatching null

            val apiUrl = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("Cookie", cookieHeader)
                setRequestProperty("X-IG-App-ID", "936619743392459")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            conn.disconnect()
            DiagnosticLog.log("IGApi", "code=$code len=${body.length} isJson=${body.trimStart().startsWith("{")}")
            if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                val root = JSONObject(body)
                val item = root.optJSONArray("items")?.optJSONObject(0)
                    ?: root.optJSONObject("graphql")?.optJSONObject("shortcode_media")
                if (item != null) {
                    parseInstagramJsonItem(item, shortcode, url, usedAuth = true)?.let { return@runCatching it }
                }
            }

            // Fallback: fetch authenticated page HTML (resolves media_id + DASH/JSON payload on private/share URLs)
            val htmlConn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 9000
                readTimeout = 9000
                instanceFollowRedirects = true
                setRequestProperty("Cookie", cookieHeader)
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            val htmlCode = htmlConn.responseCode
            val html = if (htmlCode in 200..299) {
                htmlConn.inputStream.bufferedReader().use { it.readText().take(3 * 1024 * 1024) }
            } else {
                ""
            }
            htmlConn.disconnect()
            DiagnosticLog.log("IGWeb", "code=$htmlCode len=${html.length}")
            if (html.isNotBlank()) {
                extractInstagramFromHtml(html, shortcode, url, cookieHeader, usedAuth = true)?.let {
                    return@runCatching it
                }
            }
            null
        }.onFailure { err ->
            DiagnosticLog.log("IGApiErr", err.message ?: err::class.java.simpleName)
        }.getOrNull()
    }

    private fun fetchInstagramViaWebView(context: Context, url: String): MediaPreview? {
        val shortcode = Regex("""instagram\.com/(?:reel|reels|p|tv|share)/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1) ?: return null
        val cookieMap = InstagramAuth.getCookieMap(context)
        if ("sessionid" !in cookieMap) return null
        val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

        var payloadJson: String? = null
        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        var webViewRef: WebView? = null

        mainHandler.post {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cookieMap.forEach { (k, v) ->
                    cm.setCookie("https://www.instagram.com", "$k=$v; Path=/; Secure")
                    cm.setCookie("https://i.instagram.com", "$k=$v; Path=/; Secure")
                }
                cm.flush()

                val wv = WebView(context.applicationContext)
                webViewRef = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = genuineBrowserUserAgent(context)
                }

                var captured = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (captured || finishedUrl.isNullOrBlank() || finishedUrl == "about:blank") return
                        captured = true
                        mainHandler.postDelayed(
                            {
                                val script = """
                                    (function() {
                                      var html = document.documentElement ? document.documentElement.outerHTML : '';
                                      var title = (document.querySelector('meta[property="og:title"]') || {}).content || document.title || '';
                                      var videos = [];
                                      document.querySelectorAll('video, video source').forEach(function(el) {
                                        var s = el.currentSrc || el.src || '';
                                        if (s.indexOf('http') === 0 && videos.indexOf(s) < 0) videos.push(s);
                                      });
                                      var images = [];
                                      document.querySelectorAll('main img, article img').forEach(function(img) {
                                        var s = img.currentSrc || img.src || '';
                                        if (s.indexOf('http') === 0 && (img.naturalWidth || 0) >= 300 && images.indexOf(s) < 0) {
                                          images.push(s);
                                        }
                                      });
                                      return { html: html, title: title, videos: videos, images: images };
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(script) { result ->
                                    payloadJson = result
                                    latch.countDown()
                                } ?: latch.countDown()
                            },
                            1500L,
                        )
                    }
                }
                wv.loadUrl(url)
            }.onFailure {
                latch.countDown()
            }
        }

        latch.await(16, TimeUnit.SECONDS)
        mainHandler.post {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
        }

        val raw = payloadJson?.takeIf { it.trimStart().startsWith("{") } ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val html = obj.optString("html")
        DiagnosticLog.log("IGWebView", "htmlLen=${html.length}")
        if (html.isNotBlank()) {
            extractInstagramFromHtml(html, shortcode, url, cookieHeader, usedAuth = true)?.let {
                return it
            }
        }

        val domImages = obj.optJSONArray("images")
        val domThumbs = if (domImages != null) {
            (0 until domImages.length())
                .mapNotNull {
                    domImages.optString(it).takeIf { u ->
                        u.startsWith("http") &&
                            !croppedUrlRegex.containsMatchIn(u) &&
                            !ZarditParser.isBrandLogoOrLoginPage(obj.optString("title"), u)
                    }
                }
                .distinct()
        } else {
            emptyList()
        }

        val domVideos = obj.optJSONArray("videos")
        if (domVideos != null && domVideos.length() > 0) {
            val vidUrl = (0 until domVideos.length())
                .mapNotNull { domVideos.optString(it).takeIf { u -> u.startsWith("http") } }
                .firstOrNull()
            if (vidUrl != null) {
                val size = probeUrlSize(vidUrl)
                val title = obj.optString("title").ifBlank { "Instagram Reel ($shortcode)" }
                val thumb = domThumbs.firstOrNull()
                return MediaPreview(
                    mediaId = shortcode,
                    url = url,
                    title = title,
                    author = "Instagram",
                    platform = "INSTAGRAM · VIDEO",
                    durationText = "",
                    thumbnailUrl = thumb,
                    videoOptions = listOf(VideoOption(1080, "Original", "HD", "MP4 · Direct stream", size)),
                    audioOptions = defaultAudioOptions.map {
                        it.copy(approxSizeBytes = size?.let { s -> (s / 5L).coerceAtLeast(80_000L) })
                    },
                    imageUrls = listOfNotNull(thumb),
                    directVideoUrl = vidUrl,
                    usedInstagramAuth = true,
                    sanitizedTitle = sanitizeTitle(title, "Instagram_$shortcode"),
                )
            }
        }

        if (domThumbs.isNotEmpty()) {
            val imgs = domThumbs
            if (imgs.isNotEmpty()) {
                val title = obj.optString("title").ifBlank { "Instagram Post ($shortcode)" }
                return MediaPreview(
                    mediaId = shortcode,
                    url = url,
                    title = title,
                    author = "Instagram · ${imgs.size} image${if (imgs.size == 1) "" else "s"}",
                    platform = "INSTAGRAM · IMAGE",
                    durationText = "${imgs.size} image${if (imgs.size == 1) "" else "s"}",
                    thumbnailUrl = imgs.first(),
                    videoOptions = emptyList(),
                    audioOptions = emptyList(),
                    imageUrls = imgs,
                    usedInstagramAuth = true,
                    sanitizedTitle = sanitizeTitle(title, "Instagram_$shortcode"),
                )
            }
        }
        return null
    }

    private fun fetchInstagramEmbedFallback(url: String, context: Context? = null): MediaPreview? {
        val shortcode = Regex("""instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1) ?: return null
        val ua = genuineBrowserUserAgent(context)
        val targets = listOf(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/",
        )

        for (embedUrl in targets) {
            val found = runCatching {
                val conn = (URL(embedUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                val html = conn.inputStream.bufferedReader().use { it.readText().take(1024 * 1024) }
                conn.disconnect()

                val videoUrl = Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""")
                    .find(html)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
                    ?: Regex("""<video[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")

                val displayUrls = linkedSetOf<String>()
                Regex(""""display_url"\s*:\s*"(https?:[^"]+)"""")
                    .findAll(html)
                    .forEach { m ->
                        val u = m.groupValues[1].unescapeJsonUrl()
                        if (!croppedUrlRegex.containsMatchIn(u) && !ZarditParser.isBrandLogoOrLoginPage(null, u)) {
                            displayUrls.add(u)
                        }
                    }
                if (displayUrls.isEmpty()) {
                    Regex("""class=["'][^"']*EmbeddedMediaImage[^"']*["'][^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                        .findAll(html)
                        .forEach { m ->
                            val u = m.groupValues[1].replace("&amp;", "&")
                            if (!ZarditParser.isBrandLogoOrLoginPage(null, u)) displayUrls.add(u)
                        }
                }

                if (!videoUrl.isNullOrBlank()) {
                    val size = probeUrlSize(videoUrl)
                    val thumb = displayUrls.firstOrNull()
                    return@runCatching MediaPreview(
                        mediaId = shortcode,
                        url = url,
                        title = "Instagram Reel ($shortcode)",
                        author = "Instagram",
                        platform = "INSTAGRAM · VIDEO",
                        durationText = "",
                        thumbnailUrl = thumb,
                        videoOptions = listOf(
                            VideoOption(1080, "Original", "HD", "MP4 · Direct stream", size),
                        ),
                        audioOptions = defaultAudioOptions.map {
                            it.copy(approxSizeBytes = size?.let { s -> (s / 5L).coerceAtLeast(80_000L) })
                        },
                        imageUrls = listOfNotNull(thumb),
                        directVideoUrl = videoUrl,
                        sanitizedTitle = "Instagram_$shortcode",
                    )
                }

                if (displayUrls.isNotEmpty()) {
                    val list = displayUrls.toList()
                    return@runCatching MediaPreview(
                        mediaId = shortcode,
                        url = url,
                        title = "Instagram Post ($shortcode)",
                        author = "Instagram · ${list.size} image${if (list.size == 1) "" else "s"}",
                        platform = "INSTAGRAM · IMAGE",
                        durationText = "${list.size} image${if (list.size == 1) "" else "s"}",
                        thumbnailUrl = list.first(),
                        videoOptions = emptyList(),
                        audioOptions = emptyList(),
                        imageUrls = list,
                        sanitizedTitle = "Instagram_$shortcode",
                    )
                }
                null
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun fetchWithInstagramCookies(context: Context, url: String): MediaPreview? = runCatching {
        val jsonStr = InstagramAuth.withTempCookieFile(context) { cookieFile ->
            val req = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--cookies", cookieFile.absolutePath)
            }
            YoutubeDL.getInstance().execute(req, null, null).out
        }
        if (jsonStr.isBlank()) return@runCatching null
        val json = JSONObject(jsonStr)
        val formatsArr = json.optJSONArray("formats")
        val durationSec = json.optInt("duration", 0)
        val rawTitle = decodeHtml(json.optString("title").ifBlank { "Instagram Post" })
        val id = json.optString("id").ifBlank { url.hashCode().toString() }
        val uploader = decodeHtml(json.optString("uploader").ifBlank { "Instagram" })
        val thumb = bestUncroppedImageFromJson(json)

        var probedSize: Long? = null
        var bestAudioSize = 0L
        if (formatsArr != null && formatsArr.length() > 0) {
            for (i in 0 until formatsArr.length()) {
                val f = formatsArr.optJSONObject(i) ?: continue
                if (f.optString("vcodec") == "none" && f.optString("acodec") != "none") {
                    val s = f.optLong("filesize", 0L).takeIf { it > 0L }
                        ?: f.optLong("filesize_approx", 0L).takeIf { it > 0L }
                        ?: f.optString("url").takeIf { it.startsWith("http") }?.let { probeUrlSize(it) }
                        ?: 0L
                    if (s > bestAudioSize) bestAudioSize = s
                }
            }
            val lastFmt = formatsArr.optJSONObject(formatsArr.length() - 1)
            val fmtUrl = lastFmt?.optString("url")?.takeIf { it.startsWith("http") }
            val rawSize = lastFmt?.optLong("filesize", 0L)?.takeIf { it > 0L }
                ?: lastFmt?.optLong("filesize_approx", 0L)?.takeIf { it > 0L }
            probedSize = rawSize ?: fmtUrl?.let { probeUrlSize(it) }
        } else {
            val directUrl = json.optString("url").takeIf { it.startsWith("http") }
            probedSize = directUrl?.let { probeUrlSize(it) }
        }

        val hasVideo = formatsArr != null && formatsArr.length() > 0
        if (!hasVideo && thumb != null) {
            return@runCatching MediaPreview(
                mediaId = id,
                url = url,
                title = rawTitle,
                author = uploader,
                platform = "INSTAGRAM · IMAGE",
                durationText = "1 image",
                thumbnailUrl = thumb,
                videoOptions = emptyList(),
                audioOptions = emptyList(),
                imageUrls = listOf(thumb),
                usedInstagramAuth = true,
                sanitizedTitle = sanitizeTitle(rawTitle, "Instagram_$id"),
            )
        }

        MediaPreview(
            mediaId = id,
            url = url,
            title = rawTitle,
            author = uploader,
            platform = "INSTAGRAM · VIDEO",
            durationText = formatDuration(durationSec),
            thumbnailUrl = thumb,
            videoOptions = buildList {
                val buckets = listOf(1440, 1080, 720, 480, 360)
                if (formatsArr != null && formatsArr.length() > 0) {
                    val bestByBucket = mutableMapOf<Int, Pair<Int, VideoOption>>()
                    for (i in 0 until formatsArr.length()) {
                        val f = formatsArr.optJSONObject(i) ?: continue
                        val vcodec = f.optString("vcodec")
                        val ext = f.optString("ext").lowercase(Locale.US)
                        if (vcodec == "none" || ext != "mp4") continue
                        val needsAudio = f.optString("acodec") == "none"
                        val isH264 = vcodec.startsWith("avc1", ignoreCase = true) || vcodec.startsWith("h264", ignoreCase = true)
                        val isHevc = vcodec.startsWith("hev1", ignoreCase = true) || vcodec.startsWith("hvc1", ignoreCase = true)
                        val isVp9 = vcodec.startsWith("vp09", ignoreCase = true) || vcodec.startsWith("vp9", ignoreCase = true)
                        val isAv1 = vcodec.startsWith("av01", ignoreCase = true) || vcodec.startsWith("av1", ignoreCase = true)
                        if (needsAudio && !(isH264 || isHevc || isVp9 || isAv1)) continue

                        val eh = effectiveVideoHeight(f.optInt("width", 0), f.optInt("height", 0))
                        val bucket = buckets.firstOrNull { eh in (it - 20)..(it + 20) } ?: continue
                        val fmtUrl = f.optString("url").takeIf { it.startsWith("http") }
                        val rawSize = f.optLong("filesize", 0L).takeIf { it > 0L }
                            ?: f.optLong("filesize_approx", 0L).takeIf { it > 0L }
                            ?: fmtUrl?.let { probeUrlSize(it) }
                        val totalSize = rawSize?.let { it + (if (needsAudio) bestAudioSize else 0L) } ?: probedSize
                        val fId = f.optString("format_id").takeIf { it.isNotBlank() }
                        val codecHint = when {
                            isH264 -> "MP4 · H.264"
                            isHevc -> "MP4 · HEVC"
                            isVp9 -> "MP4 · VP9"
                            isAv1 -> "MP4 · AV1"
                            else -> "MP4 · Video"
                        }
                        val score = (when {
                            isH264 -> 2_000
                            isHevc -> 1_500
                            isVp9 -> 1_000
                            isAv1 -> 500
                            else -> 0
                        }) + f.optDouble("tbr", 0.0).toInt().coerceIn(0, 400)
                        val candidate = VideoOption(
                            bucket,
                            "${bucket}p",
                            qualityBadge(bucket),
                            codecHint,
                            totalSize,
                            formatId = fId,
                            needsAudioMux = needsAudio,
                        )
                        val prev = bestByBucket[bucket]
                        if (prev == null || score > prev.first || (score == prev.first && (candidate.approxSizeBytes ?: 0L) > (prev.second.approxSizeBytes ?: 0L))) {
                            bestByBucket[bucket] = score to candidate
                        }
                    }
                    addAll(bestByBucket.values.map { it.second }.sortedByDescending { it.height })
                }
                if (isEmpty()) {
                    var fallbackId: String? = null
                    var fallbackSize = probedSize
                    if (formatsArr != null) {
                        for (i in 0 until formatsArr.length()) {
                            val f = formatsArr.optJSONObject(i) ?: continue
                            if (f.optString("ext").equals("mp4", true) &&
                                f.optString("acodec") != "none" &&
                                !f.optString("url").isNullOrBlank()
                            ) {
                                fallbackId = f.optString("format_id").takeIf { it.isNotBlank() }
                                fallbackSize = f.optLong("filesize", 0L).takeIf { it > 0L }
                                    ?: f.optString("url").takeIf { it.startsWith("http") }?.let { probeUrlSize(it) }
                                    ?: fallbackSize
                                break
                            }
                        }
                    }
                    add(VideoOption(0, "Original", "MP4", "MP4 · H.264 + AAC", fallbackSize, formatId = fallbackId, needsAudioMux = false))
                }
            },
            audioOptions = defaultAudioOptions.map { opt ->
                val est = if (durationSec > 0) {
                    (durationSec.toLong() * (opt.bitrateKbps ?: 128).toLong() * 1000L) / 8L
                } else {
                    probedSize?.let { (it / 5L).coerceAtLeast(80_000L) }
                }
                opt.copy(approxSizeBytes = est)
            },
            imageUrls = listOfNotNull(thumb),
            usedInstagramAuth = true,
            sanitizedTitle = sanitizeTitle(rawTitle, "Instagram_$id"),
        )
    }.getOrNull()

    private fun String.unescapeJsonUrl(): String =
        replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

    private fun fetchImageFallback(context: Context, url: String, useIgCookies: Boolean): MediaPreview? {
        runCatching {
            val execOut = if (useIgCookies && InstagramAuth.hasSession(context)) {
                InstagramAuth.withTempCookieFile(context) { cookieFile ->
                    val req = YoutubeDLRequest(url).apply {
                        addOption("--dump-single-json")
                        addOption("--ignore-no-formats-error")
                        addOption("--ignore-errors")
                        addOption("--cookies", cookieFile.absolutePath)
                    }
                    YoutubeDL.getInstance().execute(req, null, null).out
                }
            } else {
                val req = YoutubeDLRequest(url).apply {
                    addOption("--dump-single-json")
                    addOption("--ignore-no-formats-error")
                    addOption("--ignore-errors")
                }
                YoutubeDL.getInstance().execute(req, null, null).out
            }

            if (execOut.isNotBlank()) {
                val json = JSONObject(execOut)
                val urls = linkedSetOf<String>()
                val entries = json.optJSONArray("entries")
                if (entries != null) {
                    for (i in 0 until entries.length()) {
                        val obj = entries.optJSONObject(i) ?: continue
                        bestUncroppedImageFromJson(obj)?.let { urls.add(it) }
                    }
                }
                if (urls.isEmpty()) {
                    bestUncroppedImageFromJson(json)?.let { urls.add(it) }
                }
                DiagnosticLog.log("ImageFallback", "useCookies=$useIgCookies found=${urls.size}")
                if (urls.isNotEmpty()) {
                    val platform = json.optString("extractor_key", detectPlatformName(url))
                        .replace("Tab", "").uppercase(Locale.US)
                    val rawTitle = decodeHtml(json.optString("title").ifBlank { "Image post (${urls.size})" })
                    val id = json.optString("id").ifBlank { url.hashCode().toString() }
                    return MediaPreview(
                        mediaId = id,
                        url = url,
                        title = rawTitle,
                        author = decodeHtml(json.optString("uploader").ifBlank { "${urls.size} image${if (urls.size == 1) "" else "s"}" }),
                        platform = "$platform · IMAGE",
                        durationText = "${urls.size} image${if (urls.size == 1) "" else "s"}",
                        thumbnailUrl = urls.first(),
                        videoOptions = emptyList(),
                        audioOptions = emptyList(),
                        imageUrls = urls.toList(),
                        usedInstagramAuth = useIgCookies,
                        sanitizedTitle = sanitizeTitle(rawTitle, "${platform}_$id"),
                    )
                }
            }
        }

        return fetchWebMetaFallback(url)
    }

    private fun parseDashManifest(rawXml: String): ParsedDashManifest? =
        ZarditParser.parseDashManifest(rawXml)

    private fun fetchWebMetaFallback(url: String): MediaPreview? {
        val isThreads = detectPlatformName(url) == "Threads"
        val candidateTargets = buildList {
            if (isThreads) {
                add(url.replace("threads.net", "threads.com", ignoreCase = true) to "curl/8.5.0")
                add(url to "curl/8.5.0")
                add(url.replace("threads.net", "threads.com", ignoreCase = true) to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                if ("/post/" in url) {
                    val base = url.substringBefore("?").trimEnd('/')
                    add("$base/embed" to "curl/8.5.0")
                }
            } else {
                add(url to "curl/8.5.0")
                add(url to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                add(url to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            }
        }

        var firstImageFallback: MediaPreview? = null
        val videoVersionsBlockRegex = Regex(""""video_versions"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val dashManifestRegex = Regex(""""video_dash_manifest"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val urlInsideJsonRegex = Regex(""""url"\s*:\s*"(https?:[^"]+)"""")
        val mp4Regex = Regex("""(https?:[^"'\s<>]+\.mp4[^"'\s<>]*)""", RegexOption.IGNORE_CASE)

        for ((targetUrl, ua) in candidateTargets) {
            val preview = runCatching {
                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                val code = conn.responseCode
                val finalUrl = conn.url?.toString() ?: targetUrl
                val html = conn.inputStream.bufferedReader().use { it.readText().take(3 * 1024 * 1024) }
                conn.disconnect()
                DiagnosticLog.log("WebMeta", "ua=${ua.take(16)} target=${finalUrl.takeLast(24)} HTTP $code len=${html.length}")

                val meta = mutableMapOf<String, String>()
                ogTagRegex.findAll(html).forEach { m ->
                    val key = (m.groupValues[1].ifEmpty { m.groupValues[4] }).lowercase(Locale.US)
                    val valText = decodeHtml(m.groupValues[2].ifEmpty { m.groupValues[3] }).trim()
                    if (key.isNotEmpty() && valText.isNotEmpty() && key !in meta) {
                        meta[key] = valText
                    }
                }

                val platform = detectPlatformName(url).uppercase(Locale.US)
                val title = decodeHtml(meta["og:description"] ?: meta["og:title"] ?: meta["twitter:title"] ?: "$platform post")
                val imgUrl = meta["og:image:secure_url"] ?: meta["og:image"] ?: meta["twitter:image"]

                val postShortcode = Regex("""/post/([A-Za-z0-9_-]+)""").find(finalUrl)?.groupValues?.getOrNull(1)
                val windows = buildList {
                    if (postShortcode != null) {
                        Regex(""""code"\s*:\s*"${Regex.escape(postShortcode)}"""").findAll(html).forEach { m ->
                            val start = (m.range.first - 15_000).coerceAtLeast(0)
                            val end = (m.range.last + 15_000).coerceAtMost(html.length)
                            add(html.substring(start, end))
                        }
                    }
                    add(html)
                }

                // Prefer full multi-quality DASH manifest when Threads provides it
                for (win in windows) {
                    dashManifestRegex.findAll(win).forEach { dm ->
                        val xml = dm.groupValues[1].unescapeJsonUrl().replace("\\n", "\n")
                        val parsedDash = parseDashManifest(xml)
                        if (parsedDash != null && parsedDash.videoOptions.isNotEmpty()) {
                            return@runCatching MediaPreview(
                                mediaId = postShortcode ?: url.hashCode().toString(),
                                url = url,
                                title = title,
                                author = decodeHtml(meta["og:title"] ?: platform),
                                platform = "$platform · VIDEO",
                                durationText = "",
                                thumbnailUrl = imgUrl,
                                videoOptions = parsedDash.videoOptions,
                                audioOptions = if (parsedDash.audioUrl != null) {
                                    listOf(AudioOption("orig_m4a", "Best Audio", "M4A / AAC · Native quality", null, isOriginal = true, approxSizeBytes = parsedDash.audioBytes))
                                } else {
                                    emptyList()
                                },
                                directVideoUrl = parsedDash.videoOptions.first().directUrl,
                                directAudioUrl = parsedDash.audioUrl,
                                imageUrls = listOfNotNull(imgUrl),
                                sanitizedTitle = sanitizeTitle(title, platform),
                            )
                        }
                    }
                }

                val scopedHtml = windows.first()
                val videoVersionsUrl = videoVersionsBlockRegex.findAll(scopedHtml)
                    .mapNotNull { blockMatch ->
                        val block = blockMatch.groupValues[1]
                        if (block.contains("url")) {
                            urlInsideJsonRegex.find(block)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
                        } else null
                    }
                    .firstOrNull()

                val videoUrl = (meta["og:video:secure_url"] ?: meta["og:video"] ?: meta["twitter:player:stream"])
                    ?.takeIf { it.startsWith("http") }
                    ?: videoVersionsUrl
                    ?: Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""")
                        .find(scopedHtml)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
                    ?: Regex("""<(?:video|source)[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
                    ?: mp4Regex.findAll(scopedHtml)
                        .map { it.groupValues[1].unescapeJsonUrl() }
                        .firstOrNull { candidate ->
                            !candidate.contains("rsrc.php", ignoreCase = true) &&
                                candidate.startsWith("http")
                        }

                if (!videoUrl.isNullOrBlank()) {
                    val size = probeUrlSize(videoUrl)
                    val origW = Regex(""""original_width"\s*:\s*(\d+)""").find(scopedHtml)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val origH = Regex(""""original_height"\s*:\s*(\d+)""").find(scopedHtml)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val resTier = if (origW > 0 && origH > 0) minOf(origW, origH) else 0
                    val resLabel = if (resTier > 0) "${resTier}p" else "Original"
                    val resBadge = if (resTier > 0) qualityBadge(resTier).ifEmpty { "MP4" } else "MP4"
                    return@runCatching MediaPreview(
                        mediaId = postShortcode ?: url.hashCode().toString(),
                        url = url,
                        title = title,
                        author = decodeHtml(meta["og:title"] ?: platform),
                        platform = "$platform · VIDEO",
                        durationText = "",
                        thumbnailUrl = imgUrl,
                        videoOptions = listOf(
                            VideoOption(resTier, resLabel, resBadge, "MP4 · Direct stream", size),
                        ),
                        audioOptions = emptyList(),
                        imageUrls = listOfNotNull(imgUrl),
                        directVideoUrl = videoUrl,
                        sanitizedTitle = sanitizeTitle(title, platform),
                    )
                }

                if (!imgUrl.isNullOrBlank() && imgUrl.startsWith("http") &&
                    !ZarditParser.isBrandLogoOrLoginPage(title, imgUrl) &&
                    firstImageFallback == null
                ) {
                    firstImageFallback = MediaPreview(
                        mediaId = postShortcode ?: url.hashCode().toString(),
                        url = url,
                        title = title,
                        author = decodeHtml(meta["og:title"] ?: platform),
                        platform = "$platform · IMAGE",
                        durationText = "1 image",
                        thumbnailUrl = imgUrl,
                        videoOptions = emptyList(),
                        audioOptions = emptyList(),
                        imageUrls = listOf(imgUrl),
                        sanitizedTitle = sanitizeTitle(title, platform),
                    )
                }
                null
            }.onFailure { err ->
                DiagnosticLog.log("WebMetaErr", err.message ?: err::class.java.simpleName)
            }.getOrNull()

            if (preview != null) return preview
        }
        return firstImageFallback
    }

    private fun fetchWithFacebookCookies(context: Context, url: String): MediaPreview? = runCatching {
        val jsonStr = FacebookAuth.withTempCookieFile(context) { cookieFile ->
            val req = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--ignore-no-formats-error")
                addOption("--cookies", cookieFile.absolutePath)
            }
            YoutubeDL.getInstance().execute(req, null, null).out
        }
        if (jsonStr.isBlank()) return@runCatching null
        val json = JSONObject(jsonStr)
        val formatsArr = json.optJSONArray("formats")
        val durationSec = json.optInt("duration", 0)
        val rawTitle = decodeHtml(json.optString("title").ifBlank { "Facebook Video" })
        val id = json.optString("id").ifBlank { url.hashCode().toString() }
        val uploader = decodeHtml(json.optString("uploader").ifBlank { "Facebook" })
        val thumb = bestUncroppedImageFromJson(json)

        var bestAudioSize = 0L
        val videoOpts = buildList {
            val buckets = listOf(1440, 1080, 720, 480, 360)
            val bestByBucket = mutableMapOf<Int, VideoOption>()
            if (formatsArr != null) {
                for (i in 0 until formatsArr.length()) {
                    val f = formatsArr.optJSONObject(i) ?: continue
                    if (f.optString("vcodec") == "none" && f.optString("acodec") != "none") {
                        val s = f.optLong("filesize", 0L).takeIf { it > 0L }
                            ?: f.optLong("filesize_approx", 0L).takeIf { it > 0L }
                            ?: f.optString("url").takeIf { it.startsWith("http") }?.let { probeUrlSize(it) }
                            ?: 0L
                        if (s > bestAudioSize) bestAudioSize = s
                    }
                }
                for (i in 0 until formatsArr.length()) {
                    val f = formatsArr.optJSONObject(i) ?: continue
                    val ext = f.optString("ext").lowercase(Locale.US)
                    val vc = f.optString("vcodec")
                    if (ext in imageExts || ext == "mhtml" || vc == "none" || vc == "images") continue
                    if (ext !in videoContainers && vc.isBlank()) continue
                    val note = f.optString("format_note")
                    val fid = f.optString("format_id")
                    val fidLow = fid.lowercase(Locale.US)
                    val tier = classifyVideoTier(f.optInt("width", 0), f.optInt("height", 0), note, buckets)
                        ?: when {
                            fidLow in setOf("hd", "dash_hd") || "hd" in note.lowercase(Locale.US) -> 720
                            fidLow in setOf("sd", "dash_sd") || "sd" in note.lowercase(Locale.US) -> 480
                            else -> 0
                        }
                    val needsMux = f.optString("acodec") == "none"
                    val fmtUrl = f.optString("url").takeIf { it.startsWith("http") }
                    val rawSize = f.optLong("filesize", 0L).takeIf { it > 0L }
                        ?: f.optLong("filesize_approx", 0L).takeIf { it > 0L }
                        ?: fmtUrl?.let { probeUrlSize(it) }
                    val totalSize = rawSize?.let { it + (if (needsMux) bestAudioSize else 0L) }
                    val label = if (tier > 0) "${tier}p" else "Original"
                    val badge = if (tier > 0) qualityBadge(tier).ifEmpty { "MP4" } else "MP4"
                    bestByBucket.putIfAbsent(
                        tier,
                        VideoOption(tier, label, badge, "MP4 · Facebook", totalSize, formatId = fid.ifBlank { null }, needsAudioMux = needsMux),
                    )
                }
            }
            addAll(bestByBucket.values.sortedByDescending { it.height })
        }
        if (videoOpts.isEmpty()) return@runCatching null

        MediaPreview(
            mediaId = id,
            url = url,
            title = rawTitle,
            author = uploader,
            platform = "FACEBOOK · VIDEO",
            durationText = formatDuration(durationSec),
            thumbnailUrl = thumb,
            videoOptions = videoOpts,
            audioOptions = defaultAudioOptions.map { opt ->
                val est = if (durationSec > 0) (durationSec.toLong() * (opt.bitrateKbps ?: 128).toLong() * 1000L) / 8L else null
                opt.copy(approxSizeBytes = est)
            },
            imageUrls = listOfNotNull(thumb),
            usedInstagramAuth = true,
            sanitizedTitle = sanitizeTitle(rawTitle, "Facebook_$id"),
        )
    }.getOrNull()

    private fun fetchFacebookHtmlFallback(context: Context, url: String, useFbCookies: Boolean): MediaPreview? {
        val cookieHeader = if (useFbCookies) FacebookAuth.getCookieHeader(context) else null
        val profiles = buildList {
            if (cookieHeader != null) {
                add("Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0" to cookieHeader)
                add("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36" to cookieHeader)
            }
            add("Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0" to null)
            add("curl/8.5.0" to null)
            add("facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)" to null)
        }

        val prevCookieHandler = java.net.CookieHandler.getDefault()
        val tempCookieManager = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        try {
            java.net.CookieHandler.setDefault(tempCookieManager)
            for ((ua, ck) in profiles) {
                val parsed = runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 9000
                        readTimeout = 9000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", ua)
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                        if (ua.contains("Firefox") || ua.contains("Chrome")) {
                            setRequestProperty("Sec-Fetch-Mode", "navigate")
                        }
                        if (ck != null) {
                            setRequestProperty("Cookie", ck)
                        }
                    }
                    val finalUrl = conn.url?.toString() ?: url
                    if (!ZarditParser.isSafePublicUrl(finalUrl)) {
                        conn.disconnect()
                        return@runCatching null
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText().take(4 * 1024 * 1024) }
                    conn.disconnect()
                    DiagnosticLog.log("FBHtml", "ua=${ua.take(12)} auth=${ck != null} len=${body.length}")
                    ZarditParser.parseFacebookHtml(body, finalUrl)
                }.onFailure { err ->
                    DiagnosticLog.log("FBHtmlErr", err.message ?: err::class.java.simpleName)
                }.getOrNull() ?: continue

                if (parsed.videoOptions.isNotEmpty() && parsed.directVideoUrl != null) {
                    val size = parsed.videoOptions.firstOrNull()?.approxSizeBytes ?: probeUrlSize(parsed.directVideoUrl)
                    val opts = parsed.videoOptions.mapIndexed { i, opt ->
                        if (i == 0 && opt.approxSizeBytes == null) opt.copy(approxSizeBytes = size) else opt
                    }
                    return MediaPreview(
                        mediaId = parsed.mediaId,
                        url = url,
                        title = parsed.title,
                        author = parsed.author,
                        platform = "FACEBOOK · VIDEO",
                        durationText = "",
                        thumbnailUrl = parsed.thumbnailUrl,
                        videoOptions = opts,
                        audioOptions = if (parsed.directAudioUrl != null) {
                            listOf(AudioOption("orig_m4a", "Best Audio", "M4A / AAC · Native quality", null, isOriginal = true, approxSizeBytes = parsed.audioBytes))
                        } else {
                            defaultAudioOptions.map { it.copy(approxSizeBytes = size?.let { s -> (s / 5L).coerceAtLeast(80_000L) }) }
                        },
                        directVideoUrl = parsed.directVideoUrl,
                        directAudioUrl = parsed.directAudioUrl,
                        imageUrls = parsed.imageUrls,
                        usedInstagramAuth = useFbCookies,
                        sanitizedTitle = sanitizeTitle(parsed.title, "Facebook_${parsed.mediaId}"),
                    )
                }

                if (parsed.imageUrls.isNotEmpty()) {
                    return MediaPreview(
                        mediaId = parsed.mediaId,
                        url = url,
                        title = parsed.title,
                        author = "${parsed.author} · ${parsed.imageUrls.size} image${if (parsed.imageUrls.size == 1) "" else "s"}",
                        platform = "FACEBOOK · IMAGE",
                        durationText = "${parsed.imageUrls.size} image${if (parsed.imageUrls.size == 1) "" else "s"}",
                        thumbnailUrl = parsed.imageUrls.first(),
                        videoOptions = emptyList(),
                        audioOptions = emptyList(),
                        imageUrls = parsed.imageUrls,
                        usedInstagramAuth = useFbCookies,
                        sanitizedTitle = sanitizeTitle(parsed.title, "Facebook_${parsed.mediaId}"),
                    )
                }
            }
            return null
        } finally {
            java.net.CookieHandler.setDefault(prevCookieHandler)
            tempCookieManager.cookieStore.removeAll()
        }
    }

    private fun detectPlatformName(url: String): String =
        ZarditParser.detectPlatformName(url)

    private fun friendlyError(raw: String, isInstagram: Boolean = false, isFacebook: Boolean = false): String {
        val lower = raw.lowercase(Locale.US)
        return when {
            "empty media response" in lower || "login" in lower || "cookies" in lower || "certain audiences" in lower ||
                (isFacebook && ("no video found" in lower || "http error 404" in lower || "http error 400" in lower)) ->
                when {
                    isInstagram -> "This Instagram post requires sign-in or is restricted. Tap \"IG/FB\" at the top to connect your account locally, or retry later."
                    isFacebook -> "This Facebook post may be private or restricted. Tap \"IG/FB\" at the top to connect Facebook locally, or retry later."
                    else -> "This post requires login or is temporarily rate-limited by the platform. Try updating the Engine or retry later."
                }
            "no video formats found" in lower || "no video in this post" in lower ->
                "No downloadable video or image was found at this link."
            else -> {
                raw.lineSequence()
                    .firstOrNull { line ->
                        val trimmed = line.trim()
                        trimmed.isNotBlank() &&
                            !trimmed.startsWith("WARNING:", ignoreCase = true) &&
                            !trimmed.contains("github.com/yt-dlp") &&
                            !trimmed.contains("yt-dlp -U")
                    }
                    ?.removePrefix("ERROR:")
                    ?.trim()
                    ?: "Could not fetch media info."
            }
        }
    }

    private fun fetchPlaylistPreview(url: String): MediaPreview? = runCatching {
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
        }
        val response = YoutubeDL.getInstance().execute(request, null, null)
        val json = JSONObject(response.out)
        val entriesArr = json.optJSONArray("entries") ?: return@runCatching null
        if (entriesArr.length() == 0) return@runCatching null

        val entries = buildList {
            for (i in 0 until entriesArr.length()) {
                val obj = entriesArr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { "${i + 1}" }
                val entryUrl = obj.optString("url").ifBlank { obj.optString("webpage_url") }
                    .let { raw ->
                        if (raw.startsWith("http")) raw else "https://www.youtube.com/watch?v=$id"
                    }
                val thumb = bestUncroppedImageFromJson(obj)
                add(
                    PlaylistEntry(
                        index = i + 1,
                        id = id,
                        title = decodeHtml(obj.optString("title").ifBlank { "Item ${i + 1}" }),
                        url = entryUrl,
                        durationText = formatDuration(obj.optInt("duration", 0)),
                        thumbnailUrl = thumb,
                    ),
                )
            }
        }
        if (entries.isEmpty()) return@runCatching null

        val platform = json.optString("extractor_key", "PLAYLIST")
            .replace("Tab", "")
            .uppercase(Locale.US)
        val plTitle = decodeHtml(json.optString("title").ifBlank { "Playlist (${entries.size} items)" })
        MediaPreview(
            mediaId = json.optString("id").ifBlank { url.hashCode().toString() },
            url = url,
            title = plTitle,
            author = decodeHtml(json.optString("uploader").ifBlank { "${entries.size} items" }),
            platform = "$platform · PLAYLIST",
            durationText = "${entries.size} items",
            thumbnailUrl = entries.firstOrNull()?.thumbnailUrl,
            videoOptions = listOf(
                VideoOption(1080, "1080p", "Full HD", "MP4 · Native mux", null),
                VideoOption(720, "720p", "HD", "MP4 · H.264", null),
                VideoOption(480, "480p", "SD", "MP4 · H.264", null),
                VideoOption(360, "360p", "Low", "MP4 · H.264", null),
            ),
            audioOptions = defaultAudioOptions,
            isPlaylist = true,
            playlistEntries = entries,
            sanitizedTitle = sanitizeTitle(plTitle, platform),
        )
    }.getOrNull()

    fun parseRangeSelection(input: String, total: Int): Set<Int> =
        ZarditParser.parseRangeSelection(input, total)

    private fun probeUrlSize(url: String): Long? {
        if (!ZarditParser.isSafePublicUrl(url)) return null
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            try {
                val len = conn.contentLengthLong
                if (len > 0L) return len
            } finally {
                conn.disconnect()
            }
        }
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                val contentRange = conn.getHeaderField("Content-Range") ?: return@runCatching null
                val total = contentRange.substringAfterLast("/").trim().toLongOrNull()
                if (total != null && total > 0L) total else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    fun qualityTag(
        mode: DownloadMode,
        videoHeight: Int?,
        audioOption: AudioOption?,
        imageFormat: ImageFormat = ImageFormat.ORIGINAL,
    ): String = when (mode) {
        DownloadMode.VIDEO -> if (videoHeight != null && videoHeight > 0) "${videoHeight}p" else "original"
        DownloadMode.AUDIO -> when {
            audioOption?.requiresFfmpeg == true && audioOption.bitrateKbps != null -> "${audioOption.bitrateKbps}kbps"
            audioOption?.isOriginal == true -> "original"
            else -> "compact"
        }
        DownloadMode.IMAGE -> imageFormat.id
    }

    fun targetExtension(
        mode: DownloadMode,
        audioOption: AudioOption?,
        imageFormat: ImageFormat = ImageFormat.ORIGINAL,
    ): String = when (mode) {
        DownloadMode.VIDEO -> "mp4"
        DownloadMode.AUDIO -> audioOption?.ext ?: "m4a"
        DownloadMode.IMAGE -> imageFormat.ext
    }

    fun findExistingDownload(
        context: Context,
        mediaId: String,
        qualityTag: String,
        extension: String,
        treeUri: Uri?,
    ): String? {
        if (mediaId.isBlank()) return null
        val shortPrefix = if (mediaId.length >= 11) mediaId.take(11) else mediaId
        val qualityMarker = "[$qualityTag]"

        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            val match = dir?.listFiles()?.firstOrNull { file ->
                val name = file.name ?: return@firstOrNull false
                file.isFile && ZarditParser.matchesExistingDownloadName(name, mediaId, qualityTag, extension)
            }
            if (match != null) {
                return "${dir.name ?: "Custom folder"}/${match.name}"
            }
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%Zardit%", "%$qualityMarker%[$shortPrefix%.$extension")
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        return "Downloads/Zardit/$name"
                    }
                }
            }
            return null
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Zardit",
            )
            val match = dir.listFiles()?.firstOrNull { file ->
                file.isFile && ZarditParser.matchesExistingDownloadName(file.name, mediaId, qualityTag, extension)
            }
            return match?.let { "Downloads/Zardit/${it.name}" }
        }
    }

    private fun prepareCoverArtFile(workDir: File, coverUrl: String?): File? {
        if (coverUrl.isNullOrBlank() || !ZarditParser.isSafePublicUrl(coverUrl)) return null
        val target = File(workDir, "cover_art.jpg")
        return runCatching {
            val conn = (URL(coverUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size in 128..(8 * 1024 * 1024)) {
                        target.writeBytes(bytes)
                        return@runCatching target
                    }
                }
            } finally {
                conn.disconnect()
            }
            null
        }.getOrNull()
    }

    private fun downloadDirectHttp(
        fileUrl: String,
        destFile: File,
        progressStart: Float = 5f,
        progressSpan: Float = 90f,
        onProgress: (Float, String) -> Unit,
    ) {
        require(ZarditParser.isSafePublicUrl(fileUrl)) { "Unsafe download URL refused." }
        val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            val lower = fileUrl.lowercase(Locale.US)
            if ("instagram" in lower || "cdninstagram" in lower) {
                setRequestProperty("Referer", "https://www.instagram.com/")
            } else if ("facebook" in lower || "fbcdn" in lower) {
                setRequestProperty("Referer", "https://www.facebook.com/")
            }
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            destFile.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    copied += read
                    if (total > 0L) {
                        val pct = (progressStart + (copied.toFloat() / total) * progressSpan).coerceIn(0f, 99f)
                        onProgress(pct, ZarditFlavor.formatProgressLine("", pct))
                    }
                }
            }
        }
        conn.disconnect()
    }

    fun download(
        context: Context,
        url: String,
        mode: DownloadMode,
        videoHeight: Int?,
        audioOption: AudioOption?,
        treeUri: Uri?,
        processId: String,
        directVideoUrl: String? = null,
        directAudioUrl: String? = null,
        useInstagramCookies: Boolean = false,
        customTitle: String? = null,
        formatId: String? = null,
        needsAudioMux: Boolean = false,
        resumeDir: File? = null,
        shouldKeepWorkDirOnExit: () -> Boolean = { false },
        coverUrl: String? = null,
        artistName: String? = null,
        onProgress: (Float, String) -> Unit,
    ): SaveResult {
        val qTag = qualityTag(mode, videoHeight, audioOption)
        val workDir = (resumeDir ?: File(context.cacheDir, "zardit_dl_$processId")).apply {
            if (resumeDir == null) {
                deleteRecursively()
            }
            mkdirs()
        }

        fun muxOrFallback(vidFile: File, audFile: File, outFile: File) {
            try {
                NativeMuxer.muxMp4(vidFile, audFile, outFile)
            } catch (muxErr: Throwable) {
                if (FfmpegAddonManager.isInstalled(context) &&
                    FfmpegAddonManager.muxToMp4(context, vidFile, audFile, outFile)
                ) {
                    return
                }
                throw muxErr
            }
        }

        try {
            val safePrefix = customTitle?.takeIf { it.isNotBlank() }?.let { sanitizeTitle(it) }
            val mediaId = Regex("""(?:v=|youtu\.be/|shorts/|/reel/|/p/|/tv/|/post/)([A-Za-z0-9_-]{6,})""")
                .find(url)?.groupValues?.getOrNull(1) ?: url.hashCode().toString()

            if (mode == DownloadMode.AUDIO && !directAudioUrl.isNullOrBlank()) {
                onProgress(15f, "15% · Awakening Charizard…")
                val wantMp3 = audioOption?.requiresFfmpeg == true && FfmpegAddonManager.isInstalled(context)
                val srcFile = File(workDir, "${safePrefix ?: "Audio"} [$qTag] [$mediaId].m4a")
                downloadDirectHttp(directAudioUrl, srcFile, 10f, if (wantMp3) 70f else 85f, onProgress)
                if (wantMp3) {
                    onProgress(85f, "85% · Converting to MP3 (FFmpeg)…")
                    val coverFile = prepareCoverArtFile(workDir, coverUrl)
                    val mp3Out = File(workDir, "${safePrefix ?: "Audio"} [$qTag] [$mediaId].mp3")
                    if (FfmpegAddonManager.convertAudioToMp3(
                            context = context,
                            inputFile = srcFile,
                            outputMp3 = mp3Out,
                            bitrateKbps = audioOption?.bitrateKbps ?: 192,
                            coverFile = coverFile,
                            title = customTitle ?: safePrefix,
                            artist = artistName,
                        )
                    ) {
                        return saveOutputFile(context, mp3Out, mode, treeUri)
                    }
                }
                return saveOutputFile(context, srcFile, mode, treeUri)
            }

            if (!directVideoUrl.isNullOrBlank() && mode == DownloadMode.VIDEO) {
                onProgress(15f, "15% · Awakening Charizard…")
                val outFile = File(workDir, "${safePrefix ?: "Video"} [$qTag] [$mediaId].mp4")
                if (needsAudioMux && !directAudioUrl.isNullOrBlank()) {
                    val vidTemp = File(workDir, "direct_video.mp4")
                    val audTemp = File(workDir, "direct_audio.m4a")
                    downloadDirectHttp(directVideoUrl, vidTemp, 5f, 70f, onProgress)
                    downloadDirectHttp(directAudioUrl, audTemp, 75f, 18f, onProgress)
                    onProgress(95f, ZarditFlavor.formatProgressLine("ffmpeg", 95f))
                    muxOrFallback(vidTemp, audTemp, outFile)
                } else {
                    downloadDirectHttp(directVideoUrl, outFile, 5f, 92f, onProgress)
                }
                return saveOutputFile(context, outFile, mode, treeUri)
            }

            val titleTemplate = if (!safePrefix.isNullOrBlank()) safePrefix else "%(title).65B"
            val progressCallback: (Float, Long, String) -> Unit = { progress, _, line ->
                val pct = if (progress >= 0f) progress.coerceIn(0f, 100f) else 0f
                val friendly = ZarditFlavor.formatProgressLine(line, pct)
                onProgress(pct, friendly)
            }

            fun runRequest(req: YoutubeDLRequest) {
                req.addOption("-c")
                val platform = detectPlatformName(url)
                if (platform == "Facebook" && FacebookAuth.hasSession(context)) {
                    FacebookAuth.withTempCookieFile(context) { cookieFile ->
                        req.addOption("--cookies", cookieFile.absolutePath)
                        YoutubeDL.getInstance().execute(req, processId, progressCallback)
                    }
                } else if (useInstagramCookies && InstagramAuth.hasSession(context)) {
                    InstagramAuth.withTempCookieFile(context) { cookieFile ->
                        req.addOption("--cookies", cookieFile.absolutePath)
                        YoutubeDL.getInstance().execute(req, processId, progressCallback)
                    }
                } else {
                    YoutubeDL.getInstance().execute(req, processId, progressCallback)
                }
            }

            if (mode == DownloadMode.AUDIO) {
                val wantMp3 = audioOption?.requiresFfmpeg == true && FfmpegAddonManager.isInstalled(context)
                val formatSpec = if (audioOption?.isOriginal == true || wantMp3) {
                    "bestaudio[ext=m4a]/bestaudio"
                } else {
                    "bestaudio[abr<=140][ext=m4a]/bestaudio[ext=m4a]/bestaudio"
                }
                val req = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("-f", formatSpec)
                    addOption("-o", "${workDir.absolutePath}/$titleTemplate [$qTag] [%(id)s].%(ext)s")
                }
                runRequest(req)
                val outputFile = workDir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                    ?: error("Download finished, but no output file was found.")
                if (wantMp3) {
                    onProgress(88f, "88% · Converting to MP3 (FFmpeg)…")
                    val coverFile = prepareCoverArtFile(workDir, coverUrl)
                    val mp3Out = File(workDir, "${outputFile.nameWithoutExtension}.mp3")
                    if (FfmpegAddonManager.convertAudioToMp3(
                            context = context,
                            inputFile = outputFile,
                            outputMp3 = mp3Out,
                            bitrateKbps = audioOption?.bitrateKbps ?: 192,
                            coverFile = coverFile,
                            title = customTitle ?: safePrefix,
                            artist = artistName,
                        )
                    ) {
                        return saveOutputFile(context, mp3Out, mode, treeUri)
                    }
                }
                return saveOutputFile(context, outputFile, mode, treeUri)
            }

            // Video mode (no FFmpeg):
            val h = videoHeight ?: 1080

            // 1. Exact formatId selected (already muxed -> download directly; video-only -> download + MediaMuxer)
            if (!formatId.isNullOrBlank() && !needsAudioMux) {
                val exactReq = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("-f", formatId)
                    addOption("-o", "${workDir.absolutePath}/$titleTemplate [$qTag] [%(id)s].%(ext)s")
                }
                runRequest(exactReq)
                val out = workDir.listFiles()
                    ?.filter { it.isFile && it.length() > 1024L && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                if (out != null) return saveOutputFile(context, out, mode, treeUri)
            }

            if (needsAudioMux || (formatId.isNullOrBlank() && h > 720)) {
                val vidTemp = File(workDir, "video_only.mp4")
                val audTemp = File(workDir, "audio_only.m4a")
                val muxedResult = runCatching {
                    val vidReq = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--no-mtime")
                        if (!formatId.isNullOrBlank()) {
                            addOption("-f", formatId)
                        } else {
                            addOption("-f", "bestvideo[ext=mp4]/bestvideo")
                            addOption("-S", "res:$h,proto,vcodec:h264")
                        }
                        addOption("-o", vidTemp.absolutePath)
                    }
                    runRequest(vidReq)

                    val audReq = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--no-mtime")
                        addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                        addOption("-o", audTemp.absolutePath)
                    }
                    runRequest(audReq)

                    if (vidTemp.isFile && vidTemp.length() > 1024L && audTemp.isFile && audTemp.length() > 1024L) {
                        onProgress(95f, ZarditFlavor.formatProgressLine("ffmpeg", 95f))
                        val id = Regex("""(?:v=|youtu\.be/|shorts/|reel/|p/)([A-Za-z0-9_-]{6,})""")
                            .find(url)?.groupValues?.getOrNull(1) ?: url.hashCode().toString()
                        val finalName = "${safePrefix ?: "Video"} [$qTag] [$id].mp4"
                        val muxedOut = File(workDir, finalName)
                        muxOrFallback(vidTemp, audTemp, muxedOut)
                        saveOutputFile(context, muxedOut, mode, treeUri)
                    } else {
                        null
                    }
                }.onFailure { err ->
                    DiagnosticLog.log("MuxErr", err.message ?: err::class.java.simpleName)
                }.getOrNull()
                if (muxedResult != null) return muxedResult
                vidTemp.delete()
                audTemp.delete()
            }

            // Single pre-muxed stream fallback using res:<height> sort (works for Shorts, ultrawide, and normal 16:9)
            val singleReq = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-f", "best[ext=mp4]/best")
                if (h > 0) {
                    addOption("-S", "res:$h,proto,vcodec:h264")
                }
                addOption("-o", "${workDir.absolutePath}/$titleTemplate [$qTag] [%(id)s].%(ext)s")
            }
            runRequest(singleReq)

            val outputFile = workDir.listFiles()
                ?.filter { it.isFile && it.length() > 1024L && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: error("Download finished, but the output file was empty or missing.")

            return saveOutputFile(context, outputFile, mode, treeUri)
        } finally {
            if (!shouldKeepWorkDirOnExit()) {
                workDir.deleteRecursively()
            }
        }
    }

    fun deleteSavedFile(context: Context, contentUriStr: String?): Boolean {
        if (contentUriStr.isNullOrBlank()) return false
        return runCatching {
            val uri = Uri.parse(contentUriStr)
            when (uri.scheme) {
                "file" -> File(uri.path ?: return false).delete()
                "content" -> {
                    val deletedRows = runCatching {
                        context.contentResolver.delete(uri, null, null)
                    }.getOrDefault(0)
                    if (deletedRows > 0) {
                        true
                    } else {
                        DocumentFile.fromSingleUri(context, uri)?.delete() == true
                    }
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    fun downloadImage(
        context: Context,
        imageUrl: String,
        title: String,
        mediaId: String,
        format: ImageFormat,
        treeUri: Uri?,
        onProgress: (Float, String) -> Unit,
    ): SaveResult {
        require(ZarditParser.isSafePublicUrl(imageUrl)) { "Unsafe image URL refused." }
        onProgress(15f, "15% · Using Flash on the image…")
        val rawBytes = (URL(imageUrl).openConnection() as HttpURLConnection).run {
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            val lower = imageUrl.lowercase(Locale.US)
            if ("instagram" in lower || "cdninstagram" in lower || "fbcdn" in lower) {
                setRequestProperty("Referer", "https://www.instagram.com/")
            }
            inputStream.use { it.readBytes() }.also { disconnect() }
        }
        onProgress(70f, "70% · Evolving image format…")

        val safeTitle = sanitizeTitle(title, fallback = "image")
        val fileName = "$safeTitle [${format.id}] [$mediaId].${format.ext}"
        val tempFile = File(context.cacheDir, fileName)

        try {
            if (format == ImageFormat.ORIGINAL) {
                tempFile.writeBytes(rawBytes)
            } else {
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    ?: error("Could not decode image.")
                val compressFormat = when (format) {
                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                    ImageFormat.JPG -> Bitmap.CompressFormat.JPEG
                    ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    ImageFormat.ORIGINAL -> Bitmap.CompressFormat.JPEG
                }
                tempFile.outputStream().use { out ->
                    bmp.compress(compressFormat, 92, out)
                }
                bmp.recycle()
            }
            onProgress(95f, "95% · Catching into Downloads…")
            return saveOutputFile(context, tempFile, DownloadMode.IMAGE, treeUri)
        } finally {
            tempFile.delete()
        }
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    fun updateYtDlp(context: Context): String {
        val status = YoutubeDL.getInstance().updateYoutubeDL(
            context,
            YoutubeDL.UpdateChannel.STABLE,
        )
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".zip") || f.name.endsWith(".tmp")) {
                runCatching { f.delete() }
            }
        }
        val ver = runCatching { YoutubeDL.getInstance().version(context) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val verSuffix = if (ver != null) " (v$ver)" else ""
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "Engine leveled up!$verSuffix"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Engine is already at max level!$verSuffix"
            else -> "Engine update completed.$verSuffix"
        }
    }

    private fun saveOutputFile(
        context: Context,
        sourceFile: File,
        mode: DownloadMode,
        treeUri: Uri?,
    ): SaveResult {
        val mimeType = when (sourceFile.extension.lowercase(Locale.US)) {
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> when (mode) {
                DownloadMode.AUDIO -> "audio/*"
                DownloadMode.IMAGE -> "image/*"
                DownloadMode.VIDEO -> "video/*"
            }
        }

        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Selected folder is no longer accessible.")
            val created = dir.createFile(mimeType, sourceFile.name)
                ?: error("Could not create file in ${dir.name ?: "selected folder"}.")
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Could not write to selected folder.")
            val folderName = dir.name?.ifBlank { null } ?: "Custom folder"
            return SaveResult(
                fileName = sourceFile.name,
                savedLocation = "$folderName/${sourceFile.name}",
                contentUri = created.uri.toString(),
                fileSizeBytes = sourceFile.length().takeIf { it > 0L },
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Zardit",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads/Zardit.")
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Could not write to Downloads/Zardit.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SaveResult(
                fileName = sourceFile.name,
                savedLocation = "Downloads/Zardit/${sourceFile.name}",
                contentUri = uri.toString(),
                fileSizeBytes = sourceFile.length().takeIf { it > 0L },
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Zardit",
            ).apply { mkdirs() }
            val dest = File(dir, sourceFile.name)
            sourceFile.copyTo(dest, overwrite = true)
            return SaveResult(
                fileName = sourceFile.name,
                savedLocation = "Downloads/Zardit/${sourceFile.name}",
                contentUri = Uri.fromFile(dest).toString(),
                fileSizeBytes = sourceFile.length().takeIf { it > 0L },
            )
        }
    }

    fun cacheHistoryThumbnail(
        context: Context,
        historyId: String,
        thumbnailUrl: String?,
    ): String? {
        if (thumbnailUrl.isNullOrBlank() || !ZarditParser.isSafePublicUrl(thumbnailUrl)) return null
        val safeName = historyId.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(80)
        val outFile = File(HistoryStore.thumbnailsDir(context), "$safeName.jpg")
        return runCatching {
            val conn = (URL(thumbnailUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                val lower = thumbnailUrl.lowercase(Locale.US)
                if ("instagram" in lower || "cdninstagram" in lower) {
                    setRequestProperty("Referer", "https://www.instagram.com/")
                } else if ("facebook" in lower || "fbcdn" in lower) {
                    setRequestProperty("Referer", "https://www.facebook.com/")
                }
            }
            val raw = try {
                val buf = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > 3 * 1024 * 1024) break
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            } finally {
                conn.disconnect()
            }
            if (raw.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val sample = generateSequence(1) { it * 2 }
                .takeWhile { (maxDim / it) > 480 }
                .lastOrNull() ?: 1
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
            try {
                val scale = minOf(320f / bmp.width.coerceAtLeast(1), 320f / bmp.height.coerceAtLeast(1), 1f)
                val targetBmp = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    bmp
                }
                try {
                    outFile.outputStream().use { stream ->
                        targetBmp.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                    }
                } finally {
                    if (targetBmp !== bmp) targetBmp.recycle()
                }
            } finally {
                bmp.recycle()
            }
            outFile.absolutePath.takeIf { outFile.isFile && outFile.length() > 0L }
        }.getOrNull()
    }

    private fun qualityBadge(height: Int): String = ZarditParser.qualityBadge(height)

    fun formatFileSize(bytes: Long?): String = ZarditParser.formatFileSize(bytes)

    private fun formatDuration(seconds: Int): String = ZarditParser.formatDuration(seconds)
}
