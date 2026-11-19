package io.github.vitorhpsousa.dpfsentinel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.vitorhpsousa.dpfsentinel.core.HealthState
import io.github.vitorhpsousa.dpfsentinel.core.HealthTracker
import io.github.vitorhpsousa.dpfsentinel.core.IdleBlockageWatch
import io.github.vitorhpsousa.dpfsentinel.core.LastKnown
import io.github.vitorhpsousa.dpfsentinel.core.GradeTracker
import io.github.vitorhpsousa.dpfsentinel.core.Messages
import io.github.vitorhpsousa.dpfsentinel.core.PhoneColumns
import io.github.vitorhpsousa.dpfsentinel.core.ObdPoller
import io.github.vitorhpsousa.dpfsentinel.core.RegenEvent
import io.github.vitorhpsousa.dpfsentinel.core.RegenWatch
import io.github.vitorhpsousa.dpfsentinel.core.Row
import io.github.vitorhpsousa.dpfsentinel.core.SootZone
import io.github.vitorhpsousa.dpfsentinel.core.StartNotice
import io.github.vitorhpsousa.dpfsentinel.obd.BleElm
import io.github.vitorhpsousa.dpfsentinel.ui.MainActivity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

// Reads the car while the phone is locked: the phone's version of the firmware's main loop. One
// thread serves the most overdue OBD request, assembles a row a second, writes the CSV, and feeds
// the regen watch, health tracker, notifications, Telegram and LiveState (phone + car screens).
class LoggerService : Service() {
    @Volatile private var running = false
    @Volatile private var stopReason = ""
    // Started by AutoStart (or restarted by Android) rather than by the user: gives up sooner.
    @Volatile private var autoSession = false
    // What started this session, for the Telegram message: "by itself", "when the app was opened", "by hand".
    @Volatile private var startedHow = "by hand"
    // How long to keep trying before the car has answered once (0 = the normal idle limit).
    @Volatile private var firstContactLimitMs = 0L
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: Prefs
    private lateinit var telegram: Telegram

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        telegram = (application as DpfApp).telegram
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is Android restarting the service after killing the app: an automatic start.
        val auto = intent == null || intent.getBooleanExtra(EXTRA_AUTO, false)
        // GPS needs the location type too. Android only allows it for a start made while the app is
        // on screen (from the background it throws unless location is "Allow all the time", which
        // this app never asks for), so automatic and car-screen starts log the car data only.
        // Opening the app later sends another start, which adds GPS to the running session.
        val ble = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val withGps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ble or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val wantGps = ((!auto && DpfApp.uiVisible) || (running && gpsOn)) && PhoneSensors(this).hasLocationPermission
        val text = if (running) LiveState.state.value.status else "Starting…"
        try {
            var gps = false
            if (wantGps) {
                try {
                    gpsOn = true   // read by liveNotification
                    ServiceCompat.startForeground(this, NOTIF_LIVE, liveNotification(text), withGps)
                    gps = true
                } catch (e: Exception) {
                    Log.i(TAG, "logging without GPS: ${e.message}")
                }
            }
            gpsOn = gps
            if (!gps) ServiceCompat.startForeground(this, NOTIF_LIVE, liveNotification(text), ble)
        } catch (e: Exception) {
            // Missing Bluetooth permission or a background start Android refused.
            Log.w(TAG, "startForeground refused", e)
            if (running) return START_STICKY   // a session that is already logging carries on as it was
            gpsOn = false
            if (auto) AutoStart.failed(this, "Android refused the foreground service")
            stopSelf()
            return START_NOT_STICKY
        }
        AutoStart.clearNotification(this)
        val giveUp = intent?.getLongExtra(EXTRA_GIVE_UP, 0L) ?: 0L
        // The app coming on screen during a session only adds GPS: it is not a new start by hand.
        val addGps = intent?.getBooleanExtra(EXTRA_ADD_GPS, false) ?: false
        if (!auto && !addGps) autoSession = false
        // A plain Start (the button) waits the full time.
        if (running && !auto && !addGps && giveUp == 0L) { firstContactLimitMs = 0L; startedHow = "by hand" }
        if (!running) {
            startedHow = if (auto) "by itself" else if (giveUp > 0L) "when the app was opened" else "by hand"
            // Straight away, so the screens don't offer Start again while the worker gets going.
            LiveState.update { it.copy(running = true, status = "Starting…") }
            autoSession = auto
            firstContactLimitMs = if (giveUp > 0L) giveUp else if (auto) AUTO_GIVE_UP_MS else 0L
            running = true
            wakeLock?.let { if (it.isHeld) it.release() }   // never two: the older one would be held for hours
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DpfSentinel:logger").apply { acquire(12 * 60 * 60 * 1000L) }
            stopReason = ""
            worker = Thread({ runWorker() }, "obd-logger").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker?.join(3000)
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        val why = stopReason.ifEmpty { "Not logging" }
        LiveState.update {
            it.copy(running = false, connected = false, stale = true, status = why, row = null,
                regenActive = false, regenPaused = false, regenStartedAtMs = 0, idleBlockage = false, history = emptyList())
        }
        super.onDestroy()
    }

