package io.github.cptmaverick_22.zardit.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

object NativeMuxer {

    private val platformSupportedVideoMimes = setOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_MPEG4,
    )

    fun muxMp4(videoFile: File, audioFile: File, outputFile: File) {
        val media3Result = runCatching {
            muxWithMedia3(videoFile, audioFile, outputFile)
        }
        if (media3Result.isSuccess && outputFile.isFile && outputFile.length() > 1024L) {
            return
        }
        outputFile.delete()

        val fallbackResult = runCatching {
            muxWithPlatform(videoFile, audioFile, outputFile)
        }
        if (fallbackResult.isSuccess && outputFile.isFile && outputFile.length() > 1024L) {
            return
        }
        outputFile.delete()

        val err = media3Result.exceptionOrNull() ?: fallbackResult.exceptionOrNull()
        throw IllegalStateException(err?.message ?: "Failed to mux video and audio.", err)
    }

    @OptIn(UnstableApi::class)
    private fun muxWithMedia3(videoFile: File, audioFile: File, outputFile: File) {
        var muxer: Mp4Muxer? = null
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(outputFile)
            muxer = Mp4Muxer.Builder(fos)
                .setSampleCopyingEnabled(true)
                .build()

            val videoSamples = streamMedia3Track(videoFile, C.TRACK_TYPE_VIDEO, muxer)
            require(videoSamples > 0) { "No video samples extracted." }

            if (audioFile.isFile && audioFile.length() > 0L) {
                runCatching {
                    streamMedia3Track(audioFile, C.TRACK_TYPE_AUDIO, muxer)
                }
            }

            muxer.close()
            muxer = null
            fos.close()
            fos = null

            require(outputFile.isFile && outputFile.length() > 1024L) {
                "Media3 muxed output file is empty."
            }
        } catch (t: Throwable) {
            runCatching { muxer?.close() }
            runCatching { fos?.close() }
            outputFile.delete()
            throw t
        }
    }

    @OptIn(UnstableApi::class)
    private fun streamMedia3Track(file: File, targetTrackType: Int, muxer: Mp4Muxer): Int {
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            var readPos = 0L
            val reader = DataReader { buf, offset, length ->
                raf.seek(readPos)
                val n = raf.read(buf, offset, length)
                if (n == -1) C.RESULT_END_OF_INPUT else {
                    readPos += n
                    n
                }
            }

            val candidates = listOf(
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
            )
            var chosen: Extractor? = null
            for (ex in candidates) {
                readPos = 0L
                val probeInput = DefaultExtractorInput(reader, 0L, fileLen)
                if (runCatching { ex.sniff(probeInput) }.getOrDefault(false)) {
                    chosen = ex
                    break
                } else {
                    runCatching { ex.release() }
                }
            }
            val extractor = chosen ?: error("Unsupported container for Media3 muxing: ${file.name}")

            var muxTrackId = -1
            var sampleCount = 0
            val sampleBuf = ByteArrayOutputStream(64 * 1024)

            try {
                extractor.init(object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        if (type != targetTrackType || muxTrackId >= 0) {
                            return DiscardingTrackOutput()
                        }
                        return object : TrackOutput {
                            override fun format(format: Format) {
                                muxTrackId = muxer.addTrack(format)
                            }

                            override fun sampleData(
                                input: DataReader,
                                length: Int,
                                allowEndOfInput: Boolean,
                                sampleDataPart: Int,
                            ): Int {
                                val chunk = ByteArray(minOf(length, 64 * 1024))
                                var total = 0
                                while (total < length) {
                                    val n = input.read(chunk, 0, minOf(chunk.size, length - total))
                                    if (n == C.RESULT_END_OF_INPUT) {
                                        if (allowEndOfInput && total == 0) return C.RESULT_END_OF_INPUT
                                        throw EOFException("Unexpected end of media stream")
                                    }
                                    if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
                                        sampleBuf.write(chunk, 0, n)
                                    }
                                    total += n
                                }
                                return total
                            }

                            override fun sampleData(
                                data: ParsableByteArray,
                                length: Int,
                                sampleDataPart: Int,
                            ) {
                                val chunk = ByteArray(length)
                                data.readBytes(chunk, 0, length)
                                if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
                                    sampleBuf.write(chunk, 0, length)
                                }
                            }

                            override fun sampleMetadata(
                                timeUs: Long,
                                flags: Int,
                                size: Int,
                                offset: Int,
                                cryptoData: TrackOutput.CryptoData?,
                            ) {
                                val all = sampleBuf.toByteArray()
                                sampleBuf.reset()
                                if (offset > 0 && all.size >= offset) {
                                    sampleBuf.write(all, all.size - offset, offset)
                                }
                                val payloadEnd = all.size - offset
                                val payloadStart = payloadEnd - size
                                if (muxTrackId >= 0 && size > 0 && payloadStart >= 0) {
                                    val slice = ByteBuffer.wrap(all, payloadStart, size).slice()
                                    muxer.writeSampleData(
                                        muxTrackId,
                                        slice,
                                        BufferInfo(timeUs, size, flags),
                                    )
                                    sampleCount++
                                }
                            }
                        }
                    }

                    override fun endTracks() {}
                    override fun seekMap(seekMap: SeekMap) {}
                })

                readPos = 0L
                var input: ExtractorInput = DefaultExtractorInput(reader, 0L, fileLen)
                val posHolder = PositionHolder()
                while (true) {
                    val result = extractor.read(input, posHolder)
                    if (result == Extractor.RESULT_END_OF_INPUT) break
                    if (result == Extractor.RESULT_SEEK) {
                        readPos = posHolder.position
                        input = DefaultExtractorInput(reader, readPos, fileLen)
                    }
                }
            } finally {
                runCatching { extractor.release() }
            }
            return sampleCount
        }
    }

    private fun muxWithPlatform(videoFile: File, audioFile: File, outputFile: File) {
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
            require(videoMime in platformSupportedVideoMimes) {
                "Unsupported video codec for platform muxing: $videoMime"
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
