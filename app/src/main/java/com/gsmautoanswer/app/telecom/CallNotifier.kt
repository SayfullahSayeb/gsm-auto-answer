package com.gsmautoanswer.app.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.core.CallPhase
import com.gsmautoanswer.app.diagnostics.SessionLog
import com.gsmautoanswer.app.ui.CallActivity
import com.gsmautoanswer.app.ui.DiagnosticsActivity
import com.gsmautoanswer.app.util.formatRemainingSeconds

class CallNotifier(
    private val context: Context,
    private val log: SessionLog,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var nextAlertId = ALERT_ID_START

    init {
        createChannels()
    }

    fun showIncoming(state: CallUiState) {
        val caller = state.caller?.label ?: context.getString(R.string.unknown_caller)
        val text = when {
            state.blockedReason != null -> context.getString(R.string.notif_blocked_text, caller)
            state.preparing -> context.getString(R.string.notif_preparing_text, caller)
            state.autoAnswer -> context.getString(R.string.notif_incoming_auto_text, caller)
            else -> context.getString(R.string.notif_incoming_text, caller)
        }
        val contentIntent = callScreenPendingIntent()
        val notification = Notification.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(caller)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .addAction(action(R.drawable.ic_phone, R.string.answer, CallActionReceiver.ACTION_ANSWER, 10))
            .addAction(
                action(R.drawable.ic_call_end, R.string.decline, CallActionReceiver.ACTION_DECLINE, 11),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                log.warn("Full-screen intent permission is not available; call screen will use a notification.")
            }
        }
        notify(CHANNEL_CALL_ID, notification)
    }

    fun showOngoing(state: CallUiState) {
        val caller = state.caller?.label ?: context.getString(R.string.unknown_caller)
        val title: String
        val text: String
        when (state.phase) {
            CallPhase.ANSWERING -> {
                title = context.getString(R.string.notif_answering_title)
                text = caller
            }
            CallPhase.GREETING -> {
                title = context.getString(R.string.notif_greeting_title)
                text = context.getString(R.string.notif_greeting_text, caller)
            }
            CallPhase.HOLD -> {
                val seconds = formatRemainingSeconds(state.remainingMs)
                title = context.getString(R.string.notif_hold_title)
                text = context.getString(R.string.notif_hold_text, seconds, caller)
            }
            CallPhase.CONVERSATION -> {
                title = if (state.passive && state.connectedAtElapsedMs == null) {
                    context.getString(R.string.notif_calling_title)
                } else {
                    context.getString(R.string.notif_in_call_title)
                }
                text = if (state.passive && state.manual && !state.autoAnswer) {
                    caller
                } else {
                    context.getString(R.string.notif_in_call_text, caller)
                }
            }
            CallPhase.ENDING -> {
                title = context.getString(R.string.notif_ending_title)
                text = caller
            }
            else -> {
                title = caller
                text = context.getString(R.string.notif_in_call_title)
            }
        }

        val builder = Notification.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(callScreenPendingIntent())

        if (state.phase == CallPhase.HOLD) {
            builder.addAction(
                action(R.drawable.ic_mic, R.string.unmute, CallActionReceiver.ACTION_UNMUTE, 13),
            )
        }
        builder.addAction(
            action(R.drawable.ic_call_end, R.string.end_call, CallActionReceiver.ACTION_END, 12),
        )

        notify(CHANNEL_CALL_ID, builder.build())
    }

    fun cancelCall() {
        runCatching { notificationManager.cancel(CHANNEL_CALL_ID) }
    }

    fun showError(message: String) {
        val intent = Intent(context, DiagnosticsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            20,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notify(nextAlertId++, notification)
    }

    fun showExtraCallDeclined() {
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(context.getString(R.string.notif_declined_title))
            .setContentText(context.getString(R.string.notif_declined_text))
            .setAutoCancel(true)
            .setContentIntent(callScreenPendingIntent())
            .build()
        notify(nextAlertId++, notification)
    }

    private fun callScreenPendingIntent(): PendingIntent {
        val intent = CallActivity.createIntent(context)
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(iconRes: Int, titleRes: Int, action: String, requestCode: Int): Notification.Action {
        val intent = Intent(context, CallActionReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, iconRes),
            context.getString(titleRes),
            pendingIntent,
        ).build()
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            notificationManager.notify(id, notification)
        } catch (e: SecurityException) {
            log.warn("Notification permission missing: ${e.message}")
        } catch (e: Exception) {
            log.warn("Could not post notification: ${e.message}")
        }
    }

    private fun createChannels() {
        val calls = NotificationChannel(
            CALL_CHANNEL_ID,
            context.getString(R.string.channel_calls),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_calls_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_alerts_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannels(listOf(calls, alerts))
    }

    companion object {
        const val CALL_CHANNEL_ID = "calls"
        private const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_CALL_ID = 100
        private const val ALERT_ID_START = 200
    }
}
