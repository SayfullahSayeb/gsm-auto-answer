package com.gsmautoanswer.app.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionLog(context: Context) {
    private val file = File(context.filesDir, "session.log")
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    init {
        trimIfNeeded()
    }

    fun info(message: String) = write("I", message)

    fun warn(message: String) = write("W", message)

    fun error(message: String) = write("E", message)

    fun tail(maxLines: Int = 200): String = synchronized(lock) {
        if (!file.exists()) return ""
        file.readLines().takeLast(maxLines).joinToString("\n")
    }

    fun clear() = synchronized(lock) {
        if (file.exists()) file.delete()
    }

    private fun write(level: String, message: String) {
        Log.println(
            when (level) {
                "E" -> Log.ERROR
                "W" -> Log.WARN
                else -> Log.INFO
            },
            "GsmAutoAnswer",
            message,
        )
        synchronized(lock) {
            try {
                val line = "${timeFormat.format(Date())} $level $message\n"
                file.appendText(line)
                if (file.length() > MAX_BYTES) {
                    val keep = file.readLines().takeLast(400)
                    file.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            } catch (_: Exception) {
                // Logging must never break the call flow.
            }
        }
    }

    private fun trimIfNeeded() {
        synchronized(lock) {
            try {
                if (file.exists() && file.length() > MAX_BYTES) {
                    val keep = file.readLines().takeLast(400)
                    file.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 128 * 1024L
    }
}