    // Runs loop(), restarting it after a crash (a few times per 10 min at most), and stops the
    // service when the loop gives up by itself (no car for a while), so the screens and the
    // notification never claim "logging" while nothing is.
    private fun runWorker() {
        val me = Thread.currentThread()
        // One logging thread per process: after a quick Stop + Start the previous worker may still
        // be closing the adapter, so wait for it rather than share the BLE link.
        activeWorker.getAndSet(me)?.takeIf { it !== me && it.isAlive }?.let { old ->
            try { old.join(15_000) } catch (e: InterruptedException) { return }
        }
        val crashes = ArrayDeque<Long>()
        while (running) {
            // Anything thrown outside the loop's own handling (a full disk when opening the log file)
            // counts as a crash too, instead of killing the app.
            val crashed = try { loop() } catch (e: InterruptedException) { false } catch (e: Exception) { Log.e(TAG, "logger could not start", e); true }
            if (!running || !crashed) break
            val t = SystemClock.elapsedRealtime()
            crashes.addLast(t)
            while (crashes.isNotEmpty() && t - crashes.first() > CRASH_WINDOW_MS) crashes.removeFirst()
            if (crashes.size > MAX_CRASHES) {
                stopReason = "Stopped after repeated errors: tap Start to try again"
                alert("DPF Sentinel stopped", stopReason, NOTIF_HEALTH - 1)
                AutoStart.stoppedAbnormally(this)
                break
            }
            try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
        }
        activeWorker.compareAndSet(me, null)
        if (running) {   // ended by itself rather than by Stop
            running = false
            stopSelf()
        }
    }

