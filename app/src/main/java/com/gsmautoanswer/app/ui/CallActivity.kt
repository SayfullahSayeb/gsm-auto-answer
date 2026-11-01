package com.gsmautoanswer.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.core.CallEndReason
import com.gsmautoanswer.app.core.CallPhase
import com.gsmautoanswer.app.telecom.CallSessionManager
import com.gsmautoanswer.app.telecom.CallUiState
import com.gsmautoanswer.app.util.SystemBars
import com.gsmautoanswer.app.util.formatCallDuration
import com.gsmautoanswer.app.util.formatPhoneNumber
import com.gsmautoanswer.app.util.formatRemainingSeconds
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {

    private lateinit var manager: CallSessionManager
    private lateinit var phaseLabel: TextView
    private lateinit var callerName: TextView
    private lateinit var callerNumber: TextView
    private lateinit var callTimer: TextView
    private lateinit var callDetail: TextView
    private lateinit var routeChip: TextView
    private lateinit var blockedText: TextView
    private lateinit var errorText: TextView
    private lateinit var visualIcon: ImageView
    private lateinit var prepareSpinner: ProgressBar
    private lateinit var countdownProgress: CircularProgressIndicator
    private lateinit var countdownValue: TextView
    private lateinit var countdownUnit: TextView
    private lateinit var unmuteButton: MaterialButton
    private lateinit var ringingActions: android.view.View
    private lateinit var answerButton: MaterialButton
    private lateinit var declineButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var closeButton: MaterialButton
    private lateinit var idleNote: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var finishJob: Job? = null
    private var lastState = CallUiState()
    private var hasSeenSession = false

    private val timerTick = object : Runnable {
        override fun run() {
            updateTimer()
            if (callTimer.isVisible) {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        manager = (application as AutoAnswerApp).graph.sessionManager
        setContentView(R.layout.activity_call)
        val content = findViewById<View>(R.id.call_content)
        SystemBars.apply(
            root = content,
            baseLeft = dp(24),
            baseTop = dp(28),
            baseRight = dp(24),
            baseBottom = dp(24),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        bindViews()
        wireActions()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (lastState.phase == CallPhase.ENDED) {
                        finish()
                    } else {
                        Toast.makeText(
                            this@CallActivity,
                            R.string.call_back_disabled,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )

        render(manager.uiState.value)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.uiState.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(manager.uiState.value)
    }

    override fun onStart() {
        super.onStart()
        updateTimer()
    }

    override fun onStop() {
        handler.removeCallbacks(timerTick)
        super.onStop()
    }

    override fun onDestroy() {
        finishJob?.cancel()
        handler.removeCallbacks(timerTick)
        super.onDestroy()
    }

    private fun bindViews() {
        phaseLabel = findViewById(R.id.phase_label)
        callerName = findViewById(R.id.caller_name)
        callerNumber = findViewById(R.id.caller_number)
        callTimer = findViewById(R.id.call_timer)
        callDetail = findViewById(R.id.call_detail)
        routeChip = findViewById(R.id.route_chip)
        blockedText = findViewById(R.id.blocked_text)
        errorText = findViewById(R.id.error_text)
        visualIcon = findViewById(R.id.visual_icon)
        prepareSpinner = findViewById(R.id.prepare_spinner)
        countdownProgress = findViewById(R.id.countdown_progress)
        countdownValue = findViewById(R.id.countdown_value)
        countdownUnit = findViewById(R.id.countdown_unit)
        unmuteButton = findViewById(R.id.button_unmute)
        ringingActions = findViewById(R.id.ringing_actions)
        answerButton = findViewById(R.id.button_answer)
        declineButton = findViewById(R.id.button_decline)
        endButton = findViewById(R.id.button_end)
        closeButton = findViewById(R.id.button_close)
        idleNote = findViewById(R.id.idle_note)
    }

    private fun wireActions() {
        unmuteButton.setOnClickListener { manager.onUnmute() }
        answerButton.setOnClickListener { manager.onAnswerManually() }
        declineButton.setOnClickListener { manager.onDecline() }
        endButton.setOnClickListener { manager.onEndCall() }
        closeButton.setOnClickListener { finish() }
    }

    private fun render(state: CallUiState) {
        lastState = state
        val phase = state.phase

        if (phase == CallPhase.IDLE) {
            if (hasSeenSession) {
                finish()
                return
            }
            renderIdle()
            return
        }

        hasSeenSession = true
        finishJob?.cancel()
        idleNote.isVisible = false

        val caller = state.caller
        val displayName = caller?.displayName?.takeIf { it.isNotBlank() }
        val rawNumber = caller?.number?.takeIf { it.isNotBlank() }
        val formattedNumber = formatPhoneNumber(rawNumber)
        callerName.text = displayName ?: formattedNumber ?: getString(R.string.unknown_caller)
        callerNumber.text = if (displayName != null) formattedNumber.orEmpty() else ""
        callerNumber.isVisible = displayName != null && !formattedNumber.isNullOrBlank()

        phaseLabel.text = phaseLabel(state)
        callDetail.text = detailText(state)

        val showBlocked = phase == CallPhase.RINGING && state.blockedReason != null
        blockedText.isVisible = showBlocked
        if (showBlocked) {
            blockedText.text = "${state.blockedReason}\n\n${getString(R.string.answer_normally_hint)}"
        }

        val showError = phase == CallPhase.ENDING && state.error != null
        errorText.isVisible = showError
        if (showError) errorText.text = state.error

        val hold = phase == CallPhase.HOLD
        countdownProgress.isVisible = hold
        countdownValue.isVisible = hold
        countdownUnit.isVisible = hold
        visualIcon.isVisible = !hold
        if (hold) {
            val fraction = if (state.countdownTotalMs > 0L) {
                (state.remainingMs.toFloat() / state.countdownTotalMs.toFloat())
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            countdownProgress.progress = (fraction * 1000f).toInt()
            val seconds = formatRemainingSeconds(state.remainingMs)
            countdownValue.text = seconds.toString()
            countdownValue.contentDescription = getString(R.string.countdown_seconds, seconds) +
                " " + getString(R.string.countdown_seconds_label)
        }

        prepareSpinner.isVisible = phase == CallPhase.RINGING && state.preparing
        visualIcon.setImageResource(
            when {
                phase == CallPhase.RINGING && state.preparing -> R.drawable.ic_audio
                phase == CallPhase.RINGING || phase == CallPhase.ANSWERING -> R.drawable.ic_phone
                phase == CallPhase.GREETING -> R.drawable.ic_music
                phase == CallPhase.CONVERSATION -> R.drawable.ic_mic
                phase == CallPhase.ENDING || phase == CallPhase.ENDED -> R.drawable.ic_call_end
                else -> R.drawable.ic_phone
            },
        )

        val showRoute = !state.passive && !state.manual && state.autoAnswer &&
            (phase == CallPhase.GREETING || phase == CallPhase.HOLD ||
                phase == CallPhase.CONVERSATION)
        routeChip.isVisible = showRoute
        if (showRoute) {
            routeChip.setText(
                if (state.routeVerified) R.string.route_verified else R.string.route_unverified,
            )
            routeChip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (state.routeVerified) R.color.unmute_green else R.color.warning_amber,
                ),
            )
        }

        unmuteButton.isVisible = hold
        ringingActions.isVisible = phase == CallPhase.RINGING
        endButton.isVisible = phase == CallPhase.ANSWERING ||
            phase == CallPhase.GREETING ||
            phase == CallPhase.HOLD ||
            phase == CallPhase.CONVERSATION
        closeButton.isVisible = phase == CallPhase.ENDED

        updateTimer()
        if (phase == CallPhase.ENDED) {
            finishJob = lifecycleScope.launch {
                delay(3_000L)
                finish()
            }
        }
    }

    private fun renderIdle() {
        callerName.text = getString(R.string.app_name)
        callerNumber.isVisible = false
        callerNumber.text = ""
        phaseLabel.setText(R.string.call_screen_waiting)
        callDetail.text = null
        callDetail.isVisible = false
        blockedText.isVisible = false
        errorText.isVisible = false
        routeChip.isVisible = false
        countdownProgress.isVisible = false
        countdownValue.isVisible = false
        countdownUnit.isVisible = false
        prepareSpinner.isVisible = false
        visualIcon.setImageResource(R.drawable.ic_phone)
        unmuteButton.isVisible = false
        ringingActions.isVisible = false
        endButton.isVisible = false
        closeButton.isVisible = true
        idleNote.isVisible = true
        callTimer.isVisible = false
        handler.removeCallbacks(timerTick)
    }

    private fun phaseLabel(state: CallUiState): CharSequence = when (state.phase) {
        CallPhase.RINGING -> {
            when {
                state.blockedReason != null -> getString(R.string.auto_answer_blocked)
                state.preparing -> getString(R.string.preparing_caller_audio)
                state.autoAnswer -> getString(R.string.auto_answering)
                else -> getString(R.string.incoming_call)
            }
        }
        CallPhase.ANSWERING -> getString(R.string.answering)
        CallPhase.GREETING -> getString(R.string.playing_message)
        CallPhase.HOLD -> getString(R.string.hold_screen_title)
        CallPhase.CONVERSATION -> {
            if (state.passive && state.connectedAtElapsedMs == null) {
                getString(R.string.calling)
            } else {
                getString(R.string.in_call)
            }
        }
        CallPhase.ENDING -> getString(R.string.ending_call)
        CallPhase.ENDED -> getString(R.string.call_ended)
        CallPhase.IDLE -> getString(R.string.call_screen_waiting)
    }

    private fun detailText(state: CallUiState): CharSequence? = when (state.phase) {
        CallPhase.RINGING -> {
            when {
                state.blockedReason != null -> null
                state.preparing -> getString(R.string.preparing_detail)
                state.autoAnswer -> null
                else -> getString(R.string.answer_normally_hint)
            }
        }
        CallPhase.ANSWERING -> null
        CallPhase.GREETING -> getString(R.string.playing_message_detail)
        CallPhase.HOLD -> {
            val seconds = formatRemainingSeconds(state.countdownTotalMs)
            getString(R.string.hold_screen_detail, seconds)
        }
        CallPhase.CONVERSATION -> {
            when {
                state.passive -> getString(R.string.call_passive_note)
                state.manual -> getString(R.string.call_manual_note)
                else -> getString(R.string.mic_live)
            }
        }
        CallPhase.ENDING -> null
        CallPhase.ENDED -> endedReason(state)
        CallPhase.IDLE -> null
    }

    private fun endedReason(state: CallUiState): String = when (state.endReason) {
        CallEndReason.REMOTE -> getString(R.string.call_ended_remote)
        CallEndReason.USER -> getString(R.string.call_ended_user)
        CallEndReason.TIMEOUT -> getString(R.string.call_ended_timeout)
        CallEndReason.INJECTION_FAILURE ->
            state.error ?: getString(R.string.call_ended_injection)
        CallEndReason.ANSWER_FAILURE ->
            state.error ?: getString(R.string.call_ended_answer)
        null -> getString(R.string.call_ended)
    }

    private fun updateTimer() {
        val connectedAt = lastState.connectedAtElapsedMs
        if (lastState.phase != CallPhase.CONVERSATION || connectedAt == null) {
            callTimer.isVisible = false
            handler.removeCallbacks(timerTick)
            return
        }
        callTimer.isVisible = true
        handler.removeCallbacks(timerTick)
        callTimer.text = formatCallDuration(SystemClock.elapsedRealtime() - connectedAt)
        handler.postDelayed(timerTick, 1_000L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, CallActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
    }
}
