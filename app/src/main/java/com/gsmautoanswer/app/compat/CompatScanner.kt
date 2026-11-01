package com.gsmautoanswer.app.compat

import android.os.Build
import android.util.Base64
import com.gsmautoanswer.app.audio.InjectionCapability
import com.gsmautoanswer.app.audio.InjectionCapabilityProbe
import com.gsmautoanswer.app.data.AppSettings
import com.gsmautoanswer.app.data.LiveTestResult
import com.gsmautoanswer.app.data.SettingsRepository
import com.gsmautoanswer.app.diagnostics.SessionLog
import com.gsmautoanswer.app.diagnostics.SetupChecks
import com.gsmautoanswer.app.shizuku.ShizukuManager
import com.gsmautoanswer.app.shizuku.ShizukuState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CompatVerdict {
    VERIFIED,
    LIKELY_SUPPORTED,
    FRAMEWORK_ONLY,
    NOT_SUPPORTED,
    CANDIDATE,
    TEST_FAILED,
    TEST_INCONCLUSIVE,
    UNKNOWN,
}

data class CompatReport(
    val verdict: CompatVerdict,
    val generatedAt: Long,
    val text: String,
    val policyKnown: Boolean,
    val frameworkRoute: Boolean,
    val telephonyTx: Boolean?,
    val incallMusic: Boolean?,
    val mixerIncallMusic: Boolean?,
    val halIncallMusic: Boolean?,
    val telephonyOutputPresent: Boolean,
    val shizukuState: ShizukuState,
    val policyFiles: List<String>,
    val liveTestRan: Boolean,
    val liveRouteVerified: Boolean,
    val liveResult: LiveTestResult,
) {
    val supported: Boolean
        get() = verdict == CompatVerdict.VERIFIED ||
            verdict == CompatVerdict.LIKELY_SUPPORTED
}

