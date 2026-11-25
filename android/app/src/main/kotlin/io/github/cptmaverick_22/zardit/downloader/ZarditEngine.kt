package io.github.cptmaverick_22.zardit.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

object ZarditEngine {

    val defaultAudioOptions = listOf(
        AudioOption("mp3_320", "320 kbps", "MP3 · High quality", 320),
        AudioOption("mp3_192", "192 kbps", "MP3 · Balanced", 192),
        AudioOption("mp3_128", "128 kbps", "MP3 · Compact", 128),
        AudioOption("orig_m4a", "Original", "M4A · No re-encoding", null, isOriginal = true),
    )

    fun fetchPreview(url: String): MediaPreview {
        val cleanUrl = url.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "Enter a valid http/https link."
        }

        val info = YoutubeDL.getInstance().getInfo(cleanUrl)
        val formats = info.formats.orEmpty()
        val durationSec = info.duration.coerceAtLeast(0)

        val bestAudioSize = formats
            .filter { f -> f.vcodec == "none" && f.acodec != "none" }
            .maxOfOrNull { it.fileSize.coerceAtLeast(it.fileSizeApproximate) }
            ?: 0L

        val heightBuckets = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        val videoOptions = heightBuckets.mapNotNull { h ->
            val matching = formats.filter { f ->
                f.height in (h - 20)..(h + 20) && f.vcodec != "none"
            }
            if (matching.isEmpty()) return@mapNotNull null

            val bestVideo = matching.maxByOrNull {
                it.fileSize.coerceAtLeast(it.fileSizeApproximate)
            }
            val videoBytes = bestVideo?.let { it.fileSize.coerceAtLeast(it.fileSizeApproximate) } ?: 0L
            val approxTotal = if (videoBytes > 0L) videoBytes + bestAudioSize else null
            val codecHint = if (h >= 1440) "MP4 · AV1/H.264" else "MP4 · H.264"
            VideoOption(
                height = h,
                label = "${h}p",
                badge = qualityBadge(h),
                codecHint = codecHint,
                approxSizeBytes = approxTotal,
            )
        }.ifEmpty {
            listOf(
                VideoOption(1080, "1080p", "Full HD", "MP4 · Best available", null),
                VideoOption(720, "720p", "HD", "MP4 · Best available", null),
                VideoOption(480, "480p", "SD", "MP4 · Best available", null),
            )
        }

        val audioOptions = defaultAudioOptions.map { opt ->
            val estimated = when {
                opt.isOriginal -> bestAudioSize.takeIf { it > 0L }
                    ?: if (durationSec > 0) (durationSec.toLong() * 128_000L) / 8L else null
                opt.bitrateKbps != null && durationSec > 0 ->
                    (durationSec.toLong() * opt.bitrateKbps.toLong() * 1000L) / 8L
                else -> null
            }
            opt.copy(approxSizeBytes = estimated)
        }

        val platform = (info.extractorKey ?: info.extractor ?: "Web")
            .replace("Tab", "")
            .uppercase(Locale.US)

        return MediaPreview(
            mediaId = info.id?.ifBlank { null } ?: cleanUrl.hashCode().toString(),
            url = cleanUrl,
            title = info.title?.ifBlank { null } ?: "Untitled media",
            author = info.uploader?.ifBlank { null } ?: "Unknown creator",
            platform = "$platform · VIDEO",
            durationText = formatDuration(durationSec),
            thumbnailUrl = info.thumbnail?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            videoOptions = videoOptions,
            audioOptions = audioOptions,
        )
    }

    fun qualityTag(
        mode: DownloadMode,
        videoHeight: Int?,
        audioOption: AudioOption?,
    ): String = when (mode) {
        DownloadMode.VIDEO -> "${videoHeight ?: 1080}p"
        DownloadMode.AUDIO -> if (audioOption?.isOriginal == true) {
            "original"
        } else {
            "${audioOption?.bitrateKbps ?: 192}kbps"
        }
    }

    fun targetExtension(mode: DownloadMode, audioOption: AudioOption?): String = when (mode) {
        DownloadMode.VIDEO -> "mp4"
        DownloadMode.AUDIO -> if (audioOption?.isOriginal == true) "m4a" else "mp3"
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
                }
            }

            YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                val pct = if (progress >= 0f) progress.coerceIn(0f, 100f) else 0f
                val etaText = if (etaInSeconds > 0) " · ETA ${formatDuration(etaInSeconds.toInt())}" else ""
                val status = if (pct > 0f) {
                    "${pct.roundToInt()}%$etaText"
                } else {
                    line.take(60).ifBlank { "Downloading..." }
                }
                onProgress(pct, status)
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

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    fun updateYtDlp(context: Context): String {
        val status = YoutubeDL.getInstance().updateYoutubeDL(
            context,
            YoutubeDL.UpdateChannel.STABLE,
        )
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated to the latest version."
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date."
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
            else -> if (mode == DownloadMode.AUDIO) "audio/*" else "video/*"
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
