package com.gsmautoanswer.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.gsmautoanswer.app.diagnostics.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

data class CachedAudio(
    val file: File,
    val durationMs: Long,
    val displayName: String,
    val uri: String,
)

data class TestTone(
    val greeting: CachedAudio,
    val hold: CachedAudio,
)

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AudioCacheRepository(
    private val context: Context,
    private val log: SessionLog,
) {
    private val directory = File(context.filesDir, "caller_audio").apply { mkdirs() }
    private val decodeMutex = Mutex()

    fun cachedFileFor(uriString: String): CachedAudio? {
        val meta = queryMeta(uriString)
        val file = File(directory, cacheKey(uriString, meta) + ".pcm")
        if (!file.isFile || file.length() < MIN_PCM_BYTES) return null
        return CachedAudio(file, durationMs(file.length()), meta.displayName, uriString)
    }

    suspend fun ensure(uriString: String, onProgress: (Float) -> Unit = {}): CachedAudio =
        withContext(Dispatchers.IO) {
            decodeMutex.withLock {
                val meta = queryMeta(uriString)
                val cache = File(directory, cacheKey(uriString, meta) + ".pcm")
                if (cache.isFile && cache.length() >= MIN_PCM_BYTES) {
                    onProgress(1f)
                    return@withLock CachedAudio(
                        cache,
                        durationMs(cache.length()),
                        meta.displayName,
                        uriString,
                    )
                }

                val temp = File(directory, cache.name + ".tmp")
                try {
                    log.info("Decoding audio: ${meta.displayName} (${meta.size} bytes)")
                    decode(Uri.parse(uriString), temp, onProgress)
                    if (temp.length() < MIN_PCM_BYTES) {
                        throw AudioDecodeException("The file contains less than 0.1 seconds of audio.")
                    }
                    if (cache.exists()) cache.delete()
                    if (!temp.renameTo(cache)) {
                        temp.copyTo(cache, overwrite = true)
                        temp.delete()
                    }
                    log.info("Decoded audio: ${meta.displayName} -> ${cache.length()} bytes")
                    CachedAudio(cache, durationMs(cache.length()), meta.displayName, uriString)
                } catch (e: AudioDecodeException) {
                    temp.delete()
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    temp.delete()
                    throw e
                } catch (e: Exception) {
                    temp.delete()
                    throw AudioDecodeException(e.message ?: "Could not decode this audio file.", e)
                }
            }
        }

    /**
     * Generates the local tones used by the live compatibility test: a beep pattern the
     * caller can recognize, followed by a lower hold tone. No user files or network involved.
     */
    suspend fun ensureTestTone(): TestTone = withContext(Dispatchers.IO) {
        decodeMutex.withLock {
            val dir = File(directory, "live_test").apply { mkdirs() }
            val greeting = File(dir, "tone_greeting_v1.pcm")
            val hold = File(dir, "tone_hold_v1.pcm")
            if (!greeting.isFile || greeting.length() < MIN_PCM_BYTES) {
                writeTone(
                    file = greeting,
                    durationMs = TEST_GREETING_MS,
                    frequencyHz = 1_000.0,
                    amplitude = 0.28,
                    beepOnMs = 350,
                    beepOffMs = 150,
                )
            }
            if (!hold.isFile || hold.length() < MIN_PCM_BYTES) {
                writeTone(
                    file = hold,
                    durationMs = TEST_HOLD_MS,
                    frequencyHz = 440.0,
                    amplitude = 0.16,
                    beepOnMs = 0,
                    beepOffMs = 0,
                )
            }
            log.info("Live-test tones ready: ${greeting.length()} + ${hold.length()} bytes")
            TestTone(
                greeting = CachedAudio(
                    greeting,
                    durationMs(greeting.length()),
                    "Test beep",
                    TEST_GREETING_URI,
                ),
                hold = CachedAudio(
                    hold,
                    durationMs(hold.length()),
                    "Test hold tone",
                    TEST_HOLD_URI,
                ),
            )
        }
    }

    private fun writeTone(
        file: File,
        durationMs: Int,
        frequencyHz: Double,
        amplitude: Double,
        beepOnMs: Int,
        beepOffMs: Int,
    ) {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000)
        val fadeSamples = SAMPLE_RATE * 5 / 1000
        val periodSamples = (SAMPLE_RATE * (beepOnMs + beepOffMs) / 1000).coerceAtLeast(1)
        val onSamples = SAMPLE_RATE * beepOnMs / 1000
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            BufferedOutputStream(FileOutputStream(temp), 64 * 1024).use { output ->
                val buffer = ByteArray(4_096)
                var position = 0
                for (index in 0 until totalSamples) {
                    val audible = beepOnMs <= 0 || (index % periodSamples) < onSamples
                    var value = if (audible) {
                        kotlin.math.sin(2.0 * Math.PI * frequencyHz * index / SAMPLE_RATE) * amplitude
                    } else {
                        0.0
                    }
                    var fade = 1.0
                    if (index < fadeSamples) fade = index.toDouble() / fadeSamples
                    if (index > totalSamples - fadeSamples) {
                        fade = (totalSamples - index).toDouble() / fadeSamples
                    }
                    if (beepOnMs > 0 && audible) {
                        val positionInPeriod = index % periodSamples
                        if (positionInPeriod < fadeSamples) {
                            fade *= positionInPeriod.toDouble() / fadeSamples
                        }
                        val remainingInBeep = onSamples - positionInPeriod
                        if (remainingInBeep in 1..fadeSamples) {
                            fade *= remainingInBeep.toDouble() / fadeSamples
                        }
                    }
                    value *= fade
                    val sample = (value * 32767.0).roundToInt().coerceIn(-32768, 32767)
                    buffer[position++] = (sample and 0xFF).toByte()
                    buffer[position++] = ((sample shr 8) and 0xFF).toByte()
                    if (position == buffer.size) {
                        output.write(buffer, 0, position)
                        position = 0
                    }
                }
                if (position > 0) output.write(buffer, 0, position)
            }
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    suspend fun deleteUnusedCaches(keepUris: List<String?>) = withContext(Dispatchers.IO) {
        val keepNames = keepUris.filterNotNull().mapNotNull { uri ->
            runCatching { cacheKey(uri, queryMeta(uri)) + ".pcm" }.getOrNull()
        }.toSet()
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { file ->
            val stale = now - file.lastModified() > STALE_CACHE_MS
            if (file.name.endsWith(".tmp") || (file.name.endsWith(".pcm") && file.name !in keepNames && stale)) {
                file.delete()
            }
        }
    }

    private fun decode(uri: Uri, outputFile: File, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw AudioDecodeException("The file could not be opened. Re-select it in Audio settings.", e)
        }

        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("No audio track was found in this file.")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024).use { output ->
                val writer = PcmWriter(output, durationUs, onProgress)
                try {
                    if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                        decodeRaw(extractor, format, writer)
                    } else {
                        decodeWithCodec(extractor, format, writer)
                    }
                    writer.finish()
                } finally {
                    writer.close()
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeRaw(extractor: MediaExtractor, format: MediaFormat, writer: PcmWriter) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        writer.setSourceFormat(rate, channels, encoding)

        val buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            writer.write(buffer, size)
            writer.report(extractor.sampleTime)
            if (!extractor.advance()) break
        }
    }

    private fun decodeWithCodec(extractor: MediaExtractor, format: MediaFormat, writer: PcmWriter) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio"
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findDecoderForFormat(format)
            ?: throw AudioDecodeException("This phone has no decoder for $mime.")

        val codec = MediaCodec.createByCodecName(codecName)
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            var sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            writer.setSourceFormat(sourceRate, channels, encoding)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleTicks = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw AudioDecodeException("Decoder input buffer unavailable.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                presentationTimeUs,
                                0,
                            )
                            if (presentationTimeUs > 0) writer.report(presentationTimeUs)
                            extractor.advance()
                        }
                    }
                }

                var progressed = false
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outputFormat = codec.outputFormat
                        sourceRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        writer.setSourceFormat(sourceRate, channels, encoding)
                        continue
                    }
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER || outputIndex < 0) break

                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        writer.write(output, info.size)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    progressed = true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }

                if (progressed) {
                    idleTicks = 0
                } else {
                    idleTicks++
                    if (inputDone && idleTicks > MAX_IDLE_TICKS) {
                        throw AudioDecodeException("The decoder stalled while reading this file.")
                    }
                    Thread.sleep(2)
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun queryMeta(uriString: String): SourceMeta {
        val uri = Uri.parse(uriString)
        var name: String? = null
        var size = 0L
        var modified = 0L
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { modified = cursor.getLong(it) }
                }
            }
        } catch (_: Exception) {
        }

        if (size <= 0) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    size = descriptor.length
                }
            } catch (_: Exception) {
            }
        }
        return SourceMeta(
            displayName = name ?: uri.lastPathSegment ?: "Audio file",
            size = size,
            lastModified = modified,
        )
    }

    private fun cacheKey(uri: String, meta: SourceMeta): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val value = "$uri|${meta.size}|${meta.lastModified}|16k-mono-v1"
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun durationMs(byteLength: Long): Long =
        (byteLength * 1000L) / BYTES_PER_SECOND_16K_MONO

    private data class SourceMeta(
        val displayName: String,
        val size: Long,
        val lastModified: Long,
    )

    private class PcmWriter(
        private val output: BufferedOutputStream,
        private val durationUs: Long,
        private val onProgress: (Float) -> Unit,
    ) {
        private var sourceRate = 0
        private var channels = 0
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var resampler: LinearResampler? = null
        private var lastReportNanos = 0L
        private val sampleBytes = ByteArray(2)

        fun setSourceFormat(rate: Int, channelCount: Int, pcmEncoding: Int) {
            require(rate > 0) { "Invalid sample rate" }
            require(channelCount > 0) { "Invalid channel count" }
            if (rate != sourceRate) {
                resampler = LinearResampler(rate)
            }
            sourceRate = rate
            channels = channelCount
            encoding = pcmEncoding
        }

        fun write(buffer: ByteBuffer, size: Int) {
            if (size <= 0) return
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_FLOAT,
                AudioFormat.ENCODING_PCM_32BIT,
                -> 4
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                else -> throw AudioDecodeException("Unsupported PCM encoding: $encoding")
            }
            val frameBytes = bytesPerSample * channels
            if (frameBytes <= 0) return
            val frames = size / frameBytes
            val activeResampler = resampler ?: LinearResampler(sourceRate).also { resampler = it }

            for (index in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) {
                    val offset = index * frameBytes + channel * bytesPerSample
                    sum += readSample(buffer, offset)
                }
                activeResampler.push(sum / channels) { value -> emit(value) }
            }
        }

        private fun readSample(buffer: ByteBuffer, offset: Int): Float = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT ->
                ((buffer.get(offset).toInt() and 0xFF) - 128) / 128f
            AudioFormat.ENCODING_PCM_16BIT ->
                buffer.getShort(offset).toInt() / 32768f
            AudioFormat.ENCODING_PCM_FLOAT ->
                buffer.getFloat(offset)
            AudioFormat.ENCODING_PCM_32BIT ->
                buffer.getInt(offset) / 2147483648f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val b0 = buffer.get(offset).toInt() and 0xFF
                val b1 = buffer.get(offset + 1).toInt() and 0xFF
                val b2 = buffer.get(offset + 2).toInt() and 0xFF
                var value = b0 or (b1 shl 8) or (b2 shl 16)
                if (value and 0x800000 != 0) value = value or -0x1000000
                value / 8388608f
            }
            else -> 0f
        }

        private fun emit(value: Float) {
            val sample = (value.coerceIn(-1f, 1f) * 32767f).roundToInt()
            sampleBytes[0] = (sample and 0xFF).toByte()
            sampleBytes[1] = ((sample shr 8) and 0xFF).toByte()
            output.write(sampleBytes)
        }

        fun report(sampleTimeUs: Long) {
            if (durationUs <= 0 || sampleTimeUs <= 0) return
            val now = System.nanoTime()
            if (lastReportNanos != 0L && now - lastReportNanos < 200_000_000L) return
            lastReportNanos = now
            onProgress((sampleTimeUs.toFloat() / durationUs).coerceIn(0f, 0.99f))
        }

        fun finish() {
            onProgress(1f)
            output.flush()
        }

        fun close() {
            runCatching { output.flush() }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_TICKS = 2_000
        private const val BYTES_PER_SECOND_16K_MONO = 32_000L
        private const val MIN_PCM_BYTES = 3_200L
        private const val STALE_CACHE_MS = 30L * 24L * 60L * 60L * 1000L
        private const val SAMPLE_RATE = 16_000
        private const val TEST_GREETING_MS = 8_000
        private const val TEST_HOLD_MS = 6_000
        private const val TEST_GREETING_URI = "app://live-test/greeting"
        private const val TEST_HOLD_URI = "app://live-test/hold"
    }
}
