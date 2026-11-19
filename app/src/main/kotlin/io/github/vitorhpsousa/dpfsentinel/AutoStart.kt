package io.github.vitorhpsousa.dpfsentinel

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.vitorhpsousa.dpfsentinel.core.TriggerBackoff
import io.github.vitorhpsousa.dpfsentinel.ui.MainActivity

// The one place that starts logging without the user: called when the OBD adapter comes into range
// (PresenceService), the phone connects to the car's Bluetooth (CarBluetoothReceiver) and when
// Android Auto opens the app. Runs on the main thread and never blocks.
object AutoStart {
    private const val TAG = "AutoStart"
    private const val DEBOUNCE_MS = 30_000L
    const val NOTIF_AUTO = 30
    const val FAILED_TEXT = "Automatic logging couldn't start — open DPF Sentinel once"

    private var lastAt = 0L          // elapsedRealtime of the last request acted on
    private var lastOk = false
    private var lastReason = ""

    /** Returns "" when logging was started or there was nothing to do, else why it could not start. */
    fun request(context: Context, reason: String, giveUpMs: Long = 0L): String {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.autoLog) return ""
        val live = LiveState.state.value
        if (live.running) {
            // Already trying: a trigger means the car is probably here now, so the session that is
            // still looking for it must not give up on its old clock (opened at home, then driven off).
            // Not for the adapter's own trigger: it re-appears every time the app lets go of it,
            // which would keep a session beside a parked car alive all night.
            if (!reason.startsWith("adapter") && (!live.connected || live.stale)) LoggerService.extend(giveUpMs)
            return ""
        }
        val fromAdapter = reason.startsWith("adapter")
        if (fromAdapter && TriggerBackoff.ignoreAdapter(System.currentTimeMillis(), prefs.emptySessions, prefs.emptyEndedAt, prefs.stoppedAt)) {
            Log.i(TAG, "ignored ($reason): the car did not answer last time, or Stop was tapped")
            return ""
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            // Triggers arrive in bursts (car Bluetooth and the adapter within a second). After a
            // failure a different trigger may still try: it can have rights the first one lacked.
            if (lastAt != 0L && now - lastAt < DEBOUNCE_MS && (lastOk || reason == lastReason)) return ""
            lastAt = now
            lastOk = true
            lastReason = reason
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(app, Manifest.permission.BLUETOOTH_CONNECT)) {
            return failed(app, "$reason: the Nearby devices permission is off")
        }
        return try {
            LoggerService.start(app, auto = true, giveUpMs = giveUpMs)
            Log.i(TAG, "started ($reason)")
            prefs.autoLastTime = System.currentTimeMillis()
            prefs.autoLastResult = "ok ($reason)"
            ""
        } catch (e: SecurityException) {
            failed(app, "$reason: ${e.message ?: "not allowed"}")
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (Android 12+).
            failed(app, "$reason: Android refused a background start")
        }
    }

    /** Records a failed automatic start and tells the user once, quietly. Returns [why]. */
    fun failed(context: Context, why: String): String {
        val app = context.applicationContext
        Log.w(TAG, "automatic start failed: $why")
        synchronized(this) { lastOk = false }
        val prefs = Prefs(app)
        prefs.autoLastTime = System.currentTimeMillis()
        prefs.autoLastResult = why
        notify(app, FAILED_TEXT)
        return why
    }

    /** The logger gave up after repeated errors: leave a quiet note, cleared by the next start. */
    fun stoppedAbnormally(context: Context) {
        val prefs = Prefs(context)
        if (prefs.autoLog && (prefs.assocMac.isNotEmpty() || prefs.carBtAddress.isNotEmpty())) {
            notify(context.applicationContext, "Logging stopped after repeated errors. It will try again on the next drive.")
        }
    }

    fun clearNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_AUTO)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")   // checked just below
    private fun notify(app: Context, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(app, Manifest.permission.POST_NOTIFICATIONS)) return
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, DpfApp.CH_AUTO)
            .setSmallIcon(R.drawable.ic_dot)
            .setContentTitle("DPF Sentinel")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIF_AUTO, n) }
    }
}
