package io.github.vitorhpsousa.dpfsentinel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.vitorhpsousa.dpfsentinel.core.DailyWindowLogic
import java.time.ZoneId

// "Look for the car every day at HH:MM" (Settings, Automatic logging): one alarm a day. When it
// fires the logger is started as an automatic session that keeps trying to reach the adapter for
// the 15-minute window instead of the usual 3 minutes. The alarm is not exact (that needs a special
// permission), so Android may deliver it a few minutes late; the window absorbs that.
object DailyWindow {
    private const val TAG = "DailyWindow"

    private fun intent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, DailyWindowReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets (or clears) the next alarm from the settings. Safe to call any time. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(app)
        runCatching {
            am.cancel(pi)
            if (!prefs.dailyOn || !prefs.autoLog) return
            val at = DailyWindowLogic.next(System.currentTimeMillis(), prefs.dailyHour, prefs.dailyMinute, ZoneId.systemDefault())
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { Log.w(TAG, "couldn't set the daily alarm", it) }
    }
}

class DailyWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DailyWindow.schedule(context)   // tomorrow's
        val prefs = Prefs(context)
        if (!prefs.dailyOn) return
        val left = DailyWindowLogic.remaining(System.currentTimeMillis(), prefs.dailyHour, prefs.dailyMinute, ZoneId.systemDefault())
        if (left <= 0L) {
            Log.i("DailyWindow", "alarm arrived after the window, skipped")
            return
        }
        AutoStart.request(context, "daily time", giveUpMs = left)
    }
}
