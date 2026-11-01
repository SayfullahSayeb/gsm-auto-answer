package com.gsmautoanswer.app.telecom

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.gsmautoanswer.app.AutoAnswerApp

class AutoAnswerInCallService : InCallService() {
    private val manager
        get() = (application as AutoAnswerApp).graph.sessionManager

    override fun onCreate() {
        super.onCreate()
        manager.attachService(this)
    }

    override fun onDestroy() {
        manager.detachService(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        manager.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        manager.onCallRemoved(call)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        super.onBringToForeground(showDialpad)
        manager.bringToForeground()
    }

    override fun onSilenceRinger() {
        super.onSilenceRinger()
        manager.silenceRinger()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        manager.onAudioStateChanged(audioState)
    }
}
