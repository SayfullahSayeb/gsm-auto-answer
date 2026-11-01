package com.gsmautoanswer.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.gsmautoanswer.app.diagnostics.SessionLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams 16 kHz mono PCM caches into a 48 kHz stereo AudioTrack explicitly routed to
 * [AudioDeviceInfo.TYPE_TELEPHONY].
 *
 * The player fails closed: it refuses to run if the track is not verifiably routed to the
 * telephony uplink. It never falls back to the speaker.
 */
class CallerAudioPlayback(
    private val context: Context,
    private val log: SessionLog,
    private val greetingFile: File,
    private val holdFile: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onRouteVerified(route: String)
        fun onGreetingFinished()
        fun onFatalError(message: String)
    }

    private val stopRequested = AtomicBoolean(false)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ runPlayback() }, "gsm-auto-answer-uplink").apply { start() }
    }

    fun stopAndWait(maxWaitMs: Long = 1_500L) {
        stopRequested.set(true)
        runCatching { track?.pause() }
        val worker = thread
        if (worker != null && worker !== Thread.currentThread()) {
            worker.join(maxWaitMs)
            if (worker.isAlive) {
                log.warn("Audio thread did not stop in ${maxWaitMs}ms; forcing track stop")
                runCatching { track?.stop() }
                worker.join(300L)
            }
        }
    }

    private fun runPlayback() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GsmAutoAnswer:callerAudio",
        )
        var audioTrack: AudioTrack? = null
        var greetingSource: PcmFileSource? = null
        var holdSource: PcmFileSource? = null
        var failure: String? = null
        var greetingFinished = false

        try {
            wakeLock.acquire(MAX_WAKE_LOCK_MS)
            if (greetingFile.length() <= 0L) throw PlaybackException("The custom message cache is empty.")
            if (holdFile.length() <= 0L) throw PlaybackException("The waiting music cache is empty.")

            val audioManager = context.getSystemService(AudioManager::class.java)
            if (!awaitCallAudioMode(audioManager)) {
                throw PlaybackException(
                    "The phone is not in a cellular call (${InjectionCapabilityProbe.audioModeName(audioManager.mode)}).",
                )
            }

            val telephony = awaitTelephonyOutput(audioManager)
                ?: throw PlaybackException(
                    "No telephony uplink output is exposed by this ROM.",
                )

            log.info(
                "Starting uplink audio: telephony=${telephony.productName}, " +
                    "mode=${InjectionCapabilityProbe.audioModeName(audioManager.mode)}",
            )

            val minBuffer = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                throw PlaybackException("AudioTrack rejected the 48 kHz stereo format ($minBuffer).")
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(OUTPUT_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, OUTPUT_CHUNK_BYTES * 2))
                .build()
            track = audioTrack

            // Stay silent until the telephony route is verified: if the framework
            // falls back to the earpiece, the caller must never hear this stream.
            if (audioTrack.setVolume(0f) != AudioTrack.SUCCESS) {
                throw PlaybackException("AudioTrack could not be silenced for route verification.")
            }

            if (!audioTrack.setPreferredDevice(telephony)) {
                throw PlaybackException(
                    "The telecom stack rejected the telephony output.",
                )
            }

            audioTrack.play()
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                throw PlaybackException("AudioTrack did not enter the playing state.")
            }

            val route = awaitTelephonyRoute(audioTrack, audioManager)
                ?: throw PlaybackException(
                    "Audio was not routed to the telephony uplink. " +
                        "The vendor audio policy/mixer is missing an incall-music path.",
                )
            if (audioTrack.setVolume(1f) != AudioTrack.SUCCESS) {
                throw PlaybackException("AudioTrack volume could not be restored after routing.")
            }
            listener.onRouteVerified(route)
            log.info("Uplink audio route verified: $route")

            greetingSource = PcmFileSource(greetingFile, loop = false)
            holdSource = PcmFileSource(holdFile, loop = true)

            val input = ShortArray(INPUT_CHUNK_SAMPLES)
            val output = ByteArray(OUTPUT_CHUNK_BYTES)
            var playingGreeting = true
            var chunksSinceRouteCheck = 0
            var consecutiveMissingRoutes = 0

            while (!stopRequested.get()) {
                chunksSinceRouteCheck++
                if (chunksSinceRouteCheck >= ROUTE_RECHECK_CHUNKS) {
                    chunksSinceRouteCheck = 0
                    val current = audioTrack.routedDevice
                    when {
                        current == null -> {
                            consecutiveMissingRoutes++
                            if (consecutiveMissingRoutes >= MAX_MISSING_ROUTE_CHECKS) {
                                throw PlaybackException(
                                    "The telephony route disappeared during playback. " +
                                        "The call was ended rather than playing locally.",
                                )
                            }
                        }
                        current.type != AudioDeviceInfo.TYPE_TELEPHONY -> throw PlaybackException(
                            "Caller audio moved to " +
                                "${InjectionCapabilityProbe.deviceTypeName(current.type)}. " +
                                "The call was ended rather than playing locally.",
                        )
                        else -> consecutiveMissingRoutes = 0
                    }
                }

                val source = if (playingGreeting) greetingSource else holdSource
                val read = source.read(input, input.size)

                if (read <= 0) {
                    if (playingGreeting) {
                        playingGreeting = false
                        if (!greetingFinished) {
                            greetingFinished = true
                            listener.onGreetingFinished()
                        }
                        continue
                    }
                    throw PlaybackException("The waiting music cache ended unexpectedly.")
                }

                if (playingGreeting && read < input.size) {
                    playingGreeting = false
                }

                val bytes = convertToStereo48k(input, read, output)
                var offset = 0
                while (offset < bytes && !stopRequested.get()) {
                    val written = audioTrack.write(output, offset, bytes - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        throw PlaybackException("AudioTrack write failed ($written).")
                    }
                    if (written == 0) {
                        Thread.sleep(5L)
                    } else {
                        offset += written
                    }
                }

                if (!playingGreeting && !greetingFinished) {
                    greetingFinished = true
                    listener.onGreetingFinished()
                }
            }
        } catch (e: PlaybackException) {
            if (!stopRequested.get()) failure = e.message
            log.error("Uplink audio failed: ${e.message}")
        } catch (e: Exception) {
            if (!stopRequested.get()) failure = e.message ?: e.javaClass.simpleName
            log.error("Uplink audio exception: ${e.message}")
        } finally {
            runCatching { greetingSource?.close() }
            runCatching { holdSource?.close() }
            runCatching { audioTrack?.pause() }
            runCatching { audioTrack?.flush() }
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            track = null
            if (wakeLock.isHeld) runCatching { wakeLock.release() }
            log.info("Uplink audio stopped")
            failure?.let {
                if (!stopRequested.get()) {
                    runCatching { listener.onFatalError(it) }
                }
            }
        }
    }

    private fun awaitTelephonyRoute(
        audioTrack: AudioTrack,
        audioManager: AudioManager,
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + ROUTE_TIMEOUT_MS
        var lastType = -1
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested.get()) {
            val device = audioTrack.routedDevice
            if (device != null) {
                lastType = device.type
                if (device.type == AudioDeviceInfo.TYPE_TELEPHONY) {
                    return "${device.productName} (${InjectionCapabilityProbe.deviceTypeName(device.type)})"
                }
            }
            Thread.sleep(50L)
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString { "${InjectionCapabilityProbe.deviceTypeName(it.type)}/${it.productName}" }
        log.warn(
            "Telephony route not reached. Last routed type=$lastType; outputs=$outputs",
        )
        return null
    }

    private fun awaitCallAudioMode(audioManager: AudioManager): Boolean {
        val deadline = SystemClock.elapsedRealtime() + MODE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested.get()) {
            if (audioManager.mode == AudioManager.MODE_IN_CALL) return true
            Thread.sleep(50L)
        }
        return audioManager.mode == AudioManager.MODE_IN_CALL
    }

    private fun awaitTelephonyOutput(audioManager: AudioManager): AudioDeviceInfo? {
        val deadline = SystemClock.elapsedRealtime() + DEVICE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline && !stopRequested.get()) {
            val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (device != null) return device
            Thread.sleep(50L)
        }
        return null
    }

    private fun convertToStereo48k(input: ShortArray, count: Int, output: ByteArray): Int {
        var position = 0
        for (index in 0 until count) {
            val value = input[index].toInt()
            val low = (value and 0xFF).toByte()
            val high = ((value shr 8) and 0xFF).toByte()
            repeat(UPSAMPLE_FACTOR) {
                output[position++] = low
                output[position++] = high
                output[position++] = low
                output[position++] = high
            }
        }
        return position
    }

    private class PcmFileSource(
        private val file: File,
        private val loop: Boolean,
    ) {
        private var stream: InputStream = open()

        fun read(target: ShortArray, count: Int): Int {
            var filled = 0
            while (filled < count) {
                val low = stream.read()
                if (low < 0) {
                    if (loop) {
                        close()
                        stream = open()
                        continue
                    }
                    break
                }
                val high = stream.read()
                if (high < 0) break
                target[filled++] = (((high shl 8) or low) and 0xFFFF).toShort()
            }
            return filled
        }

        fun close() {
            runCatching { stream.close() }
        }

        private fun open(): InputStream =
            BufferedInputStream(FileInputStream(file), 32 * 1024)
    }

    private class PlaybackException(message: String) : Exception(message)

    companion object {
        private const val OUTPUT_SAMPLE_RATE = 48_000
        private const val INPUT_SAMPLE_RATE = 16_000
        private const val UPSAMPLE_FACTOR = OUTPUT_SAMPLE_RATE / INPUT_SAMPLE_RATE
        private const val INPUT_CHUNK_SAMPLES = INPUT_SAMPLE_RATE / 50 // 20 ms
        private const val OUTPUT_CHUNK_BYTES = INPUT_CHUNK_SAMPLES * UPSAMPLE_FACTOR * 4
        private const val ROUTE_TIMEOUT_MS = 2_000L
        private const val MODE_TIMEOUT_MS = 2_000L
        private const val DEVICE_TIMEOUT_MS = 1_000L
        private const val ROUTE_RECHECK_CHUNKS = 5
        private const val MAX_MISSING_ROUTE_CHECKS = 5
        private const val MAX_WAKE_LOCK_MS = 30L * 60L * 1000L
    }
}
