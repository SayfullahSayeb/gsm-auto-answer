package com.gsmautoanswer.app.core

enum class CallPhase {
    IDLE,
    RINGING,
    ANSWERING,
    GREETING,
    HOLD,
    CONVERSATION,
    ENDING,
    ENDED,
}

enum class CallEndReason {
    REMOTE,
    USER,
    TIMEOUT,
    INJECTION_FAILURE,
    ANSWER_FAILURE,
}

data class CallerInfo(
    val displayName: String?,
    val number: String?,
) {
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: number ?: "Unknown caller"
}

data class CallFlowState(
    val phase: CallPhase = CallPhase.IDLE,
    val caller: CallerInfo = CallerInfo(null, null),
    val autoAnswer: Boolean = false,
    val manual: Boolean = false,
    val countdownTotalMs: Long = 30_000L,
    val remainingMs: Long = 30_000L,
    val routeVerified: Boolean = false,
    val error: String? = null,
    val blockedReason: String? = null,
    val connectedAtElapsedMs: Long? = null,
    val endReason: CallEndReason? = null,
) {
    val isInCall: Boolean
        get() = phase == CallPhase.ANSWERING ||
            phase == CallPhase.GREETING ||
            phase == CallPhase.HOLD ||
            phase == CallPhase.CONVERSATION
}

data class CallFlowConfig(
    val countdownMs: Long = 30_000L,
)

sealed interface CallFlowEvent {
    data class Incoming(val caller: CallerInfo, val autoAnswer: Boolean) : CallFlowEvent
    data object AutoAnswerReady : CallFlowEvent
    data class AutoAnswerBlocked(val reason: String) : CallFlowEvent
    data object AnswerManually : CallFlowEvent
    data class CallActive(val atElapsedMs: Long) : CallFlowEvent
    data object RouteVerified : CallFlowEvent
    data object GreetingFinished : CallFlowEvent
    data class Tick(val remainingMs: Long) : CallFlowEvent
    data class Unmute(val atElapsedMs: Long) : CallFlowEvent
    data object EndCall : CallFlowEvent
    data object Decline : CallFlowEvent
    data object CallDisconnected : CallFlowEvent
    data class AudioFailed(val message: String) : CallFlowEvent
    data class AnswerFailed(val message: String) : CallFlowEvent
}

sealed interface CallEffect {
    data object AnswerCall : CallEffect
    data object RejectCall : CallEffect
    data object DisconnectCall : CallEffect
    data class SetMicMuted(val muted: Boolean) : CallEffect
    data object StartGreetingAudio : CallEffect
    data object StopAudio : CallEffect
    data class StartCountdown(val durationMs: Long) : CallEffect
    data object CancelCountdown : CallEffect
    data class NotifyError(val message: String) : CallEffect
}

data class CallFlowTransition(
    val state: CallFlowState,
    val effects: List<CallEffect>,
)

/**
 * Pure, serialised state machine for one injected call.
 *
 * The Telecom layer feeds events and executes the returned effects. Keeping this class free of
 * Android dependencies lets every stage be tested without a phone or a real call.
 */
