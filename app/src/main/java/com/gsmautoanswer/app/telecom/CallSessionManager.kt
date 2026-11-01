package com.gsmautoanswer.app.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.gsmautoanswer.app.audio.AudioCacheRepository
import com.gsmautoanswer.app.audio.AudioDecodeException
import com.gsmautoanswer.app.audio.CachedAudio
import com.gsmautoanswer.app.audio.CallerAudioPlayback
import com.gsmautoanswer.app.audio.InjectionCapabilityProbe
import com.gsmautoanswer.app.core.CallEffect
import com.gsmautoanswer.app.core.CallEndReason
import com.gsmautoanswer.app.core.CallFlowConfig
import com.gsmautoanswer.app.core.CallFlowEvent
import com.gsmautoanswer.app.core.CallFlowMachine
import com.gsmautoanswer.app.core.CallFlowState
import com.gsmautoanswer.app.core.CallPhase
import com.gsmautoanswer.app.core.CallerInfo
import com.gsmautoanswer.app.data.LiveTestResult
import com.gsmautoanswer.app.data.SettingsRepository
import com.gsmautoanswer.app.data.SimPolicy
import com.gsmautoanswer.app.diagnostics.SessionLog
import com.gsmautoanswer.app.ui.CallActivity
import com.gsmautoanswer.app.util.formatRemainingSeconds
import com.gsmautoanswer.app.util.maskNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CallSessionManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val audioCache: AudioCacheRepository,
    private val capabilityProbe: InjectionCapabilityProbe,
    private val notifier: CallNotifier,
    private val log: SessionLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eventQueue = Channel<QueuedEvent>(Channel.UNLIMITED)
    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var machine: CallFlowMachine? = null
    @Volatile private var sessionId = 0L
    private var activeCall: Call? = null
    private var callCallback: Call.Callback? = null
    private var inCallService: InCallService? = null
    private var passive = false
    private var preparing = false
    private var prepProgress: Float? = null
    private var manualReason: String? = null
    private var preparedGreeting: CachedAudio? = null
    private var preparedHold: CachedAudio? = null
    private var playback: CallerAudioPlayback? = null
    private val ringtone = IncomingRingtone(context, log)
    private var prepJob: Job? = null
    private var countdownJob: Job? = null
    private var answerTimeoutJob: Job? = null
    private var resetJob: Job? = null
    private var uiLaunchAttempted = false
    private var holdUiLaunchAttempted = false
    private var endedHandled = false
    private var sessionTestMode = false
    private var lastIncomingKey: List<Any?>? = null
    private var lastOngoingKey: List<Any?>? = null

    private val telecomManager = context.getSystemService(TelecomManager::class.java)

    init {
        scope.launch {
            for (queued in eventQueue) {
                if (queued.sessionId != sessionId) continue
                val currentMachine = machine ?: continue
                if (passive) continue
                val transition = currentMachine.reduce(queued.event)
                log.info("Call event ${queued.event::class.simpleName} -> ${transition.state.phase}")
                publish(transition.state)
                executeEffects(transition.effects, transition.state, queued.sessionId)
            }
        }
    }

    fun attachService(service: InCallService) {
        inCallService = service
        service.calls.forEach(::onCallAdded)
    }

    fun detachService(service: InCallService) {
        if (inCallService === service) inCallService = null
    }

    fun onCallAdded(call: Call) {
        if (call === activeCall) return

        val current = activeCall
        if (current != null) {
            if (_uiState.value.phase == CallPhase.ENDED || callState(current) == Call.STATE_DISCONNECTED) {
                teardownSession()
            } else {
                if (callState(call) == Call.STATE_RINGING) {
                    runCatching { call.reject(false, null) }
                    log.warn("Declined a second incoming call while a session is active")
                    notifier.showExtraCallDeclined()
                }
                return
            }
        }

        sessionId++
        val id = sessionId
        activeCall = call
        passive = false
        preparing = false
        prepProgress = null
        manualReason = null
        preparedGreeting = null
        preparedHold = null
        endedHandled = false
        sessionTestMode = false
        uiLaunchAttempted = false
        holdUiLaunchAttempted = false
        lastIncomingKey = null
        lastOngoingKey = null

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (call === activeCall && id == sessionId) handleStateChanged(state)
            }
        }
        callCallback = callback
        runCatching { call.registerCallback(callback) }

        val details = call.details
        val state = callState(call)
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        log.info(
            "Call added: incoming=$incoming state=${stateName(state)} " +
                "number=${maskNumber(details.handle?.schemeSpecificPart)}",
        )

        if (incoming && state == Call.STATE_RINGING) {
            startIncoming(call, id)
        } else {
            startPassive(call, state, id, incoming)
        }
    }

    fun onCallRemoved(call: Call) {
        if (call !== activeCall) return
        log.info("Call removed")
        if (passive) {
            finishPassive(endReason = null)
            return
        }
        enqueue(CallFlowEvent.CallDisconnected)
        runCatching {
            callCallback?.let { call.unregisterCallback(it) }
        }
        callCallback = null
    }

    fun onAnswerManually() {
        val currentMachine = machine ?: return
        if (currentMachine.state.phase != CallPhase.RINGING) return
        prepJob?.cancel()
        prepJob = null
        preparing = false
        prepProgress = null
        publish(currentMachine.state)
        enqueue(CallFlowEvent.AnswerManually)
    }

    fun onDecline() {
        if (machine?.state?.phase == CallPhase.RINGING) {
            prepJob?.cancel()
            prepJob = null
            preparing = false
            prepProgress = null
            enqueue(CallFlowEvent.Decline)
        }
    }

    fun onEndCall() {
        if (passive) {
            endPassiveByUser()
            return
        }
        val currentMachine = machine ?: return
        prepJob?.cancel()
        prepJob = null
        if (currentMachine.state.isInCall || currentMachine.state.phase == CallPhase.RINGING) {
            enqueue(CallFlowEvent.EndCall)
        }
    }

    fun onUnmute() {
        if (machine?.state?.phase == CallPhase.HOLD) {
            enqueue(CallFlowEvent.Unmute(SystemClock.elapsedRealtime()))
        }
    }

    fun silenceRinger() {
        ringtone.stop()
    }

    fun bringToForeground() {
        uiLaunchAttempted = false
        launchCallUi()
    }

    fun onAudioStateChanged(audioState: CallAudioState?) {
        if (audioState != null) {
            log.info(
                "Call audio route=${audioState.route} supported=${audioState.supportedRouteMask}",
            )
        }
    }

    private fun startIncoming(call: Call, id: Long) {
        val currentSettings = settings.current
        val cellular = isCellularCall(call)
        val testArmed = currentSettings.isLiveTestArmed
        sessionTestMode = testArmed
        val subscriptionId = if (cellular) resolveSubscriptionId(call) else null
        val simAllowed = SimPolicy.isAllowed(currentSettings.simSubscriptionId, subscriptionId)
        val autoEnabled = currentSettings.autoAnswerEnabled || testArmed
        val auto = autoEnabled && cellular && simAllowed
        manualReason = when {
            autoEnabled && !cellular ->
                "Only SIM-based cellular calls can be auto-answered. Answer normally to talk."
            autoEnabled && !simAllowed ->
                "This call arrived on a SIM that is not the one selected for auto-answer. " +
                    "Answer normally to talk."
            else -> null
        }
        log.info(
            "Incoming SIM check: subscription=${subscriptionId ?: "unknown"}, " +
                "configured=${currentSettings.simSubscriptionId}, allowed=$simAllowed, " +
                "testArmed=$testArmed, auto=$auto",
        )

        machine = CallFlowMachine(CallFlowConfig(currentSettings.countdownMs))
        val caller = callerInfo(call)
        val transition = machine!!.reduce(CallFlowEvent.Incoming(caller, auto))
        publish(transition.state)

        if (auto) {
            prepJob = scope.launch { prepareAutoAnswer(call, id) }
        }
    }

    private suspend fun prepareAutoAnswer(call: Call, id: Long) {
        val currentSettings = settings.current
        if (!currentSettings.autoAnswerEnabled && !sessionTestMode) return

        preparing = true
        prepProgress = null
        machine?.let { publish(it.state) }

        try {
            if (sessionTestMode) {
                val tones = try {
                    audioCache.ensureTestTone()
                } catch (e: Exception) {
                    blockAutoAnswer(id, "Could not prepare the live-test tone: ${e.message}")
                    sessionTestMode = false
                    return
                }
                if (!isStillRinging(call, id)) return
                preparedGreeting = tones.greeting
                preparedHold = tones.hold
                settings.markLiveTestCallStarted()
                log.warn(
                    "Live test call: caller-audio capability gate bypassed; playback still " +
                        "fails closed if the telephony uplink is not verified",
                )
                preparing = false
                prepProgress = null
                machine?.let { publish(it.state) }
                enqueue(CallFlowEvent.AutoAnswerReady, id)
                return
            }

            val greetingUri = currentSettings.greetingUri
            val holdUri = currentSettings.holdUri
            if (greetingUri.isNullOrBlank() || holdUri.isNullOrBlank()) {
                blockAutoAnswer(id, "Select a custom message and waiting music in Caller audio settings.")
                return
            }

            val capability = withContext(Dispatchers.IO) { capabilityProbe.probe() }
            if (id != sessionId) return
            val liveVerified = currentSettings.liveTestResult == LiveTestResult.PASSED &&
                currentSettings.liveTestStartedAtMs > 0L &&
                currentSettings.liveTestRouteVerified
            val blockingReason = capability.blockingReason()
            if (blockingReason != null && !liveVerified) {
                blockAutoAnswer(id, blockingReason)
                return
            }
            if (blockingReason != null && liveVerified) {
                log.info(
                    "Proceeding without system audio permissions because a live caller " +
                        "test passed on this device: $blockingReason",
                )
            }
            val greeting = try {
                audioCache.ensure(greetingUri)
            } catch (e: AudioDecodeException) {
                blockAutoAnswer(id, "Could not prepare the custom message: ${e.message}")
                return
            }
            val hold = try {
                audioCache.ensure(holdUri)
            } catch (e: AudioDecodeException) {
                blockAutoAnswer(id, "Could not prepare the waiting music: ${e.message}")
                return
            }

            if (!isStillRinging(call, id)) return
            preparedGreeting = greeting
            preparedHold = hold

            val delayMs = currentSettings.answerDelaySeconds * 1000L
            if (delayMs > 0L) {
                delay(delayMs)
                if (!isStillRinging(call, id)) return
            }

            preparing = false
            prepProgress = null
            machine?.let { publish(it.state) }
            enqueue(CallFlowEvent.AutoAnswerReady, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (id == sessionId) {
                blockAutoAnswer(id, e.message ?: "Automatic answer could not be prepared.")
            }
        }
    }

    private fun blockAutoAnswer(id: Long, reason: String) {
        if (id != sessionId) return
        preparing = false
        prepProgress = null
        log.warn("Auto-answer blocked: $reason")
        enqueue(CallFlowEvent.AutoAnswerBlocked(reason), id)
    }

    private fun isStillRinging(call: Call, id: Long): Boolean =
        id == sessionId &&
            activeCall === call &&
            machine?.state?.phase == CallPhase.RINGING &&
            callState(call) == Call.STATE_RINGING

    private fun enqueue(event: CallFlowEvent, id: Long = sessionId) {
        if (id != sessionId) return
        eventQueue.trySend(QueuedEvent(id, event))
    }

    private suspend fun executeEffects(
        effects: List<CallEffect>,
        state: CallFlowState,
        id: Long,
    ) {
        for (effect in effects) {
            if (id != sessionId) return
            when (effect) {
                CallEffect.AnswerCall -> answerCall(id)
                CallEffect.RejectCall -> rejectCall()
                CallEffect.DisconnectCall -> {
                    answerTimeoutJob?.cancel()
                    answerTimeoutJob = null
                    ringtone.stop()
                    runCatching { activeCall?.disconnect() }
                }
                is CallEffect.SetMicMuted -> {
                    val applied = setMuted(effect.muted)
                    if (!applied && effect.muted && state.phase == CallPhase.GREETING) {
                        enqueue(
                            CallFlowEvent.AudioFailed(
                                "The microphone could not be muted for caller audio. The call was ended.",
                            ),
                            id,
                        )
                        return
                    }
                }
                CallEffect.StartGreetingAudio -> startGreetingAudio(id)
                CallEffect.StopAudio -> stopAudio()
                is CallEffect.StartCountdown -> startCountdown(effect.durationMs, id)
                CallEffect.CancelCountdown -> {
                    countdownJob?.cancel()
                    countdownJob = null
                }
                is CallEffect.NotifyError -> notifier.showError(effect.message)
            }
        }
    }

    private fun answerCall(id: Long) {
        val call = activeCall ?: return
        when (callState(call)) {
            Call.STATE_RINGING -> {
                try {
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    startAnswerTimeout(id)
                } catch (e: Exception) {
                    log.error("Answer failed: ${e.message}")
                    enqueue(CallFlowEvent.AnswerFailed(e.message ?: "Telecom rejected the answer."), id)
                }
            }
            Call.STATE_ACTIVE -> enqueue(CallFlowEvent.CallActive(SystemClock.elapsedRealtime()), id)
            else -> enqueue(
                CallFlowEvent.AnswerFailed("The call is no longer ringing."),
                id,
            )
        }
    }

    private fun rejectCall() {
        prepJob?.cancel()
        prepJob = null
        answerTimeoutJob?.cancel()
        answerTimeoutJob = null
        ringtone.stop()
        runCatching { activeCall?.reject(false, null) }
    }

    private fun startAnswerTimeout(id: Long) {
        answerTimeoutJob?.cancel()
        answerTimeoutJob = scope.launch {
            delay(15_000L)
            if (id == sessionId && machine?.state?.phase == CallPhase.ANSWERING) {
                enqueue(
                    CallFlowEvent.AnswerFailed("The call did not become active in time."),
                    id,
                )
            }
        }
    }

    private fun startGreetingAudio(id: Long) {
        val greeting = preparedGreeting
        val hold = preparedHold
        if (greeting == null || hold == null) {
            enqueue(CallFlowEvent.AudioFailed("Caller audio is not prepared."), id)
            return
        }
        if (playback != null) {
            log.warn("A previous uplink player was still attached; stopping it first")
            playback?.stopAndWait(500L)
            playback = null
        }

        try {
            val player = CallerAudioPlayback(
                context = context,
                log = log,
                greetingFile = greeting.file,
                holdFile = hold.file,
                listener = object : CallerAudioPlayback.Listener {
                    override fun onRouteVerified(route: String) {
                        log.info("Injection route verified by AudioTrack: $route")
                        if (sessionTestMode) {
                            settings.markLiveTestRouteVerified(true)
                        }
                        enqueue(CallFlowEvent.RouteVerified, id)
                    }

                    override fun onGreetingFinished() {
                        enqueue(CallFlowEvent.GreetingFinished, id)
                    }

                    override fun onFatalError(message: String) {
                        if (sessionTestMode) {
                            settings.markLiveTestRouteVerified(false, message)
                        }
                        enqueue(CallFlowEvent.AudioFailed(message), id)
                    }
                },
            )
            playback = player
            player.start()
        } catch (e: Exception) {
            log.error("Could not start caller audio: ${e.message}")
            enqueue(CallFlowEvent.AudioFailed(e.message ?: "Caller audio could not start."), id)
        }
    }

    private suspend fun stopAudio() {
        val player = playback
        playback = null
        if (player != null) {
            withContext(Dispatchers.IO) { player.stopAndWait(1_500L) }
        }
    }

    private fun startCountdown(durationMs: Long, id: Long) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            val deadline = SystemClock.elapsedRealtime() + durationMs
            while (isActive && id == sessionId) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                enqueue(CallFlowEvent.Tick(remaining), id)
                if (remaining <= 0L) break
                delay(100L)
            }
        }
    }

    private fun setMuted(muted: Boolean): Boolean {
        val service = inCallService ?: return false
        return try {
            service.setMuted(muted)
            true
        } catch (e: Exception) {
            log.warn("setMuted($muted) failed: ${e.message}")
            false
        }
    }

    private fun handleStateChanged(state: Int) {
        val call = activeCall ?: return
        log.info("Call state: ${stateName(state)}")

        if (passive) {
            when (state) {
                Call.STATE_ACTIVE -> {
                    val current = _uiState.value
                    if (current.connectedAtElapsedMs == null) {
                        _uiState.value = current.copy(
                            connectedAtElapsedMs = SystemClock.elapsedRealtime(),
                        )
                        setMuted(false)
                        publishPassiveUi(_uiState.value)
                    }
                }
                Call.STATE_DISCONNECTING,
                Call.STATE_DISCONNECTED,
                -> finishPassive(endReason = null)
                Call.STATE_SELECT_PHONE_ACCOUNT -> selectPhoneAccount(call)
            }
            return
        }

        when (state) {
            Call.STATE_ACTIVE -> {
                answerTimeoutJob?.cancel()
                answerTimeoutJob = null
                val phase = machine?.state?.phase
                if (phase == CallPhase.ANSWERING || phase == CallPhase.RINGING) {
                    enqueue(CallFlowEvent.CallActive(SystemClock.elapsedRealtime()))
                }
            }
            Call.STATE_DISCONNECTING,
            Call.STATE_DISCONNECTED,
            -> {
                ringtone.stop()
                enqueue(CallFlowEvent.CallDisconnected)
            }
            Call.STATE_SELECT_PHONE_ACCOUNT -> selectPhoneAccount(call)
        }
    }

    private fun selectPhoneAccount(call: Call) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifier.showError(
                "Phone access is required to select the SIM account.",
            )
            return
        }
        try {
            val accounts = telecomManager.callCapablePhoneAccounts
            if (accounts.size == 1) {
                call.phoneAccountSelected(accounts.first(), false)
                log.info("Selected the only call-capable SIM account")
            } else {
                notifier.showError(
                    "This call needs a SIM account. Open the system phone settings and set a default SIM.",
                )
            }
        } catch (e: Exception) {
            log.warn("Could not select a phone account: ${e.message}")
        }
    }

    private fun startPassive(call: Call, state: Int, id: Long, incoming: Boolean) {
        passive = true
        machine = null
        manualReason = if (incoming) {
            "This call cannot use caller-audio injection. Answer or continue normally."
        } else {
            null
        }
        preparing = false
        prepProgress = null
        preparedGreeting = null
        preparedHold = null

        val connected = if (state == Call.STATE_ACTIVE) SystemClock.elapsedRealtime() else null
        val ui = CallUiState(
            phase = CallPhase.CONVERSATION,
            caller = callerInfo(call),
            autoAnswer = false,
            manual = true,
            passive = true,
            remainingMs = 0L,
            connectedAtElapsedMs = connected,
            blockedReason = manualReason,
        )
        _uiState.value = ui
        setMuted(false)
        render(ui)
        log.info("Started passive call UI (incoming=$incoming)")
    }

    private fun publishPassiveUi(ui: CallUiState) {
        _uiState.value = ui
        render(ui)
    }

    private fun endPassiveByUser() {
        if (!passive) return
        val current = _uiState.value
        if (current.phase == CallPhase.CONVERSATION) {
            val ending = current.copy(phase = CallPhase.ENDING, endReason = CallEndReason.USER)
            _uiState.value = ending
            render(ending)
            runCatching { activeCall?.disconnect() }
        }
    }

    private fun finishPassive(endReason: com.gsmautoanswer.app.core.CallEndReason?) {
        if (!passive) return
        val current = _uiState.value
        if (current.phase == CallPhase.ENDED) return
        val ended = current.copy(
            phase = CallPhase.ENDED,
            endReason = current.endReason ?: endReason ?: CallEndReason.REMOTE,
        )
        _uiState.value = ended
        render(ended)
    }

    private fun publish(state: CallFlowState) {
        val ui = CallUiState(
            phase = state.phase,
            caller = state.caller,
            autoAnswer = state.autoAnswer,
            manual = state.manual,
            passive = passive,
            preparing = preparing,
            prepProgress = prepProgress,
            remainingMs = state.remainingMs,
            countdownTotalMs = state.countdownTotalMs,
            routeVerified = state.routeVerified,
            error = state.error,
            blockedReason = state.blockedReason ?: manualReason,
            connectedAtElapsedMs = state.connectedAtElapsedMs,
            endReason = state.endReason,
        )
        _uiState.value = ui
        render(ui)
        if (state.phase != CallPhase.ANSWERING) {
            answerTimeoutJob?.cancel()
            answerTimeoutJob = null
        }
        if (state.phase == CallPhase.HOLD && !holdUiLaunchAttempted) {
            holdUiLaunchAttempted = true
            launchCallUi(force = true)
        }
    }

    private fun render(ui: CallUiState) {
        if (ui.phase == CallPhase.IDLE) {
            ringtone.stop()
            notifier.cancelCall()
            return
        }

        if (ui.phase == CallPhase.RINGING) {
            ringtone.start()
            val key = listOf(
                ui.preparing,
                ui.blockedReason,
                ui.autoAnswer,
                ui.caller?.label,
            )
            if (key != lastIncomingKey) {
                notifier.showIncoming(ui)
                lastIncomingKey = key
            }
            launchCallUi()
        } else {
            ringtone.stop()
            if (ui.phase != CallPhase.ENDED) {
                val seconds = if (ui.phase == CallPhase.HOLD) {
                    formatRemainingSeconds(ui.remainingMs)
                } else {
                    -1
                }
                val key = listOf(ui.phase, seconds, ui.caller?.label, ui.passive)
                if (key != lastOngoingKey) {
                    notifier.showOngoing(ui)
                    lastOngoingKey = key
                }
                launchCallUi()
            }
        }

        if (ui.phase == CallPhase.ENDED && !endedHandled) {
            endedHandled = true
            ringtone.stop()
            notifier.cancelCall()
            countdownJob?.cancel()
            countdownJob = null
            prepJob?.cancel()
            prepJob = null
            answerTimeoutJob?.cancel()
            answerTimeoutJob = null
            stopPlaybackAsync()
            val id = sessionId
            resetJob?.cancel()
            resetJob = scope.launch {
                delay(2_000L)
                if (id == sessionId) teardownSession()
            }
            log.info("Session ended (${ui.endReason})")
        }
    }

    private fun launchCallUi(force: Boolean = false) {
        if (uiLaunchAttempted && !force) return
        uiLaunchAttempted = true
        try {
            context.startActivity(CallActivity.createIntent(context))
        } catch (e: Exception) {
            log.warn("Could not launch the call screen: ${e.message}. Using the notification instead.")
        }
    }

    private fun stopPlaybackAsync() {
        val player = playback ?: return
        playback = null
        scope.launch(Dispatchers.IO) {
            player.stopAndWait(1_000L)
        }
    }

    private fun teardownSession() {
        resetJob?.cancel()
        resetJob = null
        prepJob?.cancel()
        prepJob = null
        countdownJob?.cancel()
        countdownJob = null
        answerTimeoutJob?.cancel()
        answerTimeoutJob = null

        val call = activeCall
        val callback = callCallback
        if (call != null && callback != null) {
            runCatching { call.unregisterCallback(callback) }
        }
        callCallback = null
        activeCall = null
        machine = null
        passive = false
        preparing = false
        prepProgress = null
        manualReason = null
        preparedGreeting = null
        preparedHold = null
        endedHandled = false
        sessionTestMode = false
        uiLaunchAttempted = false
        holdUiLaunchAttempted = false
        lastIncomingKey = null
        lastOngoingKey = null
        stopPlaybackAsync()
        ringtone.stop()
        notifier.cancelCall()
        _uiState.value = CallUiState()
        sessionId++
    }

    private fun callerInfo(call: Call): CallerInfo {
        val details = call.details
        val name = runCatching {
            details.callerDisplayName?.takeIf { it.isNotBlank() }
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    details.contactDisplayName?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
        }.getOrNull()
        val number = runCatching { details.handle?.schemeSpecificPart }.getOrNull()
        return CallerInfo(name, number)
    }

    private fun isCellularCall(call: Call): Boolean {
        return try {
            val handle = call.details.accountHandle ?: return false
            val account = telecomManager.getPhoneAccount(handle) ?: return false
            account.capabilities and PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION != 0
        } catch (e: Exception) {
            log.warn("Could not read the phone account: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveSubscriptionId(call: Call): Int? {
        val handle = call.details.accountHandle ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log.warn("READ_PHONE_STATE is not granted; the call SIM cannot be identified")
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            val direct = runCatching { telephonyManager.getSubscriptionId(handle) }.getOrNull()
            if (direct != null && direct > 0) return direct
        }

        val accountId = handle.id?.lowercase(Locale.ROOT) ?: return null
        val subscriptions = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList
                .orEmpty()
        }.getOrNull().orEmpty()

        subscriptions.firstOrNull { info ->
            val iccId = info.iccId?.lowercase(Locale.ROOT)
            !iccId.isNullOrBlank() && accountId.contains(iccId)
        }?.let { return it.subscriptionId }

        val slot = SimPolicy.slotFromAccountId(accountId)
        if (slot != null) {
            subscriptions.firstOrNull { it.simSlotIndex + 1 == slot }?.let {
                return it.subscriptionId
            }
        }
        return null
    }

    private fun callState(call: Call): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

    private fun stateName(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_PULLING_CALL -> "PULLING_CALL"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"
        else -> "state-$state"
    }

    private data class QueuedEvent(
        val sessionId: Long,
        val event: CallFlowEvent,
    )
}