    /** One logging session. Returns true if it ended on an unexpected error. */
    private fun loop(): Boolean {
        val elm = BleElm(this)
        val session = SessionFiles(this, prefs.nextSessionNumber(SessionFiles.highestNumber(this)))
        val t0 = SystemClock.elapsedRealtime()
        fun ms() = SystemClock.elapsedRealtime() - t0
        fun unix() = System.currentTimeMillis() / 1000.0

        val poller = ObdPoller(elm) { h, r, reply -> session.raw(ms(), h, r, reply) }
        val watch = RegenWatch(units = { prefs.units })
        val health = HealthTracker(HealthState.decode(prefs.health))
        val zone = SootZone()
        val idleWatch = IdleBlockageWatch()
        val startNotice = StartNotice()
        var noticesSent = 0   // connect messages queued in this session
        val history = ArrayDeque<HistoryPoint>()
        val sensors = PhoneSensors(this).also { it.start() }
        val grade = GradeTracker()
        var gps = PhoneSensors.Sample()
        var extra = PhoneColumns.empty()
        var blankRows = 0
        var deadRawSkip = 0L
        var lastRow = 0L
        var lastReconnect = -RECONNECT_MS
        var lastTelegram = 0L
        var lastNotif = 0L
        var connectFails = 0
        var lastEvent = ""
        var lastAlert = ""
        var lastCarMs = 0L   // last row with any decoded PID; the session starts the clock
        var adapterReached = false   // the adapter answered in this session (the car may still be asleep)
        val t0Elapsed = t0
        var crashed = false
        var latest: Row? = null
        // The last row the car answered: stays on the screens (marked as old) once the link is gone.
        // Per value, not per row: the last rows of a drive have no soot in them (see LastKnown).
        val lastKnown = LastKnown(Row.decode(prefs.lastRow), prefs.lastRowAt)
        var lastGood: Row? = lastKnown.row()
        var lastGoodSaved = 0L
        var lastEstimate = prefs.lastEstimate   // the estimate that went with the last soot reading
        var status = "Looking for the OBD adapter…"
        val power = getSystemService(PowerManager::class.java)

        fun publish(row: Row?) {
            if (!running) return   // a stopping worker must not flip the screens back to "logging"
            val h = health.state
            val now = unix()
            val link = elm.connected()
            // "Live" means the number this app is about is live: one answered PID is not enough.
            val liveNow = link && row != null && !row.soot.isNaN()
            val est = Messages.estimate(h, prefs.units)
            if (liveNow && est != "--") lastEstimate = est
            LiveState.set(
                Snapshot(
                    running = true,
                    status = status,
                    connected = link,
                    adapterName = elm.name,
                    row = row,
                    lastRow = lastGood,
                    lastRowAtMs = lastKnown.atMs,
                    stale = !liveNow,
                    // With the link down nobody knows whether the regen is still going: no banner.
                    regenActive = link && ((watch.active && !watch.paused) || (row?.regenActive ?: false)),
                    regenPaused = link && watch.paused && !(row?.regenActive ?: false),
                    regenStartedAtMs = if (watch.active) t0 + watch.startedAtMs else 0,
                    estimate = if (liveNow) est else lastEstimate.ifEmpty { "--" },
                    estMiles = h.estMilesToRegen,
                    estNotSoon = h.estNotSoon,
                    estFromLiveRate = h.estFromLiveRate,
                    triggerG = health.regenTriggerG(),
                    rateGPerMi = h.estRateGPerMi,
                    sootZone = zone.zone,
                    history = history.toList(),
                    health = HealthSummary(
                        regensToday = health.regensToday(now),
                        regensThisWeek = health.regensThisWeek(now),
                        avgIntervalMi = health.avgRegenIntervalMi(),
                        regensSinceOil = h.regensSinceOil,
                        oilChangeOdoMi = h.oilChangeOdometerMi,
                        lastWarmupMin = h.lastWarmupMinutes,
                        warmupMedianMin = health.warmupMedianMin(),
                        batteryV = h.lastBatteryV,
                    ),
                    session = session.number,
                    rows = session.rows,
                    lastEvent = lastEvent,
                    lastAlert = lastAlert,
                    idleBlockage = link && row != null && idleWatch.flagged,
                    writeError = session.writeError,
                    gpsFix = !gps.lat.isNaN(),
                    gpsSpeedKmh = gps.speedKmh,
                    altitudeM = grade.altitudeM,
                    gradePct = grade.gradePct,
                ),
            )
        }

        // One regen message: every event refreshes the screens; only the ones the driver should be
        // interrupted with (start / finished / cut short) alert on the phone and car and go to Telegram.
        fun postRegenEvent(ev: RegenEvent) {
            lastEvent = ev.text
            if (!ev.notify) return
            lastAlert = ev.text
            // A short title that fits the notification shade and the car's heads-up; the full advice is the body.
            val title = when (ev.type) {
                RegenEvent.Type.START -> "Filter cleaning started"
                RegenEvent.Type.END -> "Filter cleaning finished"
                RegenEvent.Type.INTERRUPTED -> "Filter cleaning cut short"
                else -> ev.headline
            }
            alert(title, ev.text, if (ev.type == RegenEvent.Type.START) NOTIF_REGEN_START else NOTIF_REGEN_END, content = ev.headline)
            telegram.event(ev.text)
        }

        fun connectAdapter(): Boolean {
            if (!elm.bluetoothOn) { status = "Bluetooth is off"; return false }
            // A saved adapter is reconnected by address; every third failure scans instead, so a
            // replaced adapter is found without a trip to the settings. Android pauses unfiltered
            // scans while the screen is off, so that rescan only runs with the screen on.
            val screenOn = power?.isInteractive ?: true
            val saved = prefs.adapterAddress
            val useSaved = saved.isNotEmpty() && (connectFails % 3 != 2 || !screenOn)
            status = if (useSaved) "Connecting to ${prefs.adapterName.ifEmpty { saved }}…" else "Scanning for the OBD adapter…"
            publish(latest)
            val dev = elm.find(if (useSaved) saved else "", onlyAddress = prefs.assocMac)
            if (dev == null) {
                connectFails++
                status = if (saved.isEmpty() && !screenOn) "Turn the phone screen on once so it can find the OBD adapter"
                else "No adapter found: plug it in, ignition on, and close other OBD apps"
                return false
            }
            if (!elm.connect(dev) || !poller.initAdapter(ms())) {
                elm.close()
                connectFails++
                status = "Adapter found but it didn't answer, retrying"
                return false
            }
            connectFails = 0
            adapterReached = true
            startNotice.reset()   // a new connection: say so on Telegram once the car answers
            prefs.adapterAddress = dev.address
            prefs.adapterName = elm.name
            status = "Connected to ${elm.name}"
            return true
        }

        try {
            publish(null)
            while (running) {
                var now = ms()
                // Parked, or walked off with the phone: give up rather than retry all night; the
                // automatic start (adapter in range, car Bluetooth, Android Auto) brings it back
                // next drive. An automatic start that never reached the car gives up sooner.
                // A trigger that arrived while this session was still looking (AutoStart) restarts the clock.
                val ext = extendAtElapsed.let { if (it > t0Elapsed) it - t0Elapsed else 0L }
                val firstLimit = firstContactLimitMs
                // The short limit is for "adapter never reached" only: once it answers, somebody is
                // in the car (ignition on, engine not started yet), so wait the full time.
                val idleLimit = if (firstLimit > 0L && lastCarMs == 0L && !adapterReached) maxOf(firstLimit, if (ext > 0L) extendLimitMs else 0L) else IDLE_STOP_MS
                if (now - maxOf(lastCarMs, ext) >= idleLimit) {
                    stopReason = "Stopped: no data from the car for ${idleLimit / 60_000} min"
                    break
                }
                if (!elm.connected()) {
                    if (now - lastReconnect >= RECONNECT_MS) {
                        // A blank row marks the gap in the CSV, once per disconnection.
                        if (latest != null) session.writeRow(now, unix(), Row().values)
                        latest = null
                        connectAdapter()
                        if (!running) break
                        lastReconnect = ms()
                        publish(latest)
                        // The notification must not keep saying "Soot 12.3 g" while the link is down.
                        if (!elm.connected()) notifyLive(status, searching = true)
                    }
                    // Telegram keeps working while the adapter is out of reach: waiting alerts are
                    // retried and /status gets its "car off or adapter out of range" answer.
                    if (now - lastTelegram >= 15_000) {
                        lastTelegram = now
                        val u = prefs.units
                        telegram.tick(
                            statusText = { Messages.status(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), null, "--", u) },
                            latestLine = Messages.latestLine(lastGood, u),
                            currentSession = session.number,
                        )
                    }
                    Thread.sleep(200)
                    continue
                }

                // While nothing decodes (ECU asleep, adapter still powered) keep the first few
                // cycles of raw replies and then one in sixty, so a parked car doesn't fill the phone.
                val keepRaw = blankRows < 5 || (++deadRawSkip % 60) == 0L
                if (!poller.step(now, keepRaw)) Thread.sleep(5)

                now = ms()
                if (now - lastRow < ROW_MS) continue
                lastRow = now
                val row = poller.assembleRow(now)
                latest = row
                val wall = unix()
                gps = sensors.sample()
                // OBD speed for distance (steadier); GPS speed while the ECU isn't answering.
                grade.update(now, if (row.speed.isNaN()) gps.speedKmh else row.speed,
                    GradeTracker.pressureAltitudeM(gps.hpa), gps.altM, gps.vAccM)
                val keepPos = prefs.logPosition
                extra = doubleArrayOf(
                    if (keepPos) gps.lat else Double.NaN, if (keepPos) gps.lon else Double.NaN,
                    gps.speedKmh, gps.altM, gps.vAccM, gps.hpa, grade.altitudeM, grade.gradePct,
                )
                session.writeRow(now, wall, row.values, extra)
                session.flushRaw()

                val ev = watch.update(now, wall, row["dpf_regen_active"], row.egt, row.soot, row.sinceRegen, row.diffPressure,
                    if (row.speed.isNaN()) gps.speedKmh else row.speed, rpm = row.rpm)
                idleWatch.update(now, row.rpm, row.speed, row.diffPressure, row.coolant, watch.active || row.regenActive)
                if (ev.type != RegenEvent.Type.NONE) postRegenEvent(ev)
                // Kept until a row has the odometer in it: a tap is not lost on a row without one.
                if (!row.odometer.isNaN() && oilChangeRequested.getAndSet(false)) health.oilChangeNow(row.odometer)
                health.update(row, ev, now, wall)
                for (a in health.takeAlerts()) {
                    val text = a.text(prefs.units)
                    alert("DPF Sentinel health", text, NOTIF_HEALTH + a.ordinal)
                    telegram.event(text)
                }
                if (health.dirty) { prefs.health = health.state.encode(); health.dirty = false }

                zone.update(row.soot)?.let { z ->
                    val text = if (z == 2) "Soot ${"%.1f".format(row.soot)} g: at this car's regen level. A steady 20+ min drive at ${prefs.units.speed(80.0)}+ helps it finish."
                    else "Soot ${"%.1f".format(row.soot)} g: rising towards a regeneration."
                    alert(if (z == 2) "Soot high" else "Soot rising", text, NOTIF_SOOT)
                }

                if (row.anyPid()) {
                    lastCarMs = now
                    // Woken after a silence: requests the sleeping car ignored were backed off to 30 s.
                    if (blankRows > 0) poller.retryAll(now)
                    lastKnown.update(row, System.currentTimeMillis())
                    lastGood = lastKnown.row()
                    if (now - lastGoodSaved >= 30_000L) {
                        lastGoodSaved = now
                        lastGood?.let { prefs.lastRow = it.encode() }
                        prefs.lastRowAt = lastKnown.atMs
                        prefs.lastEstimate = lastEstimate
                    }
                }
                // "Connected and logging" on Telegram: once per connection, with the first real readings.
                // Not counted while Telegram is off, so setting it up mid-drive still gets the message.
                if (prefs.tgStartNotice && telegram.enabled && startNotice.due(now, row.anyPid(), !row.soot.isNaN())) {
                    val wallMs = System.currentTimeMillis()
                    // The first one of a session always goes; the gap only quietens a link that keeps dropping.
                    if (noticesSent == 0 || StartNotice.allowed(wallMs, prefs.tgLastStartNotice)) {
                        prefs.tgLastStartNotice = wallMs
                        telegram.event(
                            Messages.loggingStarted(
                                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), session.number, startedHow,
                                noticesSent > 0, row, Messages.estimate(health.state, prefs.units), prefs.units,
                            ),
                            low = true,
                        )
                        noticesSent++
                    }
                }
                blankRows = if (row.anyPid()) 0 else blankRows + 1
                status = when {
                    row.anyPid() -> "Connected to ${elm.name}"
                    else -> "Connected to ${elm.name}, waiting for the engine"
                }
                if (blankRows == BLANK_ROWS_REINIT) {
                    poller.initAdapter(ms())
                } else if (blankRows >= BLANK_ROWS_RECONNECT) {
                    blankRows = 0
                    elm.close()
                }

                history.addLast(HistoryPoint(now, row.soot, row.diffPressure, row.egt, row.rpm))
                while (history.isNotEmpty() && now - history.first().ms > HISTORY_MS) history.removeFirst()
                publish(row)

                if (now - lastTelegram >= 15_000) {
                    lastTelegram = now
                    val snapRow = row
                    val u = prefs.units
                    val est = Messages.estimate(health.state, u)
                    telegram.tick(
                        statusText = { Messages.status(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), snapRow, est, u) },
                        latestLine = Messages.latestLine(snapRow, u),
                        currentSession = session.number,
                    )
                }
                if (now - lastNotif >= 5000) {
                    lastNotif = now
                    updateLiveNotification(row, Messages.estimate(health.state, prefs.units))
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Exception) {
            Log.e(TAG, "logger loop crashed", e)
            crashed = true
        } finally {
            // A regen still open when logging stops gets its closing message (finished or cut short).
            runCatching {
                val now = ms()
                val wall = unix()
                val ev = watch.finish(now, wall)
                if (ev.type != RegenEvent.Type.NONE) {
                    postRegenEvent(ev)
                    health.update(latest ?: Row(), ev, now, wall)
                    val event = lastEvent
                    val alerted = lastAlert
                    LiveState.update { it.copy(regenActive = false, regenPaused = false, regenStartedAtMs = 0, lastEvent = event, lastAlert = alerted) }
                }
            }.onFailure { Log.w(TAG, "closing the open regen failed", it) }
            lastGood?.let { prefs.lastRow = it.encode(); prefs.lastRowAt = lastKnown.atMs; prefs.lastEstimate = lastEstimate }
            // For AutoStart: a session that never heard the car makes the adapter trigger wait longer next time.
            if (lastCarMs > 0L) prefs.emptySessions = 0
            else if (!crashed) { prefs.emptySessions = prefs.emptySessions + 1; prefs.emptyEndedAt = System.currentTimeMillis() }
            elm.close()
            sensors.stop()
            session.close()
            if (health.dirty) prefs.health = health.state.encode()
        }
        return crashed
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun liveNotification(text: String, searching: Boolean = false): Notification =
        NotificationCompat.Builder(this, DpfApp.CH_LIVE)
            .setSmallIcon(R.drawable.ic_dot)
            .setContentTitle(if (searching) "DPF Sentinel is looking for the car" else "DPF Sentinel is logging")
            .setContentText(text)
            .setSubText(if (gpsOn) "car data + GPS" else "car data only")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    @SuppressLint("MissingPermission")
    private fun notifyLive(text: String, searching: Boolean) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_LIVE, liveNotification(text, searching)) }
    }

    @SuppressLint("MissingPermission")
    private fun updateLiveNotification(row: Row, estimate: String) {
        val text = if (row.soot.isNaN()) LiveState.state.value.status
        else "Soot ${"%.1f".format(row.soot)} g · next regen $estimate" + if (LiveState.state.value.regenActive) " · " + Messages.REGEN_TICK.trimEnd('.') else if (LiveState.state.value.regenPaused) " · " + Messages.REGEN_PAUSED.trimEnd('.') else ""
        NotificationManagerCompat.from(this).notify(NOTIF_LIVE, liveNotification(text))
    }

    // Posted to the phone and, through CarAppExtender, to the Android Auto screen.
    @SuppressLint("MissingPermission")
    private fun alert(title: String, text: String, id: Int, content: String = text.substringAfter('\n', text)) {
        val b = NotificationCompat.Builder(this, DpfApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_dot)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text.substringAfter('\n', text).substringBefore('\n'))
                    .setSmallIcon(R.drawable.ic_dot)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build(),
            )
        runCatching { CarNotificationManager.from(this).notify(id, b) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    companion object {
        private const val TAG = "LoggerService"
        private const val NOTIF_LIVE = 1
        private const val NOTIF_REGEN_START = 10
        private const val NOTIF_REGEN_END = 11
        private const val NOTIF_SOOT = 12
        private const val NOTIF_HEALTH = 20
        private const val ROW_MS = 1000L
        private const val RECONNECT_MS = 5000L
        private const val HISTORY_MS = 120_000L
        private const val BLANK_ROWS_REINIT = 10
        private const val BLANK_ROWS_RECONNECT = 30
        private const val IDLE_STOP_MS = 10 * 60_000L
        const val AUTO_GIVE_UP_MS = 3 * 60_000L
        private const val EXTRA_AUTO = "auto"
        private const val EXTRA_GIVE_UP = "giveUpMs"
        private const val EXTRA_ADD_GPS = "addGps"

        /** After Stop is tapped, opening the app does not restart logging for this long (Start always does). */
        const val STOP_HOLDS_MS = 30 * 60_000L

        fun stoppedRecently(context: Context): Boolean {
            val at = Prefs(context).stoppedAt
            val now = System.currentTimeMillis()
            return at > 0L && now >= at && now - at < STOP_HOLDS_MS
        }

        // A trigger while a session is still looking for the car: when (elapsedRealtime) and for how long.
        @Volatile private var extendAtElapsed = 0L
        @Volatile private var extendLimitMs = 0L

        fun extend(giveUpMs: Long) {
            extendLimitMs = maxOf(giveUpMs, AUTO_GIVE_UP_MS)
            extendAtElapsed = SystemClock.elapsedRealtime()
        }
        private const val CRASH_WINDOW_MS = 10 * 60_000L
        private const val MAX_CRASHES = 3

        /** The one live logging thread in this process (see runWorker). */
        private val activeWorker = java.util.concurrent.atomic.AtomicReference<Thread?>(null)

        /** Set by the "Oil changed" button; applied by the logger on its next row. */
        val oilChangeRequested = AtomicBoolean(false)

        /** Whether the running session has the location service type, i.e. also logs GPS. */
        @Volatile var gpsOn = false
            private set

        /**
         * Throws if Android refuses a foreground-service start (e.g. from the background).
         * [auto] marks a start nobody tapped (see AutoStart): car data only, and a shorter give-up.
         * [giveUpMs] > 0: stop if the car has not answered once within that time.
         */
        fun start(context: Context, auto: Boolean = false, giveUpMs: Long = 0L) {
            if (!auto) Prefs(context).stoppedAt = 0L
            ContextCompat.startForegroundService(
                context,
                Intent(context, LoggerService::class.java).putExtra(EXTRA_AUTO, auto).putExtra(EXTRA_GIVE_UP, giveUpMs),
            )
        }

        /** The app is on screen during a running session: restart the service type so GPS is logged too. */
        fun addGps(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LoggerService::class.java).putExtra(EXTRA_ADD_GPS, true))
        }

        fun stop(context: Context) {
            Prefs(context).stoppedAt = System.currentTimeMillis()
            context.stopService(Intent(context, LoggerService::class.java))
        }
    }
}
