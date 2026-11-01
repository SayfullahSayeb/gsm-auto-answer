package com.gsmautoanswer.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.gsmautoanswer.app.diagnostics.SessionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class InjectionStatus {
    READY,
    MIXER_MISSING,
    PERMISSION_MISSING,
    POLICY_MISSING,
}

data class AudioDeviceSummary(
    val type: Int,
    val typeName: String,
    val productName: String,
)

data class InjectionCapability(
    val permissionsReady: Boolean,
    val hasModifyPhoneState: Boolean,
    val hasModifyAudioRouting: Boolean,
    val isSystemApp: Boolean,
    val telephonyDevice: AudioDeviceSummary?,
    val policyHasTelephonyTx: Boolean?,
    val policyHasIncallMusic: Boolean?,
    val mixerHasIncallMusic: Boolean?,
    val halHasIncallMusic: Boolean?,
    val policyPath: String?,
    val mixerPath: String?,
    val halPath: String?,
    val audioMode: Int,
    val audioModeName: String,
    val outputDevices: List<AudioDeviceSummary>,
) {
    val policyRoutePresent: Boolean
        get() = policyHasTelephonyTx == true && policyHasIncallMusic == true
    val routePresent: Boolean get() = telephonyDevice != null || policyRoutePresent
    val mixerPathPresent: Boolean get() = mixerHasIncallMusic == true
    val mixerPathKnownMissing: Boolean get() = mixerHasIncallMusic == false

    val status: InjectionStatus
        get() = when {
            !permissionsReady -> InjectionStatus.PERMISSION_MISSING
            !routePresent -> InjectionStatus.POLICY_MISSING
            mixerPathKnownMissing -> InjectionStatus.MIXER_MISSING
            else -> InjectionStatus.READY
        }

    fun isReadyForAutoAnswer(): Boolean = status == InjectionStatus.READY

    fun blockingReason(): String? = when (status) {
        InjectionStatus.READY -> null
        InjectionStatus.PERMISSION_MISSING ->
            "Caller audio is not verified on this device. Run the live call test in " +
                "Device check; a caller-heard pass enables auto-answer."
        InjectionStatus.POLICY_MISSING ->
            "This ROM does not expose a telephony uplink output for app audio."
        InjectionStatus.MIXER_MISSING ->
            "The vendor mixer has no caller-audio path. Run the live call test; " +
                "a caller-heard pass enables auto-answer."
    }
}

