package com.gsmautoanswer.app

import android.app.Application
import com.gsmautoanswer.app.audio.AudioCacheRepository
import com.gsmautoanswer.app.audio.InjectionCapabilityProbe
import com.gsmautoanswer.app.compat.CompatScanner
import com.gsmautoanswer.app.data.SettingsRepository
import com.gsmautoanswer.app.diagnostics.SessionLog
import com.gsmautoanswer.app.shizuku.ShizukuManager
import com.gsmautoanswer.app.telecom.CallNotifier
import com.gsmautoanswer.app.telecom.CallSessionManager
import com.gsmautoanswer.app.util.ThemeController

class AutoAnswerApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        ThemeController.apply(graph.settings.current.themeMode)
    }
}

class AppGraph(application: Application) {
    val log = SessionLog(application)
    val settings = SettingsRepository(application)
    val audioCache = AudioCacheRepository(application, log)
    val capabilityProbe = InjectionCapabilityProbe(application, log)
    val shizuku = ShizukuManager(application, log)
    val compatScanner = CompatScanner(
        context = application,
        settings = settings,
        capabilityProbe = capabilityProbe,
        shizuku = shizuku,
        log = log,
    )
    val notifier = CallNotifier(application, log)
    val sessionManager = CallSessionManager(
        context = application,
        settings = settings,
        audioCache = audioCache,
        capabilityProbe = capabilityProbe,
        notifier = notifier,
        log = log,
    )
}
