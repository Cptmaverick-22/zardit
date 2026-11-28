package io.github.cptmaverick_22.zardit.ffmpeg

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.util.UUID

/**
 * Companion ContentProvider exposing safe, structured FFmpeg operations to Zardit.
 *
 * Keeping FFmpeg in a separate companion APK allows libffmpeg.so to reside in a
 * read-only nativeLibraryDir (W^X / SELinux compliant on Android 10–17) without
 * bloating the base Zardit APK.
 */
class FfmpegAddonProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return failure("Missing addon context")
        val caller = callingPackage
        if (caller != MAIN_APP_PACKAGE_ID) {
            return failure("Unauthorized caller: ${caller ?: "unknown"}")
        }

        return when (method) {
            METHOD_PING -> handlePing(ctx)
            METHOD_CONVERT_MP3 -> handleConvertMp3(ctx, extras)
            METHOD_MUX_MP4 -> handleMuxMp4(ctx, extras)
            else -> failure("Unsupported method: $method")
        }
    }

    private fun handlePing(ctx: Context): Bundle {
        ensureInitialized(ctx)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        return Bundle().apply {
            putBoolean(KEY_OK, bin.isFile && bin.canExecute())
            putString(KEY_VERSION, BuildConfig.VERSION_NAME)
        }
    }

    private fun handleConvertMp3(ctx: Context, extras: Bundle?): Bundle {
        if (extras == null) return failure("Missing conversion arguments")
        val inputUri = readUri(extras, KEY_INPUT_URI) ?: return failure("Missing input_uri")
        val outputUri = readUri(extras, KEY_OUTPUT_URI) ?: return failure("Missing output_uri")
        val coverUri = readUri(extras, KEY_COVER_URI)
        val rawBitrate = extras.getInt(KEY_BITRATE_KBPS, 192)
        val bitrate = if (rawBitrate in ALLOWED_BITRATES) rawBitrate else 192
        val title = sanitizeMetadata(extras.getString(KEY_TITLE))
        val artist = sanitizeMetadata(extras.getString(KEY_ARTIST))

        ensureInitialized(ctx)
        val workDir = File(ctx.cacheDir, "ffmpeg_work/${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            val inputFile = File(workDir, "input.media")
            val outputFile = File(workDir, "output.mp3")
            copyFromUri(ctx, inputUri, inputFile)
            val coverFile = if (coverUri != null) {
                File(workDir, "cover.jpg").also { dst ->
                    runCatching { copyFromUri(ctx, coverUri, dst) }
                }.takeIf { it.isFile && it.length() > 64L }
            } else {
                null
            }

            var ok = false
            var lastLog = ""
            if (coverFile != null) {
                val cmdWithCover = buildList {
                    add(ffmpegBinary(ctx).absolutePath)
                    addAll(listOf("-y", "-i", inputFile.absolutePath, "-i", coverFile.absolutePath))
                    addAll(listOf("-map", "0:a:0", "-map", "1:v:0"))
                    addAll(listOf("-c:a", "libmp3lame", "-b:a", "${bitrate}k"))
                    addAll(listOf("-c:v", "mjpeg", "-id3v2_version", "3"))
                    addAll(listOf("-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)"))
                    if (!title.isNullOrBlank()) addAll(listOf("-metadata", "title=$title"))
                    if (!artist.isNullOrBlank()) addAll(listOf("-metadata", "artist=$artist"))
                    add(outputFile.absolutePath)
                }
                val (exit, log) = execFfmpeg(ctx, cmdWithCover)
                lastLog = log
                ok = exit == 0 && outputFile.isFile && outputFile.length() >= 1024L
                if (!ok) outputFile.delete()
            }

            if (!ok) {
                val cmdAudioOnly = buildList {
                    add(ffmpegBinary(ctx).absolutePath)
                    addAll(listOf("-y", "-i", inputFile.absolutePath, "-vn"))
                    addAll(listOf("-c:a", "libmp3lame", "-b:a", "${bitrate}k", "-id3v2_version", "3"))
                    if (!title.isNullOrBlank()) addAll(listOf("-metadata", "title=$title"))
                    if (!artist.isNullOrBlank()) addAll(listOf("-metadata", "artist=$artist"))
                    add(outputFile.absolutePath)
                }
                val (exit, log) = execFfmpeg(ctx, cmdAudioOnly)
                lastLog = log
                ok = exit == 0 && outputFile.isFile && outputFile.length() >= 1024L
            }

            if (!ok) {
                return failure("FFmpeg MP3 conversion failed: ${lastLog.takeLast(300)}")
            }

            copyToUri(ctx, outputFile, outputUri)
            Bundle().apply { putBoolean(KEY_OK, true) }
        } catch (t: Throwable) {
            failure(t.message ?: t::class.java.simpleName)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun handleMuxMp4(ctx: Context, extras: Bundle?): Bundle {
        if (extras == null) return failure("Missing mux arguments")
        val videoUri = readUri(extras, KEY_VIDEO_URI) ?: return failure("Missing video_uri")
        val audioUri = readUri(extras, KEY_AUDIO_URI)
        val outputUri = readUri(extras, KEY_OUTPUT_URI) ?: return failure("Missing output_uri")

        ensureInitialized(ctx)
        val workDir = File(ctx.cacheDir, "ffmpeg_work/${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            val videoFile = File(workDir, "video.media")
            val audioFile = if (audioUri != null) File(workDir, "audio.media") else null
            val outputFile = File(workDir, "muxed.mp4")

            copyFromUri(ctx, videoUri, videoFile)
            if (audioFile != null && audioUri != null) {
                copyFromUri(ctx, audioUri, audioFile)
            }

            val copyCmd = buildList {
                add(ffmpegBinary(ctx).absolutePath)
                addAll(listOf("-y", "-i", videoFile.absolutePath))
                if (audioFile != null) addAll(listOf("-i", audioFile.absolutePath))
                addAll(listOf("-c", "copy", "-movflags", "+faststart", outputFile.absolutePath))
            }
            var (exit, lastLog) = execFfmpeg(ctx, copyCmd)
            var ok = exit == 0 && outputFile.isFile && outputFile.length() >= 4096L

            // Fallback when container rejects Opus/Vorbis/etc. on stream copy: copy video, encode audio to AAC.
            if (!ok) {
                outputFile.delete()
                val compatCmd = buildList {
                    add(ffmpegBinary(ctx).absolutePath)
                    addAll(listOf("-y", "-i", videoFile.absolutePath))
                    if (audioFile != null) addAll(listOf("-i", audioFile.absolutePath))
                    addAll(listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", outputFile.absolutePath))
                }
                val retry = execFfmpeg(ctx, compatCmd)
                exit = retry.first
                lastLog = retry.second
                ok = exit == 0 && outputFile.isFile && outputFile.length() >= 4096L
            }

            if (!ok) {
                return failure("FFmpeg mux failed (exit=$exit): ${lastLog.takeLast(300)}")
            }

            copyToUri(ctx, outputFile, outputUri)
            Bundle().apply { putBoolean(KEY_OK, true) }
        } catch (t: Throwable) {
            failure(t.message ?: t::class.java.simpleName)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Synchronized
    private fun ensureInitialized(ctx: Context) {
        FFmpeg.getInstance().init(ctx)
    }

    private fun ffmpegBinary(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    private fun execFfmpeg(ctx: Context, args: List<String>): Pair<Int, String> {
        val usrLib = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        if (usrLib.isDirectory) {
            val env = pb.environment()
            val cur = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] = if (cur.isBlank()) usrLib.absolutePath else "${usrLib.absolutePath}:$cur"
        }
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val exit = proc.waitFor()
        return exit to output
    }

    private fun copyFromUri(ctx: Context, uri: Uri, dst: File) {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open input URI")
    }

    private fun copyToUri(ctx: Context, src: File, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            src.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open output URI")
    }

    @Suppress("DEPRECATION")
    private fun readUri(bundle: Bundle, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(key, Uri::class.java)
        } else {
            bundle.getParcelable(key)
        }

    private fun sanitizeMetadata(raw: String?): String? =
        raw?.replace(Regex("[\\u0000-\\u001F]"), " ")?.trim()?.take(180)?.ifBlank { null }

    private fun failure(message: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, message)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val MAIN_APP_PACKAGE_ID = "io.github.cptmaverick_22.zardit"
        const val METHOD_PING = "ping"
        const val METHOD_CONVERT_MP3 = "convert_mp3"
        const val METHOD_MUX_MP4 = "mux_mp4"

        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_VERSION = "version"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_COVER_URI = "cover_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_VIDEO_URI = "video_uri"
        const val KEY_AUDIO_URI = "audio_uri"
        const val KEY_BITRATE_KBPS = "bitrate_kbps"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"

        private val ALLOWED_BITRATES = setOf(128, 192, 256, 320)
    }
}
