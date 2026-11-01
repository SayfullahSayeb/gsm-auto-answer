package com.gsmautoanswer.app.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTreeSelectorTest {
    private val active = "/vendor/etc/audio_policy_configuration_sec.xml"
    private val base = "/vendor/etc/audio_policy_configuration_base.xml"
    private val bluetooth = "/vendor/etc/bluetooth_audio_policy_configuration.xml"

    @Test
    fun selectsConfigSourceReportedByDumpsys() {
        val dumpsys = "AudioPolicyManager: Config source: $active\n"
        val selected = PolicyTreeSelector.selectActive(
            dumpsys = dumpsys,
            localPath = null,
            available = setOf(active, base),
        )
        assertEquals(active, selected)
    }

    @Test
    fun fallsBackToLocalPolicyPath() {
        val selected = PolicyTreeSelector.selectActive(
            dumpsys = "No source line",
            localPath = active,
            available = setOf(active),
        )
        assertEquals(active, selected)
    }

    @Test
    fun fallsBackToKnownPaths() {
        val selected = PolicyTreeSelector.selectActive(
            dumpsys = "",
            localPath = null,
            available = setOf(base, bluetooth),
        )
        assertNull(selected)
    }

    @Test
    fun collectsOnlyTheActiveIncludeTree() {
        val contents = mapOf(
            active to """
                <audioPolicyConfiguration>
                    <xi:include href="bluetooth_audio_policy_configuration.xml"/>
                </audioPolicyConfiguration>
            """.trimIndent(),
            bluetooth to "<audioPolicyConfiguration/>",
            base to """
                <devicePort tagName="Telephony Tx"
                    type="AUDIO_DEVICE_OUT_TELEPHONY_TX" role="sink"/>
                <mixPort name="incall_music_uplink" role="source"/>
            """.trimIndent(),
        )
        val tree = PolicyTreeSelector.collect(active, contents)
        assertTrue(tree.containsKey(active))
        assertTrue(tree.containsKey(bluetooth))
        assertFalse(tree.containsKey(base))
        assertFalse(tree.values.joinToString("\n").contains("TELEPHONY_TX"))
    }

    @Test
    fun resolvesParentRelativeIncludes() {
        val contents = mapOf(
            "/vendor/etc/policy/active.xml" to
                """<xi:include href="../shared/ports.xml"/>""",
            "/vendor/etc/shared/ports.xml" to """<xi:include href="more.xml"/>""",
            "/vendor/etc/shared/more.xml" to "<ports/>",
        )
        val tree = PolicyTreeSelector.collect("/vendor/etc/policy/active.xml", contents)
        assertEquals(
            listOf(
                "/vendor/etc/policy/active.xml",
                "/vendor/etc/shared/ports.xml",
                "/vendor/etc/shared/more.xml",
            ),
            tree.keys.toList(),
        )
    }
}
