package io.github.vitorhpsousa.dpfsentinel

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle

class DpfApp : Application() {
    /** One sender for the logger and the settings screen's test/report buttons. */
    val telegram by lazy { Telegram(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "Logging", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while DPF Sentinel is reading the car"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "DPF alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Regeneration started/finished, high soot, health warnings"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AUTO, "Automatic logging", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown when logging could not start by itself"
            },
        )
        // Whether the phone UI is on screen: only then may the logger also ask for the location
        // foreground-service type (Android refuses it for starts made from the background).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { startedActivities++ }
            override fun onActivityStopped(a: Activity) { startedActivities-- }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        const val CH_LIVE = "live"
        const val CH_ALERTS = "alerts"
        const val CH_AUTO = "auto"

        @Volatile private var startedActivities = 0
        val uiVisible: Boolean get() = startedActivities > 0
    }
}
