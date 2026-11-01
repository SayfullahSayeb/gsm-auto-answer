package com.gsmautoanswer.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiveTestResult {
    NOT_RUN,
    PASSED,
    FAILED,
}

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

data class AppSettings(
    val autoAnswerEnabled: Boolean = false,
    val greetingUri: String? = null,
    val holdUri: String? = null,
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
    val answerDelaySeconds: Int = 0,
    val simSubscriptionId: Int = ALL_SIMS,
    val simLabel: String? = null,
    val liveTestArmedUntilMs: Long = 0L,
    val liveTestStartedAtMs: Long = 0L,
    val liveTestRouteVerified: Boolean = false,
    val liveTestResult: LiveTestResult = LiveTestResult.NOT_RUN,
    val liveTestError: String? = null,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
) {
    val countdownMs: Long
        get() = countdownSeconds * 1000L

    val isLiveTestArmed: Boolean
        get() = liveTestArmedUntilMs > System.currentTimeMillis()

    val liveTestArmedRemainingMs: Long
        get() = (liveTestArmedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    val usesSpecificSim: Boolean
        get() = simSubscriptionId != ALL_SIMS

    companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 30
        const val MIN_COUNTDOWN_SECONDS = 5
        const val MAX_COUNTDOWN_SECONDS = 120
        const val MAX_ANSWER_DELAY_SECONDS = 5
        const val ALL_SIMS = -1
        const val LIVE_TEST_WINDOW_MS = 30L * 60L * 1000L
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings
        get() = _settings.value

    private fun load(): AppSettings = AppSettings(
        autoAnswerEnabled = prefs.getBoolean(KEY_AUTO_ANSWER, false),
        greetingUri = prefs.getString(KEY_GREETING_URI, null),
        holdUri = prefs.getString(KEY_HOLD_URI, null),
        countdownSeconds = prefs.getInt(
            KEY_COUNTDOWN_SECONDS,
            AppSettings.DEFAULT_COUNTDOWN_SECONDS,
        ).coerceIn(
            AppSettings.MIN_COUNTDOWN_SECONDS,
            AppSettings.MAX_COUNTDOWN_SECONDS,
        ),
        answerDelaySeconds = prefs.getInt(KEY_ANSWER_DELAY_SECONDS, 0)
            .coerceIn(0, AppSettings.MAX_ANSWER_DELAY_SECONDS),
        simSubscriptionId = prefs.getInt(KEY_SIM_SUBSCRIPTION_ID, AppSettings.ALL_SIMS),
        simLabel = prefs.getString(KEY_SIM_LABEL, null),
        liveTestArmedUntilMs = prefs.getLong(KEY_LIVE_TEST_ARMED_UNTIL, 0L),
        liveTestStartedAtMs = prefs.getLong(KEY_LIVE_TEST_STARTED_AT, 0L),
        liveTestRouteVerified = prefs.getBoolean(KEY_LIVE_TEST_ROUTE_VERIFIED, false),
        liveTestResult = runCatching {
            LiveTestResult.valueOf(
                prefs.getString(KEY_LIVE_TEST_RESULT, null) ?: LiveTestResult.NOT_RUN.name,
            )
        }.getOrDefault(LiveTestResult.NOT_RUN),
        liveTestError = prefs.getString(KEY_LIVE_TEST_ERROR, null),
        themeMode = runCatching {
            ThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, null) ?: ThemeMode.LIGHT.name,
            )
        }.getOrDefault(ThemeMode.LIGHT),
    )

    private fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_AUTO_ANSWER, next.autoAnswerEnabled)
            .putString(KEY_GREETING_URI, next.greetingUri)
            .putString(KEY_HOLD_URI, next.holdUri)
            .putInt(KEY_COUNTDOWN_SECONDS, next.countdownSeconds)
            .putInt(KEY_ANSWER_DELAY_SECONDS, next.answerDelaySeconds)
            .putInt(KEY_SIM_SUBSCRIPTION_ID, next.simSubscriptionId)
            .putString(KEY_SIM_LABEL, next.simLabel)
            .putLong(KEY_LIVE_TEST_ARMED_UNTIL, next.liveTestArmedUntilMs)
            .putLong(KEY_LIVE_TEST_STARTED_AT, next.liveTestStartedAtMs)
            .putBoolean(KEY_LIVE_TEST_ROUTE_VERIFIED, next.liveTestRouteVerified)
            .putString(KEY_LIVE_TEST_RESULT, next.liveTestResult.name)
            .putString(KEY_LIVE_TEST_ERROR, next.liveTestError)
            .putString(KEY_THEME_MODE, next.themeMode.name)
            .apply()
        _settings.value = next
    }

    fun setAutoAnswerEnabled(enabled: Boolean) =
        update { it.copy(autoAnswerEnabled = enabled) }

    fun setGreetingUri(uri: String?) = update { it.copy(greetingUri = uri) }

    fun setHoldUri(uri: String?) = update { it.copy(holdUri = uri) }

    fun setCountdownSeconds(seconds: Int) = update {
        it.copy(
            countdownSeconds = seconds.coerceIn(
                AppSettings.MIN_COUNTDOWN_SECONDS,
                AppSettings.MAX_COUNTDOWN_SECONDS,
            ),
        )
    }

    fun setAnswerDelaySeconds(seconds: Int) = update {
        it.copy(answerDelaySeconds = seconds.coerceIn(0, AppSettings.MAX_ANSWER_DELAY_SECONDS))
    }

    fun setSimSelection(subscriptionId: Int, label: String?) = update {
        it.copy(
            simSubscriptionId = subscriptionId,
            simLabel = if (subscriptionId == AppSettings.ALL_SIMS) null else label,
        )
    }

    fun armLiveTest() = update {
        it.copy(
            liveTestArmedUntilMs = System.currentTimeMillis() + AppSettings.LIVE_TEST_WINDOW_MS,
            liveTestStartedAtMs = 0L,
            liveTestRouteVerified = false,
            liveTestResult = LiveTestResult.NOT_RUN,
            liveTestError = null,
        )
    }

    fun disarmLiveTest() = update { it.copy(liveTestArmedUntilMs = 0L) }

    fun markLiveTestCallStarted() = update {
        it.copy(
            liveTestArmedUntilMs = 0L,
            liveTestStartedAtMs = System.currentTimeMillis(),
            liveTestRouteVerified = false,
            liveTestResult = LiveTestResult.NOT_RUN,
            liveTestError = null,
        )
    }

    fun markLiveTestRouteVerified(verified: Boolean, error: String? = null) = update {
        it.copy(
            liveTestRouteVerified = verified || it.liveTestRouteVerified,
            liveTestError = error ?: it.liveTestError,
        )
    }

    fun setLiveTestResult(result: LiveTestResult) = update {
        it.copy(
            liveTestResult = result,
            liveTestArmedUntilMs = 0L,
        )
    }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    private companion object {
        const val PREFS_NAME = "gsm_auto_answer_settings"
        const val KEY_AUTO_ANSWER = "auto_answer_enabled"
        const val KEY_GREETING_URI = "greeting_uri"
        const val KEY_HOLD_URI = "hold_uri"
        const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
        const val KEY_ANSWER_DELAY_SECONDS = "answer_delay_seconds"
        const val KEY_SIM_SUBSCRIPTION_ID = "sim_subscription_id"
        const val KEY_SIM_LABEL = "sim_label"
        const val KEY_LIVE_TEST_ARMED_UNTIL = "live_test_armed_until"
        const val KEY_LIVE_TEST_STARTED_AT = "live_test_started_at"
        const val KEY_LIVE_TEST_ROUTE_VERIFIED = "live_test_route_verified"
        const val KEY_LIVE_TEST_RESULT = "live_test_result"
        const val KEY_LIVE_TEST_ERROR = "live_test_error"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
