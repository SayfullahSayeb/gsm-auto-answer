package com.gsmautoanswer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimPolicyTest {
    @Test
    fun allSimsAllowsEveryCall() {
        assertTrue(SimPolicy.isAllowed(AppSettings.ALL_SIMS, 1))
        assertTrue(SimPolicy.isAllowed(AppSettings.ALL_SIMS, 2))
        assertTrue(SimPolicy.isAllowed(AppSettings.ALL_SIMS, null))
    }

    @Test
    fun specificSimRequiresMatchingSubscription() {
        assertTrue(SimPolicy.isAllowed(2, 2))
        assertFalse(SimPolicy.isAllowed(2, 1))
        assertFalse(SimPolicy.isAllowed(2, null))
    }

    @Test
    fun accountIdSlotFallback() {
        assertEquals(1, SimPolicy.slotFromAccountId("SIM1"))
        assertEquals(2, SimPolicy.slotFromAccountId("sim 2"))
        assertEquals(1, SimPolicy.slotFromAccountId("Sim1"))
        assertNull(SimPolicy.slotFromAccountId("icc-123456"))
        assertNull(SimPolicy.slotFromAccountId(null))
    }
}
