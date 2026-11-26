package io.github.cptmaverick_22.zardit.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object NativeMuxer {

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

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val outVideoTrack = muxer.addTrack(videoFormat)

            var outAudioTrack = -1
            if (audioTrackIndex >= 0) {
                audioExtractor.selectTrack(audioTrackIndex)
                val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
                outAudioTrack = muxer.addTrack(audioFormat)
            }

            muxer.start()

            val bufferSize = maxBufferSize(videoFormat).coerceAtLeast(512 * 1024)
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()

            copySamples(videoExtractor, muxer, outVideoTrack, buffer, info)
            if (outAudioTrack >= 0) {
                copySamples(audioExtractor, muxer, outAudioTrack, buffer, info)
            }
        } finally {
            runCatching { muxer?.stop() }
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
    ) {
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            @Suppress("DEPRECATION")
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    }
}
