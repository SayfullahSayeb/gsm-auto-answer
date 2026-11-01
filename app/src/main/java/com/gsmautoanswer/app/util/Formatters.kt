package com.gsmautoanswer.app.util

import android.telephony.PhoneNumberUtils
import java.util.Locale
import kotlin.math.ceil

fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

fun formatCallDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatRemainingSeconds(remainingMs: Long): Int =
    ceil(remainingMs.coerceAtLeast(0L) / 1000.0).toInt()

fun formatPhoneNumber(number: String?): String? {
    val raw = number?.takeIf { it.isNotBlank() } ?: return null
    return try {
        PhoneNumberUtils.formatNumber(raw, Locale.getDefault().country) ?: raw
    } catch (_: Exception) {
        raw
    }
}

fun maskNumber(number: String?): String =
    if (number?.any { it.isDigit() } == true) "***" else "unknown"
