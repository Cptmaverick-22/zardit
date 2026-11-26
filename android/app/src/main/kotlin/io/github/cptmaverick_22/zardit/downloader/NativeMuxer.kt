package io.github.cptmaverick_22.zardit.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object NativeMuxer {

    private val supportedVideoMimes = setOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_MPEG4,
    )

    fun muxMp4(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrackIndex = findTrack(videoExtractor, "video/")
            val audioTrackIndex = findTrack(audioExtractor, "audio/")
            require(videoTrackIndex >= 0) { "No video track found to mux." }

            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            require(videoMime in supportedVideoMimes) {
                "Unsupported video codec for MP4 muxing: $videoMime"
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = muxer.addTrack(videoFormat)

            var outAudioTrack = -1
            if (audioTrackIndex >= 0) {
                audioExtractor.selectTrack(audioTrackIndex)
                val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
                val audioMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                if (audioMime.startsWith("audio/mp4a") || audioMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    outAudioTrack = muxer.addTrack(audioFormat)
                }
            }

            muxer.start()

            val bufferSize = maxBufferSize(videoFormat).coerceAtLeast(512 * 1024)
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()

            val videoFrames = copySamples(videoExtractor, muxer, outVideoTrack, buffer, info)
            require(videoFrames > 0) { "No video samples written during mux." }

            if (outAudioTrack >= 0) {
                copySamples(audioExtractor, muxer, outAudioTrack, buffer, info)
            }

            muxer.stop()
            muxer.release()
            muxer = null

            require(outputFile.isFile && outputFile.length() > 1024L) {
                "Muxed output file is empty."
            }
        } catch (t: Throwable) {
            runCatching { muxer?.release() }
            muxer = null
            outputFile.delete()
            throw t
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun maxBufferSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            512 * 1024
        }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ): Int {
        var count = 0
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            @Suppress("DEPRECATION")
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buffer, info)
            count++
            extractor.advance()
        }
        return count
    }
}
