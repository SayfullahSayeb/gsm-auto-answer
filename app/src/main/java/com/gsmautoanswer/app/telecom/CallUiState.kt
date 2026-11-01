package com.gsmautoanswer.app.telecom

import com.gsmautoanswer.app.core.CallEndReason
import com.gsmautoanswer.app.core.CallPhase
import com.gsmautoanswer.app.core.CallerInfo

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val caller: CallerInfo? = null,
    val autoAnswer: Boolean = false,
    val manual: Boolean = false,
    val passive: Boolean = false,
    val preparing: Boolean = false,
    val prepProgress: Float? = null,
    val remainingMs: Long = 0L,
    val countdownTotalMs: Long = 30_000L,
    val routeVerified: Boolean = false,
    val error: String? = null,
    val blockedReason: String? = null,
    val connectedAtElapsedMs: Long? = null,
    val endReason: CallEndReason? = null,
) {
    val visible: Boolean
        get() = phase != CallPhase.IDLE

    val isRinging: Boolean
        get() = phase == CallPhase.RINGING

    val isEnded: Boolean
        get() = phase == CallPhase.ENDED

    val showCountdown: Boolean
        get() = phase == CallPhase.HOLD

    val micShouldBeMuted: Boolean
        get() = phase == CallPhase.ANSWERING ||
            phase == CallPhase.GREETING ||
            phase == CallPhase.HOLD
}