class CallFlowMachine(
    private val config: CallFlowConfig = CallFlowConfig(),
    initialState: CallFlowState = CallFlowState(
        countdownTotalMs = config.countdownMs,
        remainingMs = config.countdownMs,
    ),
) {
    var state: CallFlowState = initialState
        private set

    fun reset() {
        state = CallFlowState(
            countdownTotalMs = config.countdownMs,
            remainingMs = config.countdownMs,
        )
    }

    fun reduce(event: CallFlowEvent): CallFlowTransition {
        val next = transition(state, event)
        state = next.state
        return next
    }

    private fun transition(current: CallFlowState, event: CallFlowEvent): CallFlowTransition {
        if (current.phase == CallPhase.ENDED) {
            return CallFlowTransition(current, emptyList())
        }

        return when (event) {
            is CallFlowEvent.Incoming -> {
                if (current.phase != CallPhase.IDLE) {
                    keep(current)
                } else {
                    CallFlowTransition(
                        state = CallFlowState(
                            phase = CallPhase.RINGING,
                            caller = event.caller,
                            autoAnswer = event.autoAnswer,
                            countdownTotalMs = config.countdownMs,
                            remainingMs = config.countdownMs,
                        ),
                        effects = emptyList(),
                    )
                }
            }

            CallFlowEvent.AutoAnswerReady -> {
                if (current.phase == CallPhase.RINGING &&
                    current.autoAnswer &&
                    current.blockedReason == null
                ) {
                    CallFlowTransition(
                        current.copy(phase = CallPhase.ANSWERING, manual = false),
                        listOf(CallEffect.AnswerCall, CallEffect.SetMicMuted(true)),
                    )
                } else {
                    keep(current)
                }
            }

            is CallFlowEvent.AutoAnswerBlocked -> {
                if (current.phase == CallPhase.RINGING && current.autoAnswer) {
                    CallFlowTransition(
                        current.copy(blockedReason = event.reason),
                        emptyList(),
                    )
                } else {
                    keep(current)
                }
            }

            CallFlowEvent.AnswerManually -> {
                if (current.phase == CallPhase.RINGING) {
                    CallFlowTransition(
                        current.copy(phase = CallPhase.ANSWERING, manual = true),
                        listOf(CallEffect.AnswerCall),
                    )
                } else {
                    keep(current)
                }
            }

            is CallFlowEvent.CallActive -> {
                when (current.phase) {
                    CallPhase.ANSWERING -> {
                        if (current.manual) {
                            CallFlowTransition(
                                current.copy(
                                    phase = CallPhase.CONVERSATION,
                                    connectedAtElapsedMs = event.atElapsedMs,
                                ),
                                listOf(CallEffect.SetMicMuted(false)),
                            )
                        } else {
                            CallFlowTransition(
                                current.copy(phase = CallPhase.GREETING),
                                listOf(
                                    CallEffect.SetMicMuted(true),
                                    CallEffect.StartGreetingAudio,
                                ),
                            )
                        }
                    }

                    CallPhase.RINGING -> {
                        CallFlowTransition(
                            current.copy(
                                phase = CallPhase.CONVERSATION,
                                manual = true,
                                connectedAtElapsedMs = event.atElapsedMs,
                            ),
                            listOf(CallEffect.SetMicMuted(false)),
                        )
                    }

                    else -> keep(current)
                }
            }

            CallFlowEvent.RouteVerified -> {
                if (current.phase == CallPhase.GREETING || current.phase == CallPhase.HOLD) {
                    keep(current.copy(routeVerified = true))
                } else {
                    keep(current)
                }
            }

            CallFlowEvent.GreetingFinished -> {
                if (current.phase == CallPhase.GREETING) {
                    CallFlowTransition(
                        current.copy(
                            phase = CallPhase.HOLD,
                            remainingMs = current.countdownTotalMs,
                            routeVerified = true,
                        ),
                        listOf(CallEffect.StartCountdown(current.countdownTotalMs)),
                    )
                } else {
                    keep(current)
                }
            }

            is CallFlowEvent.Tick -> {
                if (current.phase != CallPhase.HOLD) {
                    keep(current)
                } else {
                    val remaining = event.remainingMs.coerceAtLeast(0L)
                    if (remaining == 0L) {
                        val message = timeoutMessage(current.countdownTotalMs)
                        CallFlowTransition(
                            current.copy(
                                phase = CallPhase.ENDING,
                                remainingMs = 0L,
                                error = message,
                                endReason = CallEndReason.TIMEOUT,
                            ),
                            listOf(
                                CallEffect.CancelCountdown,
                                CallEffect.StopAudio,
                                CallEffect.DisconnectCall,
                                CallEffect.NotifyError(message),
                            ),
                        )
                    } else {
                        keep(current.copy(remainingMs = remaining))
                    }
                }
            }

            is CallFlowEvent.Unmute -> {
                if (current.phase == CallPhase.HOLD) {
                    CallFlowTransition(
                        current.copy(
                            phase = CallPhase.CONVERSATION,
                            connectedAtElapsedMs = event.atElapsedMs,
                            remainingMs = 0L,
                        ),
                        listOf(
                            CallEffect.CancelCountdown,
                            CallEffect.StopAudio,
                            CallEffect.SetMicMuted(false),
                        ),
                    )
                } else {
                    keep(current)
                }
            }

            CallFlowEvent.EndCall -> {
                when (current.phase) {
                    CallPhase.RINGING -> CallFlowTransition(
                        current.copy(
                            phase = CallPhase.ENDING,
                            endReason = CallEndReason.USER,
                        ),
                        listOf(CallEffect.RejectCall),
                    )

                    CallPhase.ANSWERING,
                    CallPhase.GREETING,
                    CallPhase.HOLD,
                    CallPhase.CONVERSATION,
                    -> CallFlowTransition(
                        current.copy(
                            phase = CallPhase.ENDING,
                            endReason = CallEndReason.USER,
                        ),
                        listOf(
                            CallEffect.CancelCountdown,
                            CallEffect.StopAudio,
                            CallEffect.DisconnectCall,
                        ),
                    )

                    else -> keep(current)
                }
            }

            CallFlowEvent.Decline -> {
                if (current.phase == CallPhase.RINGING) {
                    CallFlowTransition(
                        current.copy(
                            phase = CallPhase.ENDING,
                            endReason = CallEndReason.USER,
                        ),
                        listOf(CallEffect.RejectCall),
                    )
                } else {
                    keep(current)
                }
            }

            CallFlowEvent.CallDisconnected -> {
                if (current.phase == CallPhase.ENDING) {
                    CallFlowTransition(
                        current.copy(phase = CallPhase.ENDED),
                        listOf(CallEffect.CancelCountdown, CallEffect.StopAudio),
                    )
                } else if (current.phase != CallPhase.ENDED) {
                    CallFlowTransition(
                        current.copy(
                            phase = CallPhase.ENDED,
                            endReason = current.endReason ?: CallEndReason.REMOTE,
                        ),
                        listOf(CallEffect.CancelCountdown, CallEffect.StopAudio),
                    )
                } else {
                    keep(current)
                }
            }

            is CallFlowEvent.AudioFailed -> {
                if (current.isInCall) {
                    CallFlowTransition(
                        current.copy(
                            phase = CallPhase.ENDING,
                            error = event.message,
                            endReason = CallEndReason.INJECTION_FAILURE,
                        ),
                        listOf(
                            CallEffect.CancelCountdown,
                            CallEffect.StopAudio,
                            CallEffect.DisconnectCall,
                            CallEffect.NotifyError(event.message),
                        ),
                    )
                } else {
                    keep(current)
                }
            }

            is CallFlowEvent.AnswerFailed -> {
                if (current.phase == CallPhase.ANSWERING) {
                    CallFlowTransition(
                        current.copy(
                            phase = CallPhase.ENDING,
                            error = event.message,
                            endReason = CallEndReason.ANSWER_FAILURE,
                        ),
                        listOf(
                            CallEffect.StopAudio,
                            CallEffect.RejectCall,
                            CallEffect.NotifyError(event.message),
                        ),
                    )
                } else {
                    keep(current)
                }
            }
        }
    }

    private fun keep(current: CallFlowState): CallFlowTransition =
        CallFlowTransition(current, emptyList())

    private fun timeoutMessage(countdownMs: Long): String {
        val seconds = (countdownMs / 1000L).toInt()
        return "No Unmute within $seconds seconds. The call was ended."
    }
}
