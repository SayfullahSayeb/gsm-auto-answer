package com.gsmautoanswer.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.AppGraph
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.audio.InjectionCapability
import com.gsmautoanswer.app.audio.InjectionCapabilityProbe
import com.gsmautoanswer.app.data.AppSettings
import com.gsmautoanswer.app.data.LiveTestResult
import com.gsmautoanswer.app.diagnostics.SetupChecks
import com.gsmautoanswer.app.util.SystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var graph: AppGraph
    private lateinit var reportText: TextView
    private lateinit var logText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var refreshButton: MaterialButton
    private lateinit var copyButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private var latestReport = ""
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph = (application as AutoAnswerApp).graph
        setContentView(R.layout.activity_diagnostics)
        SystemBars.apply(findViewById(R.id.diag_root))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        reportText = findViewById(R.id.report_text)
        logText = findViewById(R.id.log_text)
        progress = findViewById(R.id.progress)
        refreshButton = findViewById(R.id.refresh_button)
        copyButton = findViewById(R.id.copy_button)
        shareButton = findViewById(R.id.share_button)

        copyButton.isEnabled = false
        shareButton.isEnabled = false

        refreshButton.setOnClickListener { runChecks() }
        copyButton.setOnClickListener { copyReport() }
        shareButton.setOnClickListener { shareReport() }

        runChecks()
    }

    override fun onResume() {
        super.onResume()
        if (!running && latestReport.isEmpty()) {
            runChecks()
        }
    }

    private fun runChecks() {
        if (running) return
        running = true
        progress.isVisible = true
        refreshButton.isEnabled = false
        copyButton.isEnabled = false
        shareButton.isEnabled = false
        reportText.text = getString(R.string.diagnostics_running)

        lifecycleScope.launch {
            try {
                val capability = graph.capabilityProbe.probe()
                val report = withContext(Dispatchers.IO) {
                    buildReport(capability)
                }
                latestReport = report
                reportText.text = report
                val log = graph.log.tail(140)
                logText.text = log.ifBlank { getString(R.string.diagnostics_no_log) }
                copyButton.isEnabled = true
                shareButton.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportText.text = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
                progress.isVisible = false
                refreshButton.isEnabled = true
            }
        }
    }

    private fun buildReport(
        capability: InjectionCapability,
    ): String {
        val settings = graph.settings.current
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val version = packageInfo.versionName ?: "?"
        val builder = StringBuilder()

        builder.appendLine("GSM Auto-Answer diagnostics")
        builder.appendLine(
            "Generated: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
        )
        builder.appendLine("App version: $version (${packageInfo.longVersionCode})")
        builder.appendLine("Device: ${manufacturer()} ${Build.MODEL}")
        builder.appendLine("Build: ${Build.DISPLAY}")
        builder.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        builder.appendLine()

        builder.appendLine("[Setup]")
        builder.appendLine(
            "Default phone app: " + yesNo(SetupChecks.isDefaultDialer(this)),
        )
        builder.appendLine(
            "Call notifications: " + yesNo(SetupChecks.notificationsAllowed(this)),
        )
        builder.appendLine(
            "Battery optimization exempt: " + yesNo(SetupChecks.isBatteryExempt(this)),
        )
        builder.appendLine("Installed as system app: " + yesNo(capability.isSystemApp))
        builder.appendLine(
            "MODIFY_PHONE_STATE: " + yesNo(
                isGranted(Manifest.permission.MODIFY_PHONE_STATE),
            ),
        )
        builder.appendLine(
            "MODIFY_AUDIO_ROUTING: " + yesNo(
                isGranted(InjectionCapabilityProbe.PERMISSION_MODIFY_AUDIO_ROUTING),
            ),
        )
        val liveVerified = settings.liveTestResult == LiveTestResult.PASSED &&
            settings.liveTestStartedAtMs > 0L &&
            settings.liveTestRouteVerified
        builder.appendLine("Live caller test verified: ${yesNo(liveVerified)}")
        builder.appendLine("Automatic answer usable: " +
            yesNo(capability.isReadyForAutoAnswer() || liveVerified))
        builder.appendLine(
            "Blocking reason: " +
                if (liveVerified) {
                    "none (live caller test passed on this device)"
                } else {
                    capability.blockingReason() ?: "none"
                },
        )
        builder.appendLine()

        builder.appendLine("[Caller-audio route]")
        builder.appendLine("Status: ${capability.status}")
        val telephony = capability.telephonyDevice
        builder.appendLine(
            "Telephony uplink output: " +
                if (telephony != null) {
                    "${telephony.productName} (${telephony.typeName})"
                } else {
                    "not exposed"
                },
        )
        builder.appendLine("Audio policy: ${capability.policyPath ?: "not readable"}")
        builder.appendLine(
            "  AUDIO_DEVICE_OUT_TELEPHONY_TX: " + triState(capability.policyHasTelephonyTx),
        )
        builder.appendLine(
            "  incall_music mix port: " + triState(capability.policyHasIncallMusic),
        )
        builder.appendLine("Vendor mixer: ${capability.mixerPath ?: "not readable"}")
        builder.appendLine(
            "  incall_music route: " + triState(capability.mixerHasIncallMusic),
        )
        builder.appendLine("Vendor audio HAL: ${capability.halPath ?: "not readable"}")
        builder.appendLine(
            "  incall_music support string: " + triState(capability.halHasIncallMusic),
        )
        builder.appendLine("Audio mode now: ${capability.audioModeName} (${capability.audioMode})")
        builder.appendLine("Audio outputs:")
        if (capability.outputDevices.isEmpty()) {
            builder.appendLine("  (none reported)")
        } else {
            capability.outputDevices.forEach {
                builder.appendLine("  - ${it.typeName} / ${it.productName}")
            }
        }
        builder.appendLine()

        builder.appendLine("[Settings]")
        builder.appendLine("Auto-answer enabled: ${yesNo(settings.autoAnswerEnabled)}")
        builder.appendLine(
            "SIM for auto-answer: " +
                if (settings.usesSpecificSim) {
                    settings.simLabel ?: "subscription ${settings.simSubscriptionId}"
                } else {
                    "all SIMs"
                },
        )
        builder.appendLine("Live test result: ${settings.liveTestResult}")
        builder.appendLine("Live test route verified: ${yesNo(settings.liveTestRouteVerified)}")
        builder.appendLine("Unmute window: ${settings.countdownSeconds} seconds")
        builder.appendLine("Answer delay: ${settings.answerDelaySeconds} seconds")
        builder.appendLine("Custom message: " + describeAudio(settings, greeting = true))
        builder.appendLine("Waiting music: " + describeAudio(settings, greeting = false))

        return builder.toString().trimEnd()
    }

    private fun describeAudio(settings: AppSettings, greeting: Boolean): String {
        val uri = if (greeting) settings.greetingUri else settings.holdUri
        if (uri.isNullOrBlank()) return "not selected"
        val cached = runCatching { graph.audioCache.cachedFileFor(uri) }.getOrNull()
        return if (cached != null) {
            "${cached.displayName}, ${cached.durationMs / 1000.0} s, " +
                "${cached.file.length()} bytes cached"
        } else {
            "selected, no prepared cache yet"
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun triState(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }

    private fun copyReport() {
        if (latestReport.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.diagnostics_share_subject), latestReport),
        )
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        if (latestReport.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_share_subject))
            putExtra(Intent.EXTRA_TEXT, latestReport)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share)))
        }
    }
    private fun manufacturer(): String =
        Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.US) }

}
