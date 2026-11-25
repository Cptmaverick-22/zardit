package io.github.cptmaverick_22.zardit.downloader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

object ZarditEngine {

    val defaultAudioOptions = listOf(
        AudioOption("mp3_320", "320 kbps", "MP3 · High quality", 320),
        AudioOption("mp3_192", "192 kbps", "MP3 · Balanced", 192),
        AudioOption("mp3_128", "128 kbps", "MP3 · Compact", 128),
        AudioOption("orig_m4a", "Original", "M4A · No re-encoding", null, isOriginal = true),
    )

    private val imageExts = setOf("jpg", "jpeg", "png", "webp")
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

    fun fetchPreview(url: String): MediaPreview {
        val cleanUrl = url.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "Enter a valid http/https link."
        }

        if (isPlaylistUrl(cleanUrl)) {
            fetchPlaylistPreview(cleanUrl)?.let { return it }
        }

        val info = try {
            YoutubeDL.getInstance().getInfo(cleanUrl)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.contains("No video formats found", ignoreCase = true)) {
                fetchImageFallback(cleanUrl)?.let { return it }
            }
            throw IllegalStateException(friendlyError(msg))
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
                f.height in (h - 20)..(h + 20) && f.vcodec != "none"
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
                VideoOption(1080, "1080p", "Full HD", "MP4 · Best available", fallbackSize),
                VideoOption(720, "720p", "HD", "MP4 · Best available", fallbackSize),
                VideoOption(480, "480p", "SD", "MP4 · Best available", fallbackSize),
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
                    (bestAudioSize * opt.bitrateKbps.toLong()) / 128L
                opt.bitrateKbps != null && fallbackVideoSize != null ->
                    ((fallbackVideoSize / 5L) * opt.bitrateKbps.toLong() / 128L).coerceAtLeast(80_000L)
                else -> null
            }
            opt.copy(approxSizeBytes = estimated)
        }

        val platform = (info.extractorKey ?: info.extractor ?: "Web")
            .replace("Tab", "")
            .uppercase(Locale.US)
        val kindLabel = if (isPureImage && imageUrls.isNotEmpty()) "IMAGE" else "VIDEO"

        return MediaPreview(
            mediaId = info.id?.ifBlank { null } ?: cleanUrl.hashCode().toString(),
            url = cleanUrl,
            title = info.title?.ifBlank { null } ?: "Untitled media",
            author = info.uploader?.ifBlank { null } ?: "Unknown creator",
            platform = "$platform · $kindLabel",
            durationText = formatDuration(durationSec),
            thumbnailUrl = info.thumbnail?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            videoOptions = videoOptions,
            audioOptions = audioOptions,
            imageUrls = imageUrls,
        )
    }

    private fun fetchImageFallback(url: String): MediaPreview? {
        // 1. Try yt-dlp --dump-single-json --ignore-errors for carousel thumbnails
        runCatching {
            val req = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--ignore-errors")
            }
            val res = YoutubeDL.getInstance().execute(req, null, null)
            if (res.out.isNotBlank()) {
                val json = JSONObject(res.out)
                val urls = linkedSetOf<String>()
                val entries = json.optJSONArray("entries")
                if (entries != null) {
                    for (i in 0 until entries.length()) {
                        val obj = entries.optJSONObject(i) ?: continue
                        val u = obj.optString("url").takeIf { it.startsWith("http") }
                            ?: obj.optString("thumbnail").takeIf { it.startsWith("http") }
                        if (u != null) urls.add(u)
                    }
                }
                val mainThumb = json.optString("thumbnail").takeIf { it.startsWith("http") }
                if (urls.isEmpty() && mainThumb != null) urls.add(mainThumb)
                if (urls.isNotEmpty()) {
                    val platform = json.optString("extractor_key", "WEB")
                        .replace("Tab", "").uppercase(Locale.US)
                    return MediaPreview(
                        mediaId = json.optString("id").ifBlank { url.hashCode().toString() },
                        url = url,
                        title = json.optString("title").ifBlank { "Image post (${urls.size})" },
                        author = json.optString("uploader").ifBlank { "${urls.size} image${if (urls.size == 1) "" else "s"}" },
                        platform = "$platform · IMAGE",
                        durationText = "${urls.size} image${if (urls.size == 1) "" else "s"}",
                        thumbnailUrl = urls.first(),
                        videoOptions = emptyList(),
                        audioOptions = emptyList(),
                        imageUrls = urls.toList(),
                    )
                }
            }
        }

        // 2. Fall back to OpenGraph og:image extraction
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val html = conn.inputStream.bufferedReader().use { it.readText().take(512 * 1024) }
            conn.disconnect()

            val meta = mutableMapOf<String, String>()
            ogTagRegex.findAll(html).forEach { m ->
                val key = (m.groupValues[1].ifEmpty { m.groupValues[4] }).lowercase(Locale.US)
                val valText = m.groupValues[2].ifEmpty { m.groupValues[3] }
                    .replace("&amp;", "&")
                    .trim()
                if (key.isNotEmpty() && valText.isNotEmpty() && key !in meta) {
                    meta[key] = valText
                }
            }
            val imgUrl = meta["og:image:secure_url"] ?: meta["og:image"] ?: meta["twitter:image"]
            if (imgUrl.isNullOrBlank() || !imgUrl.startsWith("http")) return@runCatching null

            val platform = detectPlatformName(url).uppercase(Locale.US)
            val title = meta["og:title"] ?: meta["twitter:title"] ?: "Image post"
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
            )
        }.getOrNull()
    }

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

    private fun friendlyError(raw: String): String {
        val lower = raw.lowercase(Locale.US)
        return when {
            "empty media response" in lower || "login" in lower || "cookies" in lower ->
                "This post requires login or is temporarily rate-limited by the platform. Try updating yt-dlp or retry later."
            "no video formats found" in lower ->
                "No downloadable video or image was found at this link."
            else -> {
                raw.lineSequence()
                    .firstOrNull { it.isNotBlank() && !it.contains("github.com/yt-dlp") && !it.contains("yt-dlp -U") }
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
                val thumb = obj.optJSONArray("thumbnails")?.let { thumbs ->
                    if (thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") else null
                } ?: obj.optString("thumbnail").ifBlank { null }
                add(
                    PlaylistEntry(
                        index = i + 1,
                        id = id,
                        title = obj.optString("title").ifBlank { "Item ${i + 1}" },
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
        MediaPreview(
            mediaId = json.optString("id").ifBlank { url.hashCode().toString() },
            url = url,
            title = json.optString("title").ifBlank { "Playlist (${entries.size} items)" },
            author = json.optString("uploader").ifBlank { "${entries.size} items" },
            platform = "$platform · PLAYLIST",
            durationText = "${entries.size} items",
            thumbnailUrl = entries.firstOrNull()?.thumbnailUrl,
            videoOptions = listOf(
                VideoOption(1080, "1080p", "Full HD", "MP4 · H.264", null),
                VideoOption(720, "720p", "HD", "MP4 · H.264", null),
                VideoOption(480, "480p", "SD", "MP4 · H.264", null),
                VideoOption(360, "360p", "Low", "MP4 · H.264", null),
            ),
            audioOptions = defaultAudioOptions,
            isPlaylist = true,
            playlistEntries = entries,
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
        DownloadMode.AUDIO -> if (audioOption?.isOriginal == true) {
            "original"
        } else {
            "${audioOption?.bitrateKbps ?: 192}kbps"
        }
        DownloadMode.IMAGE -> imageFormat.id
    }

    fun targetExtension(
        mode: DownloadMode,
        audioOption: AudioOption?,
        imageFormat: ImageFormat = ImageFormat.ORIGINAL,
    ): String = when (mode) {
        DownloadMode.VIDEO -> "mp4"
        DownloadMode.AUDIO -> if (audioOption?.isOriginal == true) "m4a" else "mp3"
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
        onProgress: (Float, String) -> Unit,
    ): SaveResult {
        val qTag = qualityTag(mode, videoHeight, audioOption)
        val workDir = File(context.cacheDir, "zardit_dl_$processId").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-o", "${workDir.absolutePath}/%(title).120B [$qTag] [%(id)s].%(ext)s")

                when (mode) {
                    DownloadMode.VIDEO -> {
                        val h = videoHeight ?: 1080
                        val formatSelector = if (h <= 1080) {
                            "bestvideo[height<=$h][vcodec^=avc1][ext=mp4]+bestaudio[ext=m4a]/" +
                                "bestvideo[height<=$h][ext=mp4]+bestaudio[ext=m4a]/" +
                                "best[height<=$h][ext=mp4]/best[height<=$h]/best"
                        } else {
                            "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
                        }
                        addOption("-f", formatSelector)
                        addOption("--merge-output-format", "mp4")
                    }

                    DownloadMode.AUDIO -> {
                        addOption("-x")
                        addOption("--embed-thumbnail")
                        addOption("--add-metadata")
                        if (audioOption?.isOriginal == true) {
                            addOption("--audio-format", "m4a")
                        } else {
                            val kbps = audioOption?.bitrateKbps ?: 192
                            addOption("--audio-format", "mp3")
                            addOption("--audio-quality", "${kbps}K")
                        }
                    }

                    DownloadMode.IMAGE -> error("Use downloadImage for Image mode.")
                }
            }

            YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
                val pct = if (progress >= 0f) progress.coerceIn(0f, 100f) else 0f
                val friendly = ZarditFlavor.formatProgressLine(line, pct)
                onProgress(pct, friendly)
            }

            val outputFile = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: error("Download finished, but no output file was found.")

            return saveOutputFile(context, outputFile, mode, treeUri)
        } finally {
            workDir.deleteRecursively()
        }
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

        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "-").take(80).ifBlank { "image" }
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
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "Charizard learned new moves! (yt-dlp updated)"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Charizard is already at max level! (yt-dlp up to date)"
            else -> "yt-dlp update completed."
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