class InjectionCapabilityProbe(
    private val context: Context,
    private val log: SessionLog,
) {
    suspend fun probe(): InjectionCapability = withContext(Dispatchers.IO) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toSummary() }
        }.getOrElse {
            log.warn("Could not enumerate audio outputs: ${it.message}")
            emptyList()
        }
        val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }

        val hasModifyPhoneState = isGranted(Manifest.permission.MODIFY_PHONE_STATE)
        val hasModifyAudioRouting = isGranted(PERMISSION_MODIFY_AUDIO_ROUTING)
        val isSystemApp = try {
            context.applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) {
            false
        }

        val policyFile = firstReadable(
            // Some vendors ship the active policy under a variant name.
            "/vendor/etc/audio_policy_configuration_sec.xml",
            "/vendor/etc/audio_policy_configuration.xml",
            "/odm/etc/audio_policy_configuration_sec.xml",
            "/odm/etc/audio_policy_configuration.xml",
            "/system/etc/audio_policy_configuration.xml",
        )
        val mixerFiles = findMixerFiles()
        val policyText = policyFile?.let { readPolicyTree(it) }
        val mixerText = mixerFiles
            .joinToString("\n") { file ->
                runCatching { file.readText() }.getOrElse { "" }
            }
            .ifBlank { null }
        val halFile = firstHalFile()
        val halText = halFile?.let {
            runCatching { it.readBytes().toString(Charsets.ISO_8859_1) }.getOrNull()
        }

        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        val result = InjectionCapability(
            permissionsReady = hasModifyPhoneState && hasModifyAudioRouting,
            hasModifyPhoneState = hasModifyPhoneState,
            hasModifyAudioRouting = hasModifyAudioRouting,
            isSystemApp = isSystemApp,
            telephonyDevice = telephony,
            policyHasTelephonyTx = policyText?.contains("AUDIO_DEVICE_OUT_TELEPHONY_TX") == true,
            policyHasIncallMusic = policyText?.contains("incall_music", ignoreCase = true) == true,
            mixerHasIncallMusic = mixerText?.let {
                it.contains("incall_music", ignoreCase = true) ||
                    it.contains("incall-music", ignoreCase = true) ||
                    it.contains("incallmusic", ignoreCase = true)
            },
            halHasIncallMusic = halText?.let {
                it.contains("INCALL_MUSIC", ignoreCase = true) ||
                    it.contains("incallmusic", ignoreCase = true)
            },
            policyPath = policyFile?.absolutePath,
            mixerPath = mixerFiles.joinToString(", ") { it.absolutePath }.ifEmpty { null },
            halPath = halFile?.absolutePath,
            audioMode = mode,
            audioModeName = audioModeName(mode),
            outputDevices = outputs,
        )
        log.info(
            "Injection probe: status=${result.status}, perms=${result.permissionsReady}, " +
                "telephony=${telephony != null}, mixer=${result.mixerHasIncallMusic}, " +
                "hal=${result.halHasIncallMusic}, policy=$policyFile",
        )
        result
    }

    private fun readPolicyTree(
        file: File,
        depth: Int = 0,
        seen: MutableSet<String> = mutableSetOf(),
    ): String? {
        if (depth > 3 || !seen.add(file.absolutePath)) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val builder = StringBuilder(text)
        val includeRegex = Regex("""<xi:include\s+[^>]*href\s*=\s*"([^"]+)"""")
        includeRegex.findAll(text).forEach { match ->
            val href = match.groupValues[1]
            val included = if (href.startsWith("/")) File(href) else File(file.parentFile, href)
            if (included.isFile && included.canRead()) {
                readPolicyTree(included, depth + 1, seen)?.let {
                    builder.append('\n').append(it)
                }
            }
        }
        return builder.toString()
    }

    private fun findMixerFiles(): List<File> {
        val directories = listOf("/vendor/etc", "/odm/etc", "/system/etc")
        val found = mutableListOf<File>()
        for (path in directories) {
            val files = File(path).listFiles() ?: continue
            files.filterTo(found) {
                it.isFile && it.canRead() && it.extension == "xml" &&
                    (it.name.startsWith("mixer_paths") || it.name.startsWith("mixer_path"))
            }
        }
        return found.distinctBy { it.absolutePath }
    }

    private fun firstHalFile(): File? {
        val directories = listOf(
            "/vendor/lib64/hw",
            "/vendor/lib/hw",
            "/odm/lib64/hw",
            "/odm/lib/hw",
            "/vendor/bin/hw",
            "/odm/bin/hw",
        )
        for (path in directories) {
            val files = File(path).listFiles() ?: continue
            files.firstOrNull {
                it.isFile && it.canRead() &&
                    it.length() in 1..(64L * 1024 * 1024) &&
                    (it.name.startsWith("audio.primary") ||
                        it.name.startsWith("android.hardware.audio"))
            }?.let { return it }
        }
        return null
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun firstReadable(vararg paths: String): File? =
        paths.map(::File).firstOrNull { it.isFile && it.canRead() }

    private fun AudioDeviceInfo.toSummary() = AudioDeviceSummary(
        type = type,
        typeName = deviceTypeName(type),
        productName = productName?.toString() ?: "unknown",
    )

    companion object {
        const val PERMISSION_MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"

        fun deviceTypeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony uplink"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
            else -> "type-$type"
        }

        fun audioModeName(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            else -> "mode-$mode"
        }
    }
}
