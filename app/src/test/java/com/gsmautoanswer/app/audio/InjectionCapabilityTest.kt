package com.gsmautoanswer.app.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionCapabilityTest {

    private fun capability(
        permissions: Boolean = true,
        telephony: Boolean = true,
        policy: Boolean = telephony,
        mixer: Boolean? = true,
    ) = InjectionCapability(
        permissionsReady = permissions,
        hasModifyPhoneState = permissions,
        hasModifyAudioRouting = permissions,
        isSystemApp = true,
        telephonyDevice = if (telephony) {
            AudioDeviceSummary(AudioDeviceInfo.TYPE_TELEPHONY, "telephony uplink", "telephony_tx")
        } else {
            null
        },
        policyHasTelephonyTx = policy,
        policyHasIncallMusic = policy,
        mixerHasIncallMusic = mixer,
        halHasIncallMusic = true,
        policyPath = "/vendor/etc/audio_policy_configuration.xml",
        mixerPath = "/vendor/etc/mixer_paths.xml",
        halPath = "/vendor/lib64/hw/audio.primary.so",
        audioMode = AudioManager.MODE_IN_CALL,
        audioModeName = "MODE_IN_CALL",
        outputDevices = emptyList(),
    )

    @Test
    fun `ready capability allows auto answer`() {
        val ready = capability()
        assertEquals(InjectionStatus.READY, ready.status)
        assertTrue(ready.isReadyForAutoAnswer())
        assertNull(ready.blockingReason())
    }

    @Test
    fun `missing privileged permissions blocks`() {
        val blocked = capability(permissions = false, telephony = false)
        assertEquals(InjectionStatus.PERMISSION_MISSING, blocked.status)
        assertFalse(blocked.isReadyForAutoAnswer())
        assertNotNull(blocked.blockingReason())
    }

    @Test
    fun `missing telephony output and policy blocks`() {
        val blocked = capability(telephony = false, mixer = null)
        assertEquals(InjectionStatus.POLICY_MISSING, blocked.status)
        assertFalse(blocked.isReadyForAutoAnswer())
        assertNotNull(blocked.blockingReason())
    }

    @Test
    fun `policy uplink without an enumerated device still needs the mixer`() {
        val policyOnly = capability(telephony = false, policy = true, mixer = false)
        assertEquals(InjectionStatus.MIXER_MISSING, policyOnly.status)
        assertFalse(policyOnly.isReadyForAutoAnswer())
        assertNotNull(policyOnly.blockingReason())
    }

    @Test
    fun `missing mixer path blocks until a live call passes`() {
        val mixerMissing = capability(mixer = false)
        assertEquals(InjectionStatus.MIXER_MISSING, mixerMissing.status)
        assertFalse(mixerMissing.isReadyForAutoAnswer())
        assertNotNull(mixerMissing.blockingReason())
    }

    @Test
    fun `unknown mixer evidence does not fabricate a missing path`() {
        val unknownMixer = capability(mixer = null)
        assertEquals(InjectionStatus.READY, unknownMixer.status)
        assertNull(unknownMixer.blockingReason())
    }
}
