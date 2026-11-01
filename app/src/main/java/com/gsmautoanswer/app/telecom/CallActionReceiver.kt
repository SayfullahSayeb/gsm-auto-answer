package com.gsmautoanswer.app.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gsmautoanswer.app.AutoAnswerApp

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as AutoAnswerApp).graph.sessionManager
        when (intent.action) {
            ACTION_ANSWER -> manager.onAnswerManually()
            ACTION_DECLINE -> manager.onDecline()
            ACTION_END -> manager.onEndCall()
            ACTION_UNMUTE -> manager.onUnmute()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.gsmautoanswer.app.action.ANSWER"
        const val ACTION_DECLINE = "com.gsmautoanswer.app.action.DECLINE"
        const val ACTION_END = "com.gsmautoanswer.app.action.END"
        const val ACTION_UNMUTE = "com.gsmautoanswer.app.action.UNMUTE"
    }
}
