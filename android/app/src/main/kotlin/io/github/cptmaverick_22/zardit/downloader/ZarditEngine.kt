package io.github.cptmaverick_22.zardit.downloader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.cptmaverick_22.zardit.data.InstagramAuth
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object ZarditEngine {

    val defaultAudioOptions = listOf(
        AudioOption("orig_m4a", "Best Audio", "M4A / AAC · Native quality", null, isOriginal = true),
        AudioOption("compact_m4a", "Compact Audio", "M4A · Smaller file", 128, isOriginal = false),
    )

    private val imageExts = setOf("jpg", "jpeg", "png", "webp")
    private val croppedUrlRegex = Regex(
        """(?:/[sp]\d+x\d+/|/c\d+\.\d+\.\d+\.\d+|dst-jpg_e\d+_[sp]|sqp=|profile_pic)""",
        RegexOption.IGNORE_CASE,
    )
    private val ogTagRegex = Regex(
        """<meta\s+(?:property|name)=["']([^"']+)["']\s+content=["']([^"']+)["']|<meta\s+content=["']([^"']+)["']\s+(?:property|name)=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("/playlist?") ||
            (lower.contains("list=") && !lower.contains("watch?v=") && !lower.contains("youtu.be/")) ||
            lower.contains("/sets/")
    }

    fun fetchPreview(context: Context, url: String): MediaPreview {
        val rawUrl = url.trim()
        require(rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            "Enter a valid http/https link."
        }
        val cleanUrl = normalizeAndResolveUrl(rawUrl)

        if (isPlaylistUrl(cleanUrl)) {
            fetchPlaylistPreview(cleanUrl)?.let { return it }
        }

        val info = try {
            YoutubeDL.getInstance().getInfo(cleanUrl)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            val isIg = detectPlatformName(cleanUrl) == "Instagram"
            val isThreads = detectPlatformName(cleanUrl) == "Threads"
            val isNoVideo = msg.contains("No video formats found", ignoreCase = true) ||
                msg.contains("no video in this post", ignoreCase = true)

            if (isIg) {
                if (InstagramAuth.hasSession(context)) {
                    fetchInstagramApiWithCookies(context, cleanUrl)?.let { return it }
                }
                if (isNoVideo) {
                    fetchImageFallback(context, cleanUrl, useIgCookies = false)?.let { return it }
                    fetchInstagramEmbedFallback(cleanUrl)?.let { return it }
                } else {
                    fetchInstagramEmbedFallback(cleanUrl)?.let { return it }
                }
                if (InstagramAuth.hasSession(context)) {
                    fetchWithInstagramCookies(context, cleanUrl)?.let { return it }
                }
            } else if (isNoVideo) {
                fetchImageFallback(context, cleanUrl, useIgCookies = false)?.let { return it }
            }

            if (isThreads || msg.contains("generic", ignoreCase = true)) {
                fetchWebMetaFallback(cleanUrl)?.let { return it }
            }

            throw IllegalStateException(friendlyError(msg, isIg))
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
                .lastOrNull { f -> f.vcodec == "none" && f.acodec != "none" && !f.url.isNullOrBlank() }
                ?.url
            if (!audioUrl.isNullOrBlank()) {
                bestAudioSize = probeUrlSize(audioUrl) ?: 0L
            }
        }

        val heightBuckets = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        var videoOptions = heightBuckets.mapNotNull { h ->
            val matching = formats.filter { f ->
                val effectiveHeight = effectiveVideoHeight(f.width, f.height)
                effectiveHeight in (h - 20)..(h + 20) && f.vcodec != "none"
            }
            if (matching.isEmpty()) return@mapNotNull null

            val bestVideo = matching.maxByOrNull {
                it.fileSize.coerceAtLeast(it.fileSizeApproximate)
            }
            var videoBytes = bestVideo?.let { it.fileSize.coerceAtLeast(it.fileSizeApproximate) } ?: 0L
            if (videoBytes <= 0L && bestVideo != null) {
                if (durationSec > 0 && bestVideo.tbr > 0) {
                    videoBytes = (durationSec * bestVideo.tbr * 1000.0 / 8.0).toLong()
                } else if (!bestVideo.url.isNullOrBlank()) {
                    videoBytes = probeUrlSize(bestVideo.url!!) ?: 0L
                }
            }
            val needsAudioAdd = bestVideo?.acodec == "none"
            val approxTotal = if (videoBytes > 0L) {
                videoBytes + (if (needsAudioAdd) bestAudioSize else 0L)
            } else {
                null
            }
            val codecHint = if (h >= 1440) "MP4 · AV1/H.264" else "MP4 · H.264"
            VideoOption(
                height = h,
                label = "${h}p",
                badge = qualityBadge(h),
                codecHint = codecHint,
                approxSizeBytes = approxTotal,
            )
        }

        val isPureImage = (info.ext?.lowercase(Locale.US) in imageExts) ||
            (videoOptions.isEmpty() && formats.none { it.vcodec != "none" && it.vcodec != null })

        val imageUrls = buildList {
            if (isPureImage && !info.url.isNullOrBlank() && info.url!!.startsWith("http")) {
                add(info.url!!)
            } else if (isPureImage && !info.thumbnail.isNullOrBlank() && info.thumbnail!!.startsWith("http")) {
                add(info.thumbnail!!)
            }
        }

        if (videoOptions.isEmpty() && !isPureImage) {
            val probeTarget = formats.lastOrNull { !it.url.isNullOrBlank() && it.url!!.startsWith("http") }?.url
                ?: info.url?.takeIf { it.startsWith("http") }
            val fallbackSize = probeTarget?.let { probeUrlSize(it) }
            videoOptions = listOf(
                VideoOption(1080, "Original", "MP4", "Best available stream", fallbackSize),
            )
        }

        val fallbackVideoSize = videoOptions.firstOrNull { it.approxSizeBytes != null }?.approxSizeBytes
        val audioOptions = defaultAudioOptions.map { opt ->
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

    private fun effectiveVideoHeight(width: Int, height: Int): Int {
        if (width > 0 && height > 0) return minOf(width, height)
        return height
    }

    private fun normalizeAndResolveUrl(url: String): String {
        var current = url
        val lower = current.lowercase(Locale.US)
        if (("threads.com" in lower || "threads.net" in lower) && "/share/" in lower) {
            runCatching {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                }
                conn.responseCode
                val resolved = conn.url.toString()
                conn.disconnect()
                if (resolved.startsWith("http")) current = resolved
            }
        }
        if ("threads.com" in current.lowercase(Locale.US)) {
            current = current.replace("threads.com", "threads.net", ignoreCase = true)
        }
        return current
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

    private fun fetchInstagramApiWithCookies(context: Context, url: String): MediaPreview? {
        val shortcode = Regex("""instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1) ?: return null

        return runCatching {
            val cookieHeader = InstagramAuth.withTempCookieFile(context) { cookieFile ->
                cookieFile.readLines()
                    .filter { !it.startsWith("#") && it.isNotBlank() }
                    .mapNotNull { line ->
                        val cols = line.split("\t")
                        if (cols.size >= 7) "${cols[5]}=${cols[6]}" else null
                    }
                    .joinToString("; ")
            }
            if (cookieHeader.isBlank()) return@runCatching null

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
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (body.isBlank() || !body.trimStart().startsWith("{")) return@runCatching null

            val root = JSONObject(body)
            val item = root.optJSONArray("items")?.optJSONObject(0)
                ?: root.optJSONObject("graphql")?.optJSONObject("shortcode_media")
                ?: return@runCatching null

            val rawTitle = item.optJSONObject("caption")?.optString("text")
                ?.ifBlank { null }
                ?: "Instagram Post ($shortcode)"
            val author = item.optJSONObject("user")?.optString("username")?.ifBlank { null }
                ?: item.optJSONObject("owner")?.optString("username")?.ifBlank { null }
                ?: "Instagram"

            val imageUrls = linkedSetOf<String>()
            val carousel = item.optJSONArray("carousel_media")
            if (carousel != null && carousel.length() > 0) {
                for (i in 0 until carousel.length()) {
                    val slide = carousel.optJSONObject(i) ?: continue
                    val bestImg = slide.optJSONObject("image_versions2")
                        ?.optJSONArray("candidates")
                        ?.let { candidates ->
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
                            best
                        }
                    if (bestImg != null) imageUrls.add(bestImg)
                }
            }

            // Single image (if not a video)
            val videoVersions = item.optJSONArray("video_versions")
            if (videoVersions != null && videoVersions.length() > 0 && imageUrls.isEmpty()) {
                val vidUrl = videoVersions.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
                val thumbUrl = item.optJSONObject("image_versions2")
                    ?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")
                if (vidUrl != null) {
                    val size = probeUrlSize(vidUrl)
                    return@runCatching MediaPreview(
                        mediaId = shortcode,
                        url = url,
                        title = rawTitle,
                        author = author,
                        platform = "INSTAGRAM · VIDEO",
                        durationText = formatDuration(item.optDouble("video_duration", 0.0).toInt()),
                        thumbnailUrl = thumbUrl,
                        videoOptions = listOf(
                            VideoOption(1080, "Original", "HD", "MP4 · Direct stream", size),
                        ),
                        audioOptions = defaultAudioOptions.map {
                            it.copy(approxSizeBytes = size?.let { s -> (s / 5L).coerceAtLeast(80_000L) })
                        },
                        directVideoUrl = vidUrl,
                        usedInstagramAuth = true,
                        sanitizedTitle = sanitizeTitle(rawTitle, "Instagram_$shortcode"),
                    )
                }
            }

            if (imageUrls.isEmpty()) {
                val singleImg = item.optJSONObject("image_versions2")
                    ?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")
                    ?.takeIf { it.startsWith("http") }
                    ?: item.optString("display_url").takeIf { it.startsWith("http") }
                if (singleImg != null) imageUrls.add(singleImg)
            }

            if (imageUrls.isNotEmpty()) {
                val list = imageUrls.toList()
                return@runCatching MediaPreview(
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
                    usedInstagramAuth = true,
                    sanitizedTitle = sanitizeTitle(rawTitle, "Instagram_$shortcode"),
                )
            }
            null
        }.getOrNull()
    }

    private fun fetchInstagramEmbedFallback(url: String): MediaPreview? {
        val shortcode = Regex("""instagram\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1) ?: return null
        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"

        return runCatching {
            val conn = (URL(embedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
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
                    if (!croppedUrlRegex.containsMatchIn(u)) displayUrls.add(u)
                }
            if (displayUrls.isEmpty()) {
                Regex("""class=["'][^"']*EmbeddedMediaImage[^"']*["'][^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .forEach { m -> displayUrls.add(m.groupValues[1].replace("&amp;", "&")) }
            }

            if (!videoUrl.isNullOrBlank()) {
                val size = probeUrlSize(videoUrl)
                return@runCatching MediaPreview(
                    mediaId = shortcode,
                    url = url,
                    title = "Instagram Reel ($shortcode)",
                    author = "Instagram",
                    platform = "INSTAGRAM · VIDEO",
                    durationText = "",
                    thumbnailUrl = displayUrls.firstOrNull(),
                    videoOptions = listOf(
                        VideoOption(1080, "Original", "HD", "MP4 · Direct stream", size),
                    ),
                    audioOptions = defaultAudioOptions.map {
                        it.copy(approxSizeBytes = size?.let { s -> (s / 5L).coerceAtLeast(80_000L) })
                    },
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
        if (formatsArr != null && formatsArr.length() > 0) {
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
                val buckets = listOf(1080, 720, 480, 360)
                if (formatsArr != null && formatsArr.length() > 0) {
                    val seenHeights = linkedSetOf<Int>()
                    for (i in 0 until formatsArr.length()) {
                        val f = formatsArr.optJSONObject(i) ?: continue
                        if (f.optString("vcodec") == "none") continue
                        val eh = effectiveVideoHeight(f.optInt("width", 0), f.optInt("height", 0))
                        val bucket = buckets.firstOrNull { eh in (it - 20)..(it + 20) } ?: continue
                        if (seenHeights.add(bucket)) {
                            val fmtUrl = f.optString("url").takeIf { it.startsWith("http") }
                            val rawSize = f.optLong("filesize", 0L).takeIf { it > 0L }
                                ?: f.optLong("filesize_approx", 0L).takeIf { it > 0L }
                            val size = rawSize ?: fmtUrl?.let { probeUrlSize(it) } ?: probedSize
                            add(VideoOption(bucket, "${bucket}p", qualityBadge(bucket), "MP4 · H.264", size))
                        }
                    }
                }
                if (isEmpty()) {
                    add(VideoOption(1080, "Original", "MP4", "Best available stream", probedSize))
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
                        addOption("--ignore-errors")
                        addOption("--cookies", cookieFile.absolutePath)
                    }
                    YoutubeDL.getInstance().execute(req, null, null).out
                }
            } else {
                val req = YoutubeDLRequest(url).apply {
                    addOption("--dump-single-json")
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

    private fun fetchWebMetaFallback(url: String): MediaPreview? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
        }
        val html = conn.inputStream.bufferedReader().use { it.readText().take(768 * 1024) }
        conn.disconnect()

        val meta = mutableMapOf<String, String>()
        ogTagRegex.findAll(html).forEach { m ->
            val key = (m.groupValues[1].ifEmpty { m.groupValues[4] }).lowercase(Locale.US)
            val valText = decodeHtml(m.groupValues[2].ifEmpty { m.groupValues[3] }).trim()
            if (key.isNotEmpty() && valText.isNotEmpty() && key !in meta) {
                meta[key] = valText
            }
        }

        val platform = detectPlatformName(url).uppercase(Locale.US)
        val title = decodeHtml(meta["og:title"] ?: meta["twitter:title"] ?: "$platform post")
        val imgUrl = meta["og:image:secure_url"] ?: meta["og:image"] ?: meta["twitter:image"]

        // Check video metadata FIRST (crucial for Threads videos!)
        val videoUrl = (meta["og:video:secure_url"] ?: meta["og:video"] ?: meta["twitter:player:stream"])
            ?.takeIf { it.startsWith("http") }
            ?: Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
            ?: Regex(""""video_versions"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"(https?:[^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)?.unescapeJsonUrl()
            ?: Regex("""<video[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")

        if (!videoUrl.isNullOrBlank()) {
            val size = probeUrlSize(videoUrl)
            return@runCatching MediaPreview(
                mediaId = url.hashCode().toString(),
                url = url,
                title = title,
                author = platform,
                platform = "$platform · VIDEO",
                durationText = "",
                thumbnailUrl = imgUrl,
                videoOptions = listOf(
                    VideoOption(1080, "Original", "HD", "MP4 · Direct stream", size),
                ),
                audioOptions = emptyList(),
                directVideoUrl = videoUrl,
                sanitizedTitle = sanitizeTitle(title, platform),
            )
        }

        if (imgUrl.isNullOrBlank() || !imgUrl.startsWith("http")) return@runCatching null

        MediaPreview(
            mediaId = url.hashCode().toString(),
            url = url,
            title = title,
            author = platform,
            platform = "$platform · IMAGE",
            durationText = "1 image",
            thumbnailUrl = imgUrl,
            videoOptions = emptyList(),
            audioOptions = emptyList(),
            imageUrls = listOf(imgUrl),
            sanitizedTitle = sanitizeTitle(title, platform),
        )
    }.getOrNull()

    private fun detectPlatformName(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            "instagram.com" in lower || "instagr.am" in lower -> "Instagram"
            "facebook.com" in lower || "fb.watch" in lower -> "Facebook"
            "reddit.com" in lower || "redd.it" in lower -> "Reddit"
            "twitter.com" in lower || "x.com" in lower -> "X"
            "threads.net" in lower || "threads.com" in lower -> "Threads"
            "youtube.com" in lower || "youtu.be" in lower -> "YouTube"
            else -> "Web"
        }
    }

    private fun friendlyError(raw: String, isInstagram: Boolean = false): String {
        val lower = raw.lowercase(Locale.US)
        return when {
            "empty media response" in lower || "login" in lower || "cookies" in lower || "certain audiences" in lower ->
                if (isInstagram) {
                    "This Instagram post requires sign-in or is restricted. Tap \"IG\" at the top to connect your account locally, or retry later."
                } else {
                    "This post requires login or is temporarily rate-limited by the platform. Try updating the Engine or retry later."
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

    fun parseRangeSelection(input: String, total: Int): Set<Int> {
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

    private fun probeUrlSize(url: String): Long? {
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
        DownloadMode.VIDEO -> "${videoHeight ?: 1080}p"
        DownloadMode.AUDIO -> if (audioOption?.isOriginal == true) "original" else "compact"
        DownloadMode.IMAGE -> imageFormat.id
    }

    fun targetExtension(
        mode: DownloadMode,
        audioOption: AudioOption?,
        imageFormat: ImageFormat = ImageFormat.ORIGINAL,
    ): String = when (mode) {
        DownloadMode.VIDEO -> "mp4"
        DownloadMode.AUDIO -> "m4a"
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
        val idMarker = "[$mediaId]"
        val qualityMarker = "[$qualityTag]"

        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            val match = dir?.listFiles()?.firstOrNull { file ->
                val name = file.name ?: return@firstOrNull false
                file.isFile &&
                    name.contains(idMarker) &&
                    name.contains(qualityMarker) &&
                    name.endsWith(".$extension", ignoreCase = true)
            }
            if (match != null) {
                return "${dir.name ?: "Custom folder"}/${match.name}"
            }
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%Zardit%", "%$qualityMarker%$idMarker%.$extension")
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
                file.isFile &&
                    file.name.contains(idMarker) &&
                    file.name.contains(qualityMarker) &&
                    file.name.endsWith(".$extension", ignoreCase = true)
            }
            return match?.let { "Downloads/Zardit/${it.name}" }
        }
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
        useInstagramCookies: Boolean = false,
        customTitle: String? = null,
        onProgress: (Float, String) -> Unit,
    ): SaveResult {
        val qTag = qualityTag(mode, videoHeight, audioOption)
        val workDir = File(context.cacheDir, "zardit_dl_$processId").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            val safePrefix = customTitle?.takeIf { it.isNotBlank() }?.let { sanitizeTitle(it) }
            if (!directVideoUrl.isNullOrBlank() && mode == DownloadMode.VIDEO) {
                onProgress(15f, "15% · Awakening Charizard…")
                val id = Regex("""/(?:reel|p|tv)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
                    ?: url.hashCode().toString()
                val outFile = File(workDir, "${safePrefix ?: "Video"} [$qTag] [$id].mp4")
                val conn = (URL(directVideoUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            copied += read
                            if (total > 0L) {
                                val pct = (copied * 100f / total).coerceIn(0f, 99f)
                                onProgress(pct, ZarditFlavor.formatProgressLine("", pct))
                            }
                        }
                    }
                }
                conn.disconnect()
                return saveOutputFile(context, outFile, mode, treeUri)
            }

            val titleTemplate = if (!safePrefix.isNullOrBlank()) safePrefix else "%(title).65B"
            val progressCallback: (Float, Long, String) -> Unit = { progress, _, line ->
                val pct = if (progress >= 0f) progress.coerceIn(0f, 100f) else 0f
                val friendly = ZarditFlavor.formatProgressLine(line, pct)
                onProgress(pct, friendly)
            }

            fun runRequest(req: YoutubeDLRequest) {
                if (useInstagramCookies && InstagramAuth.hasSession(context)) {
                    InstagramAuth.withTempCookieFile(context) { cookieFile ->
                        req.addOption("--cookies", cookieFile.absolutePath)
                        YoutubeDL.getInstance().execute(req, processId, progressCallback)
                    }
                } else {
                    YoutubeDL.getInstance().execute(req, processId, progressCallback)
                }
            }

            if (mode == DownloadMode.AUDIO) {
                val formatSpec = if (audioOption?.isOriginal == true) {
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
                return saveOutputFile(context, outputFile, mode, treeUri)
            }

            // Video mode (no FFmpeg):
            // Try separate MP4 video + M4A audio & mux natively with MediaMuxer for >720p, or direct MP4 when single stream.
            val h = videoHeight ?: 1080
            val isYouTube = detectPlatformName(url) == "YouTube"
            if (isYouTube && h > 720) {
                val vidTemp = File(workDir, "video_only.mp4")
                val audTemp = File(workDir, "audio_only.m4a")
                val vidReq = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("-f", "bestvideo[height<=$h][vcodec^=avc1][ext=mp4]/bestvideo[height<=$h][ext=mp4]")
                    addOption("-o", vidTemp.absolutePath)
                }
                runRequest(vidReq)

                val audReq = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("-f", "bestaudio[ext=m4a]")
                    addOption("-o", audTemp.absolutePath)
                }
                runRequest(audReq)

                if (vidTemp.isFile && audTemp.isFile) {
                    onProgress(95f, ZarditFlavor.formatProgressLine("ffmpeg", 95f))
                    val id = Regex("""(?:v=|youtu\.be/|shorts/)([A-Za-z0-9_-]{11})""")
                        .find(url)?.groupValues?.getOrNull(1) ?: url.hashCode().toString()
                    val finalName = "${safePrefix ?: "Video"} [$qTag] [$id].mp4"
                    val muxedOut = File(workDir, finalName)
                    NativeMuxer.muxMp4(vidTemp, audTemp, muxedOut)
                    return saveOutputFile(context, muxedOut, mode, treeUri)
                }
            }

            // Single pre-muxed stream (YouTube <=720p, Instagram, Threads, Reddit, X, Facebook)
            val singleReq = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-f", "best[height<=$h][ext=mp4]/best[ext=mp4]/best[height<=$h]/best")
                addOption("-o", "${workDir.absolutePath}/$titleTemplate [$qTag] [%(id)s].%(ext)s")
            }
            runRequest(singleReq)

            val outputFile = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: error("Download finished, but no output file was found.")

            return saveOutputFile(context, outputFile, mode, treeUri)
        } finally {
            workDir.deleteRecursively()
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
        onProgress(15f, "15% · Using Flash on the image…")
        val rawBytes = URL(imageUrl).openConnection().run {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            getInputStream().use { it.readBytes() }
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
            )
        }
    }

    private fun qualityBadge(height: Int): String = when {
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

    private fun formatDuration(seconds: Int): String {
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
}
