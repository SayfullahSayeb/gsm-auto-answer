package com.gsmautoanswer.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFlowMachineTest {

    private val caller = CallerInfo(displayName = "Test Caller", number = "+8801700000000")

    private fun machine(countdownMs: Long = 30_000L): CallFlowMachine =
        CallFlowMachine(CallFlowConfig(countdownMs = countdownMs))

    private fun CallFlowMachine.toHold(countdownMs: Long = 30_000L): CallFlowMachine {
        reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        reduce(CallFlowEvent.AutoAnswerReady)
        reduce(CallFlowEvent.CallActive(atElapsedMs = 1_000L))
        reduce(CallFlowEvent.RouteVerified)
        reduce(CallFlowEvent.GreetingFinished)
        return this
    }

    @Test
    fun `auto answer follows greeting hold unmute sequence`() {
        val machine = machine()

        val incoming = machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        assertEquals(CallPhase.RINGING, incoming.state.phase)
        assertTrue(incoming.state.autoAnswer)
        assertTrue(incoming.effects.isEmpty())

        val ready = machine.reduce(CallFlowEvent.AutoAnswerReady)
        assertEquals(CallPhase.ANSWERING, ready.state.phase)
        assertEquals(
            listOf(CallEffect.AnswerCall, CallEffect.SetMicMuted(true)),
            ready.effects,
        )

        val active = machine.reduce(CallFlowEvent.CallActive(atElapsedMs = 2_000L))
        assertEquals(CallPhase.GREETING, active.state.phase)
        assertEquals(
            listOf(CallEffect.SetMicMuted(true), CallEffect.StartGreetingAudio),
            active.effects,
        )
        assertNull(active.state.connectedAtElapsedMs)

        val verified = machine.reduce(CallFlowEvent.RouteVerified)
        assertTrue(verified.state.routeVerified)

        val hold = machine.reduce(CallFlowEvent.GreetingFinished)
        assertEquals(CallPhase.HOLD, hold.state.phase)
        assertEquals(30_000L, hold.state.remainingMs)
        assertEquals(listOf(CallEffect.StartCountdown(30_000L)), hold.effects)

        val tick = machine.reduce(CallFlowEvent.Tick(12_500L))
        assertEquals(CallPhase.HOLD, tick.state.phase)
        assertEquals(12_500L, tick.state.remainingMs)
        assertTrue(tick.effects.isEmpty())

        val unmute = machine.reduce(CallFlowEvent.Unmute(atElapsedMs = 42_000L))
        assertEquals(CallPhase.CONVERSATION, unmute.state.phase)
        assertEquals(42_000L, unmute.state.connectedAtElapsedMs)
        assertEquals(
            listOf(
                CallEffect.CancelCountdown,
                CallEffect.StopAudio,
                CallEffect.SetMicMuted(false),
            ),
            unmute.effects,
        )
    }

    @Test
    fun `timeout stops audio and disconnects`() {
        val machine = machine().toHold()

        val timeout = machine.reduce(CallFlowEvent.Tick(0L))
        assertEquals(CallPhase.ENDING, timeout.state.phase)
        assertEquals(CallEndReason.TIMEOUT, timeout.state.endReason)
        assertEquals(
            listOf(
                CallEffect.CancelCountdown,
                CallEffect.StopAudio,
                CallEffect.DisconnectCall,
                CallEffect.NotifyError("No Unmute within 30 seconds. The call was ended."),
            ),
            timeout.effects,
        )

        val ended = machine.reduce(CallFlowEvent.CallDisconnected)
        assertEquals(CallPhase.ENDED, ended.state.phase)
        assertEquals(CallEndReason.TIMEOUT, ended.state.endReason)
        assertEquals(
            listOf(CallEffect.CancelCountdown, CallEffect.StopAudio),
            ended.effects,
        )
    }

    @Test
    fun `manual answer never starts injection and is unmuted`() {
        val machine = machine()

        machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = false))
        val answering = machine.reduce(CallFlowEvent.AnswerManually)
        assertEquals(
            listOf(CallEffect.AnswerCall),
            answering.effects,
        )

        val active = machine.reduce(CallFlowEvent.CallActive(atElapsedMs = 5_000L))
        assertEquals(CallPhase.CONVERSATION, active.state.phase)
        assertTrue(active.state.manual)
        assertEquals(listOf(CallEffect.SetMicMuted(false)), active.effects)
        assertEquals(5_000L, active.state.connectedAtElapsedMs)

        val ignored = machine.reduce(CallFlowEvent.GreetingFinished)
        assertEquals(CallPhase.CONVERSATION, ignored.state.phase)
        assertTrue(ignored.effects.isEmpty())
    }

    @Test
    fun `blocked auto answer can still be answered normally`() {
        val machine = machine()

        machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        val blocked = machine.reduce(CallFlowEvent.AutoAnswerBlocked("No telephony uplink"))
        assertEquals(CallPhase.RINGING, blocked.state.phase)
        assertEquals("No telephony uplink", blocked.state.blockedReason)

        val lateReady = machine.reduce(CallFlowEvent.AutoAnswerReady)
        assertEquals(CallPhase.RINGING, lateReady.state.phase)
        assertTrue(lateReady.effects.isEmpty())

        machine.reduce(CallFlowEvent.AnswerManually)
        val active = machine.reduce(CallFlowEvent.CallActive(atElapsedMs = 1L))
        assertEquals(CallPhase.CONVERSATION, active.state.phase)
        assertTrue(active.state.manual)
    }

    @Test
    fun `unmute is ignored before countdown starts`() {
        val machine = machine()
        machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        machine.reduce(CallFlowEvent.AutoAnswerReady)
        machine.reduce(CallFlowEvent.CallActive(atElapsedMs = 1L))

        val result = machine.reduce(CallFlowEvent.Unmute(atElapsedMs = 2L))
        assertEquals(CallPhase.GREETING, result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `remote hangup during hold cleans up`() {
        val machine = machine().toHold()

        val result = machine.reduce(CallFlowEvent.CallDisconnected)
        assertEquals(CallPhase.ENDED, result.state.phase)
        assertEquals(CallEndReason.REMOTE, result.state.endReason)
        assertEquals(
            listOf(CallEffect.CancelCountdown, CallEffect.StopAudio),
            result.effects,
        )
    }

    @Test
    fun `audio failure ends the call`() {
        val machine = machine().toHold()

        val result = machine.reduce(CallFlowEvent.AudioFailed("No telephony route"))
        assertEquals(CallPhase.ENDING, result.state.phase)
        assertEquals(CallEndReason.INJECTION_FAILURE, result.state.endReason)
        assertEquals(
            listOf(
                CallEffect.CancelCountdown,
                CallEffect.StopAudio,
                CallEffect.DisconnectCall,
                CallEffect.NotifyError("No telephony route"),
            ),
            result.effects,
        )
    }

    @Test
    fun `configured countdown is used`() {
        val machine = machine(countdownMs = 45_000L)
        machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        machine.reduce(CallFlowEvent.AutoAnswerReady)
        machine.reduce(CallFlowEvent.CallActive(atElapsedMs = 1L))
        val hold = machine.reduce(CallFlowEvent.GreetingFinished)
        assertEquals(45_000L, hold.state.remainingMs)
        assertEquals(listOf(CallEffect.StartCountdown(45_000L)), hold.effects)

        val timeout = machine.reduce(CallFlowEvent.Tick(0L))
        assertEquals(
            "No Unmute within 45 seconds. The call was ended.",
            timeout.state.error,
        )
    }

    @Test
    fun `user can end during greeting and hold`() {
        val greetingMachine = machine()
        greetingMachine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        greetingMachine.reduce(CallFlowEvent.AutoAnswerReady)
        greetingMachine.reduce(CallFlowEvent.CallActive(atElapsedMs = 1L))

        val greetingEnd = greetingMachine.reduce(CallFlowEvent.EndCall)
        assertEquals(CallPhase.ENDING, greetingEnd.state.phase)
        assertEquals(CallEndReason.USER, greetingEnd.state.endReason)
        assertEquals(
            listOf(
                CallEffect.CancelCountdown,
                CallEffect.StopAudio,
                CallEffect.DisconnectCall,
            ),
            greetingEnd.effects,
        )

        val holdMachine = machine().toHold()
        val holdEnd = holdMachine.reduce(CallFlowEvent.EndCall)
        assertEquals(CallPhase.ENDING, holdEnd.state.phase)
        assertEquals(
            listOf(
                CallEffect.CancelCountdown,
                CallEffect.StopAudio,
                CallEffect.DisconnectCall,
            ),
            holdEnd.effects,
        )
    }

    @Test
    fun `decline rejects a ringing call`() {
        val machine = machine()
        machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = false))

        val result = machine.reduce(CallFlowEvent.Decline)
        assertEquals(CallPhase.ENDING, result.state.phase)
        assertEquals(listOf(CallEffect.RejectCall), result.effects)

        val ended = machine.reduce(CallFlowEvent.CallDisconnected)
        assertEquals(CallPhase.ENDED, ended.state.phase)
    }

    @Test
    fun `answer failure rejects and reports`() {
        val machine = machine()
        machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        machine.reduce(CallFlowEvent.AutoAnswerReady)

        val result = machine.reduce(CallFlowEvent.AnswerFailed("Answer rejected by Telecom"))
        assertEquals(CallPhase.ENDING, result.state.phase)
        assertEquals(CallEndReason.ANSWER_FAILURE, result.state.endReason)
        assertEquals(
            listOf(
                CallEffect.StopAudio,
                CallEffect.RejectCall,
                CallEffect.NotifyError("Answer rejected by Telecom"),
            ),
            result.effects,
        )
    }

    @Test
    fun `duplicate and out of phase events are ignored`() {
        val machine = machine().toHold()
        machine.reduce(CallFlowEvent.Unmute(atElapsedMs = 1L))

        val secondUnmute = machine.reduce(CallFlowEvent.Unmute(atElapsedMs = 2L))
        assertTrue(secondUnmute.effects.isEmpty())
        val lateTick = machine.reduce(CallFlowEvent.Tick(0L))
        assertEquals(CallPhase.CONVERSATION, lateTick.state.phase)
        val lateIncoming = machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = true))
        assertEquals(CallPhase.CONVERSATION, lateIncoming.state.phase)

        val ended = machine.reduce(CallFlowEvent.CallDisconnected)
        assertEquals(CallPhase.ENDED, ended.state.phase)
        val afterEnd = machine.reduce(CallFlowEvent.EndCall)
        assertTrue(afterEnd.effects.isEmpty())
        assertEquals(CallPhase.ENDED, afterEnd.state.phase)
    }

    @Test
    fun `auto answer ready requires auto mode`() {
        val machine = machine()
        machine.reduce(CallFlowEvent.Incoming(caller, autoAnswer = false))

        val result = machine.reduce(CallFlowEvent.AutoAnswerReady)
        assertEquals(CallPhase.RINGING, result.state.phase)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `reset returns to idle with configured countdown`() {
        val machine = machine(countdownMs = 12_000L).toHold(countdownMs = 12_000L)
        machine.reset()

        assertEquals(CallPhase.IDLE, machine.state.phase)
        assertEquals(12_000L, machine.state.countdownTotalMs)
        assertEquals(12_000L, machine.state.remainingMs)
        assertFalse(machine.state.isInCall)
    }
}
