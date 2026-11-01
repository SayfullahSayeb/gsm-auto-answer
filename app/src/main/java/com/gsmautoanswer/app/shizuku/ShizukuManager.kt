package com.gsmautoanswer.app.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.gsmautoanswer.app.diagnostics.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.InputStream
import java.util.concurrent.TimeUnit

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
}

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val error: String?,
    val timedOut: Boolean = false,
) {
    val success: Boolean
        get() = exitCode == 0 && !timedOut

    val combined: String
        get() = buildString {
            if (output.isNotBlank()) append(output.trimEnd())
            if (!error.isNullOrBlank()) {
                if (isNotEmpty()) append('\n')
                append(error.trimEnd())
            }
            if (timedOut) {
                if (isNotEmpty()) append('\n')
                append("[timed out]")
            }
        }.ifBlank { "(no output)" }
}

class ShizukuManager(
    private val context: Context,
    private val log: SessionLog,
) {
    private val _state = MutableStateFlow(refreshState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        log.info("Shizuku permission result: $grantResult")
        refresh()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        log.info("Shizuku binder received")
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        log.warn("Shizuku binder died")
        refresh()
    }

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }.onFailure { log.warn("Could not attach Shizuku listeners: ${it.message}") }
    }

    fun refresh() {
        _state.value = refreshState()
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isReady(): Boolean = refreshState() == ShizukuState.READY

    fun serverVersion(): Int? = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getVersion() else null
    }.getOrNull()

    fun requestPermission() {
        val current = refreshState()
        if (current != ShizukuState.PERMISSION_REQUIRED) {
            refresh()
            return
        }
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { log.warn("Shizuku permission request failed: ${it.message}") }
    }

    fun openShizuku(): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launch)
            true
        }.getOrDefault(false)
    }

    /**
     * Runs a fixed, internal command as the Shizuku shell user. Callers must not pass
     * text derived from user input.
     */
    suspend fun shell(command: String, timeoutMs: Long = 60_000L): ShellResult =
        withContext(Dispatchers.IO) {
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                return@withContext ShellResult(-1, "", "Shizuku is not running")
            }
            if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
                return@withContext ShellResult(-1, "", "Shizuku 11 or newer is required")
            }
            val permission = runCatching { Shizuku.checkSelfPermission() }.getOrNull()
            if (permission != PackageManager.PERMISSION_GRANTED) {
                return@withContext ShellResult(-1, "", "Shizuku permission was not granted")
            }
            val process = try {
                Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            } catch (e: Exception) {
                log.warn("Shizuku command could not start: ${e.message}")
                return@withContext ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outReader = Thread { readCapped(process.inputStream, stdout) }
                .apply { name = "shizuku-stdout"; start() }
            val errReader = Thread { readCapped(process.errorStream, stderr) }
                .apply { name = "shizuku-stderr"; start() }

            var timedOut = false
            try {
                if (process is ShizukuRemoteProcess) {
                    timedOut = !process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    if (timedOut) process.destroy()
                } else {
                    process.waitFor()
                }
            } catch (e: Exception) {
                log.warn("Shizuku command wait failed: ${e.message}")
                runCatching { process.destroy() }
            }
            outReader.join(1_000L)
            errReader.join(1_000L)
            val code = runCatching { process.exitValue() }.getOrDefault(-1)
            ShellResult(
                exitCode = code,
                output = stdout.toString(),
                error = stderr.toString().ifBlank { null },
                timedOut = timedOut,
            )
        }

    suspend fun applySetup(packageName: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Invalid package name" }
        val commands = listOf(
            "pm grant $packageName android.permission.READ_PHONE_STATE",
            "pm grant $packageName android.permission.CALL_PHONE",
            "pm grant $packageName android.permission.ANSWER_PHONE_CALLS",
            "pm grant $packageName android.permission.POST_NOTIFICATIONS",
            "cmd role add-role-holder android.app.role.DIALER $packageName",
            "cmd deviceidle whitelist +$packageName",
            "cmd appops set $packageName USE_FULL_SCREEN_INTENT allow",
        )
        val builder = StringBuilder()
        for (command in commands) {
            val result = shell(command, timeoutMs = 20_000L)
            builder.appendLine("$ $command")
            builder.appendLine(result.combined)
            builder.appendLine("exit=${result.exitCode}")
        }
        log.info("Shizuku setup finished for $packageName")
        return builder.toString().trimEnd()
    }

    private fun readCapped(stream: InputStream, target: StringBuilder) {
        runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8_192)
                while (target.length < MAX_OUTPUT_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    val allowed = minOf(read, MAX_OUTPUT_CHARS - target.length)
                    target.append(buffer, 0, allowed)
                    if (allowed < read) break
                }
            }
        }
    }

    private fun refreshState(): ShizukuState {
        if (!isInstalled()) return ShizukuState.NOT_INSTALLED
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuState.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.READY
            } else {
                ShizukuState.PERMISSION_REQUIRED
            }
        } catch (_: Exception) {
            ShizukuState.NOT_RUNNING
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4_201
        const val MAX_OUTPUT_CHARS = 4_000_000
    }
}