class CompatScanner(
    private val context: android.content.Context,
    private val settings: SettingsRepository,
    private val capabilityProbe: InjectionCapabilityProbe,
    private val shizuku: ShizukuManager,
    private val log: SessionLog,
) {
    suspend fun scan(): CompatReport {
        val local = capabilityProbe.probe()
        shizuku.refresh()
        val shizukuState = shizuku.state.value
        val current = settings.current

        var policyBlocks = emptyList<CompatFile>()
        var policyText: String? = null
        var policyFiles = emptyList<String>()
        var mixerText: String? = null
        var halFiles = emptyList<String>()
        var halIncallMusic: Boolean? = null
        var halTelephonyTx: Boolean? = null
        var dumpsysText: String? = null
        var pcmText: String? = null
        var scanMode = "local app"

        if (shizukuState == ShizukuState.READY) {
            scanMode = "Shizuku shell"
            val policyResult = shizuku.shell(POLICY_COMMAND, timeoutMs = 30_000L)
            policyBlocks = parseFileBlocks(policyResult.output)

            val mixerResult = shizuku.shell(MIXER_COMMAND, timeoutMs = 30_000L)
            mixerText = parseFileBlocks(mixerResult.output)
                .joinToString("\n") { it.content }
                .ifBlank { null }

            val halResult = shizuku.shell(HAL_COMMAND, timeoutMs = 60_000L)
            val halBlocks = parseFileBlocks(halResult.output).distinctBy { it.path }
            halFiles = halBlocks.map { it.path }
            var halFound = false
            for (block in halBlocks) {
                val bytes = decodeBase64(block.content) ?: continue
                halFound = true
                val ascii = bytes.toString(Charsets.ISO_8859_1).lowercase(Locale.US)
                if (ascii.contains("incall_music") ||
                    ascii.contains("incall-music") ||
                    ascii.contains("incallmusic") ||
                    ascii.contains("incall music")
                ) {
                    halIncallMusic = true
                }
                if (ascii.contains("telephony_tx") ||
                    ascii.contains("telephony-tx") ||
                    ascii.contains("telephony tx")
                ) {
                    halTelephonyTx = true
                }
            }
            if (halFound) {
                if (halIncallMusic == null) halIncallMusic = false
                if (halTelephonyTx == null) halTelephonyTx = false
            }

            dumpsysText = shizuku.shell(
                "dumpsys media.audio_policy",
                timeoutMs = 30_000L,
            ).output
            pcmText = shizuku.shell(
                "cat /proc/asound/pcm 2>/dev/null; echo @@SEP; ls -l /dev/snd 2>/dev/null",
                timeoutMs = 15_000L,
            ).output

            if (policyBlocks.isNotEmpty()) {
                val contents = policyBlocks.associate { it.path to it.content }
                val activePath = PolicyTreeSelector.selectActive(
                    dumpsys = dumpsysText.orEmpty(),
                    localPath = local.policyPath,
                    available = contents.keys,
                )
                val tree = PolicyTreeSelector.collect(activePath, contents)
                if (tree.isNotEmpty()) {
                    policyFiles = tree.keys.toList()
                    policyText = tree.values.joinToString("\n")
                }
            }
        }

        val telephonyTx = if (policyText != null) {
            policyText.containsFlag(TELEPHONY_TX)
        } else {
            local.policyHasTelephonyTx
        }
        val incallMusic = if (policyText != null) {
            policyText.containsFlag(INCALL_MUSIC)
        } else {
            local.policyHasIncallMusic
        }
        val mixerIncallMusic = if (mixerText != null) {
            mixerText.containsFlag(INCALL_MUSIC)
        } else {
            local.mixerHasIncallMusic
        }
        val policyKnown = telephonyTx != null && incallMusic != null
        val frameworkRoute = telephonyTx == true && incallMusic == true
        val vendorEvidence = mixerIncallMusic == true || halIncallMusic == true
        val vendorNegative = (mixerIncallMusic == false && halIncallMusic == false) ||
            (mixerIncallMusic == false && halIncallMusic == null) ||
            (mixerIncallMusic == null && halIncallMusic == false)
        val liveRan = current.liveTestStartedAtMs > 0L
        val routeVerified = current.liveTestRouteVerified
        val liveResult = current.liveTestResult

        val verdict = CompatVerdicts.classify(
            policyKnown = policyKnown,
            frameworkRoute = frameworkRoute,
            vendorEvidence = vendorEvidence,
            vendorNegative = vendorNegative,
            liveResult = liveResult,
            routeVerified = routeVerified,
        )

        val report = CompatReport(
            verdict = verdict,
            generatedAt = System.currentTimeMillis(),
            text = "",
            policyKnown = policyKnown,
            frameworkRoute = frameworkRoute,
            telephonyTx = telephonyTx,
            incallMusic = incallMusic,
            mixerIncallMusic = mixerIncallMusic,
            halIncallMusic = halIncallMusic,
            telephonyOutputPresent = local.telephonyDevice != null,
            shizukuState = shizukuState,
            policyFiles = policyFiles,
            liveTestRan = liveRan,
            liveRouteVerified = routeVerified,
            liveResult = liveResult,
        )
        val text = buildReport(
            report = report,
            local = local,
            settings = current,
            scanMode = scanMode,
            halFiles = halFiles,
            halTelephonyTx = halTelephonyTx,
            dumpsysText = dumpsysText,
            pcmText = pcmText,
            mixerText = mixerText,
            policyText = policyText,
        )
        log.info(
            "Compatibility scan: verdict=$verdict policy=$policyKnown route=$frameworkRoute " +
                "mixer=$mixerIncallMusic hal=$halIncallMusic shizuku=$shizukuState",
        )
        return report.copy(text = text)
    }

    private fun buildReport(
        report: CompatReport,
        local: InjectionCapability,
        settings: AppSettings,
        scanMode: String,
        halFiles: List<String>,
        halTelephonyTx: Boolean?,
        dumpsysText: String?,
        pcmText: String?,
        mixerText: String?,
        policyText: String?,
    ): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = packageInfo.versionName ?: "?"
        val builder = StringBuilder()

        builder.appendLine("GSM Auto-Answer compatibility report")
        builder.appendLine("Generated: ${format.format(Date(report.generatedAt))}")
        builder.appendLine("App: $version (${packageInfo.longVersionCode})")
        builder.appendLine("Device: ${manufacturer()} ${Build.MODEL} (${Build.DEVICE})")
        builder.appendLine("Build: ${Build.DISPLAY}")
        builder.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        builder.appendLine("Scan mode: $scanMode")
        builder.appendLine("Shizuku: ${report.shizukuState}${shizukuVersionText()}")
        builder.appendLine("Verdict: ${report.verdict}")
        builder.appendLine()

        builder.appendLine("[Purchase decision]")
        builder.appendLine(decisionText(report.verdict))
        builder.appendLine()

        builder.appendLine("[Framework audio route]")
        builder.appendLine(
            "Active config source: " +
                (report.policyFiles.firstOrNull() ?: local.policyPath ?: "unknown"),
        )
        builder.appendLine("AUDIO_DEVICE_OUT_TELEPHONY_TX: ${tri(report.telephonyTx)}")
        builder.appendLine("incall_music mix port / flag: ${tri(report.incallMusic)}")
        builder.appendLine(
            "Telephony output visible to this app: ${yesNo(report.telephonyOutputPresent)}",
        )
        builder.appendLine("Policy files:")
        if (report.policyFiles.isEmpty()) {
            builder.appendLine("  ${local.policyPath ?: "not readable"}")
        } else {
            report.policyFiles.forEach { builder.appendLine("  $it") }
        }
        policyText?.let { text ->
            val relevant = text.lineSequence()
                .filter { it.contains(INCALL_MUSIC, true) || it.contains("telephony", true) }
                .take(12)
                .toList()
            if (relevant.isNotEmpty()) {
                builder.appendLine("Policy matches:")
                relevant.forEach { builder.appendLine("  ${it.trim().take(190)}") }
            }
        }
        dumpsysText?.let { text ->
            val relevant = text.lineSequence()
                .filter {
                    it.contains("Telephony Tx", true) ||
                        it.contains("incall_music", true) ||
                        it.contains("mVoiceTxIncallMusic", true)
                }
                .take(12)
                .toList()
            builder.appendLine("dumpsys media.audio_policy matches:")
            if (relevant.isEmpty()) {
                builder.appendLine("  (no telephony/incall lines)")
            } else {
                relevant.forEach { builder.appendLine("  ${it.trim().take(190)}") }
            }
        }
        builder.appendLine()

        builder.appendLine("[Vendor layer]")
        builder.appendLine("Mixer: ${local.mixerPath ?: "not readable"}")
        builder.appendLine("  incall_music path: ${tri(report.mixerIncallMusic)}")
        builder.appendLine(
            "Vendor HAL: " +
                if (halFiles.isNotEmpty()) {
                    halFiles.joinToString(", ")
                } else {
                    local.halPath ?: "not readable by the app"
                },
        )
        builder.appendLine("  incall_music string: ${tri(report.halIncallMusic)}")
        builder.appendLine("  telephony_tx string: ${tri(halTelephonyTx)}")
        if (mixerText != null) {
            val relevant = mixerText.lineSequence()
                .filter { it.contains(INCALL_MUSIC, true) }
                .take(8)
                .toList()
            if (relevant.isNotEmpty()) {
                builder.appendLine("Mixer matches:")
                relevant.forEach { builder.appendLine("  ${it.trim().take(190)}") }
            }
        }
        pcmText?.let { text ->
            val pcm = text.substringBefore("@@SEP").trim()
            if (pcm.isEmpty()) {
                builder.appendLine("Kernel PCM: not readable by the scan user")
            } else {
                val playback = pcm.lineSequence().count { it.contains("playback", true) }
                val capture = pcm.lineSequence().count { it.contains("capture", true) }
                val voice = pcm.lineSequence()
                    .filter { it.contains("voice", true) || it.contains("call", true) }
                    .take(8)
                    .toList()
                builder.appendLine("Kernel PCM: $playback playback / $capture capture")
                voice.forEach { builder.appendLine("  ${it.trim().take(190)}") }
            }
        }
        builder.appendLine()

        builder.appendLine("[App and permissions]")
        builder.appendLine("Default phone app: ${yesNo(SetupChecks.isDefaultDialer(context))}")
        builder.appendLine("Call notifications: ${yesNo(SetupChecks.notificationsAllowed(context))}")
        builder.appendLine("Battery exempt: ${yesNo(SetupChecks.isBatteryExempt(context))}")
        builder.appendLine("Installed as system app: ${yesNo(local.isSystemApp)}")
        builder.appendLine("MODIFY_PHONE_STATE: ${yesNo(local.hasModifyPhoneState)}")
        builder.appendLine("MODIFY_AUDIO_ROUTING: ${yesNo(local.hasModifyAudioRouting)}")
        builder.appendLine("Audio mode now: ${local.audioModeName}")
        builder.appendLine("Outputs:")
        if (local.outputDevices.isEmpty()) {
            builder.appendLine("  (none reported)")
        } else {
            local.outputDevices.forEach {
                builder.appendLine("  - ${it.typeName} / ${it.productName}")
            }
        }
        builder.appendLine()

        builder.appendLine("[Settings]")
        builder.appendLine(
            "SIM for auto-answer: " +
                if (settings.usesSpecificSim) {
                    settings.simLabel ?: "subscription ${settings.simSubscriptionId}"
                } else {
                    "all SIMs"
                },
        )
        builder.appendLine(
            "Live test armed: " +
                if (settings.isLiveTestArmed) {
                    "yes, ${settings.liveTestArmedRemainingMs / 60_000L} min remaining"
                } else {
                    "no"
                },
        )
        builder.appendLine(
            "Last live test: " +
                if (!report.liveTestRan) {
                    "not run"
                } else {
                    "${report.liveResult} at " +
                        format.format(Date(settings.liveTestStartedAtMs)) +
                        "; telephony route verified: ${yesNo(report.liveRouteVerified)}" +
                        (settings.liveTestError?.let { "; error: $it" } ?: "")
                },
        )
        builder.appendLine()

        builder.appendLine("[Limits]")
        builder.appendLine(
            "The scan reads configuration. It cannot prove the modem/DSP forwards app audio.",
        )
        builder.appendLine(
            "Only a live call from another phone proves caller-side playback. Never judge by " +
                "this phone's own speaker; the app never routes caller audio there.",
        )
        builder.appendLine(
            "A normal install cannot hold MODIFY_AUDIO_ROUTING on most ROMs. If the live test " +
                "cannot reach the telephony device, the result is inconclusive, not a pass.",
        )

        return builder.toString().trimEnd()
    }

    private fun decisionText(verdict: CompatVerdict): String = when (verdict) {
        CompatVerdict.VERIFIED ->
            "PASS — a caller heard the live test tone through the cellular uplink."
        CompatVerdict.LIKELY_SUPPORTED ->
            "CANDIDATE — framework and vendor evidence are present. Confirm with a live call " +
                "before buying."
        CompatVerdict.FRAMEWORK_ONLY ->
            "FAIL (likely) — the framework route exists but the vendor mixer/HAL has no " +
                "incall-music path. The modem will probably discard app audio."
        CompatVerdict.NOT_SUPPORTED ->
            "FAIL — this ROM exposes no telephony-TX/incall-music route. App audio cannot " +
                "reach a caller with this app."
        CompatVerdict.CANDIDATE ->
            "UNCONFIRMED — a framework route exists but the vendor layer could not be read. " +
                "Use Shizuku and run a live call test."
        CompatVerdict.TEST_FAILED ->
            "FAIL — the framework route verified during the live call, but the caller heard " +
                "nothing. The modem/DSP dropped the audio."
        CompatVerdict.TEST_INCONCLUSIVE ->
            "INCONCLUSIVE — the live test could not verify the telephony uplink. Check setup " +
                "and permissions before judging the device."
        CompatVerdict.UNKNOWN ->
            "UNKNOWN — the audio policy could not be read. Install and start Shizuku for a " +
                "full scan, then run a live call test."
    }

    private fun shizukuVersionText(): String =
        shizuku.serverVersion()?.let { " (server $it)" } ?: ""

    private fun decodeBase64(block: String): ByteArray? = runCatching {
        val compact = block.filterNot { it.isWhitespace() }
        if (compact.length < 16) return null
        Base64.decode(compact, Base64.DEFAULT)
    }.getOrNull()

    private fun parseFileBlocks(output: String): List<CompatFile> {
        val blocks = mutableListOf<CompatFile>()
        var path: String? = null
        val content = StringBuilder()
        for (line in output.lineSequence()) {
            if (line.startsWith(FILE_MARKER)) {
                if (path != null) blocks += CompatFile(path, content.toString())
                path = line.removePrefix(FILE_MARKER).trim()
                content.setLength(0)
            } else if (path != null) {
                content.appendLine(line)
            }
        }
        if (path != null) blocks += CompatFile(path, content.toString())
        return blocks.distinctBy { it.path }
    }

    private fun String.containsFlag(flag: String): Boolean =
        contains(flag, ignoreCase = true) ||
            contains(flag.replace('_', '-'), ignoreCase = true) ||
            contains(flag.replace("_", ""), ignoreCase = true)

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun tri(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }

    private data class CompatFile(val path: String, val content: String)

    private companion object {
        const val FILE_MARKER = "@@FILE:"
        const val TELEPHONY_TX = "AUDIO_DEVICE_OUT_TELEPHONY_TX"
        const val INCALL_MUSIC = "incall_music"

        val POLICY_COMMAND = """
            for f in /vendor/etc/audio_policy_configuration*.xml /vendor/etc/audio_policy*.xml /odm/etc/audio_policy_configuration*.xml /odm/etc/audio_policy*.xml /system/etc/audio_policy_configuration*.xml /system/etc/audio_policy*.xml; do
              [ -f "${'$'}f" ] || continue
              printf '\n@@FILE:%s\n' "${'$'}f"
              cat "${'$'}f" 2>/dev/null
            done
        """.trimIndent()

        val MIXER_COMMAND = """
            for f in /vendor/etc/mixer_paths*.xml /vendor/etc/mixer_path*.xml /odm/etc/mixer_paths*.xml /system/etc/mixer_paths*.xml; do
              [ -f "${'$'}f" ] || continue
              printf '\n@@FILE:%s\n' "${'$'}f"
              cat "${'$'}f" 2>/dev/null
            done
        """.trimIndent()

        val HAL_COMMAND = """
            for f in /vendor/lib64/libaudioproxy.so /vendor/lib/libaudioproxy.so /vendor/lib64/libaudio-ril.so /vendor/lib/libaudio-ril.so /vendor/lib64/hw/audio.primary.*.so /vendor/lib/hw/audio.primary.*.so; do
              [ -f "${'$'}f" ] || continue
              size=${'$'}(wc -c < "${'$'}f" 2>/dev/null || echo 0)
              [ "${'$'}size" -gt 0 ] 2>/dev/null || continue
              [ "${'$'}size" -le 2097152 ] 2>/dev/null || continue
              printf '\n@@FILE:%s\n' "${'$'}f"
              base64 "${'$'}f" 2>/dev/null
            done
        """.trimIndent()
    }
    private fun manufacturer(): String =
        Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.US) }

}
