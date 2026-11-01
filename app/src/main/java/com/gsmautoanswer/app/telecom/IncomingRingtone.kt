package com.gsmautoanswer.app.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.gsmautoanswer.app.diagnostics.SessionLog

class IncomingRingtone(
    private val context: Context,
    private val log: SessionLog,
) {
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var active = false

    fun start() {
        if (active) return
        active = true
        val audioManager = context.getSystemService(AudioManager::class.java)
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Unit
            AudioManager.RINGER_MODE_VIBRATE -> startVibration()
            else -> startSound()
        }
    }

    fun stop() {
        active = false
        runCatching { ringtone?.stop() }
        ringtone = null
        stopVibration()
    }

    private fun startSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
            val r = RingtoneManager.getRingtone(context, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.isLooping = true
            r.play()
            ringtone = r
        } catch (e: Exception) {
            log.warn("Could not play the system ringtone: ${e.message}")
        }
    }

    private fun startVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            val pattern = longArrayOf(0L, 900L, 900L)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            vibrating = true
        } catch (e: Exception) {
            log.warn("Could not start vibration: ${e.message}")
        }
    }

    private fun stopVibration() {
        if (!vibrating) return
        vibrating = false
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }
}
