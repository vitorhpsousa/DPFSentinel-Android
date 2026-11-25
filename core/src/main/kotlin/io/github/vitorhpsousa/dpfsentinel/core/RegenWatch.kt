package io.github.vitorhpsousa.dpfsentinel.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Turns the decoded regen flag into start / finished / cut-short messages. The rules, their
// constants and the test vectors shared with the firmware are in core/SPEC-regen.md.
//
// What the logs up to 2026-11-07 show (31 flag episodes, replayed by RealLogReplayTest):
//  - a regen completed if and only if distance-since-regen fell to ~0, in the same reply as the
//    flag going off (15 of 15 completed, 0 of 16 others);
//  - the flag going off with the engine at 0 rpm is the key being turned: the attempt is over and
//    the ECU starts again on a later drive. It never "paused at idle": completed regens sat at a
//    standstill with the engine running for up to ~100 s with the flag still on;
//  - the flag can blink off for a few seconds while driving and come straight back.

/**
 * One message from [RegenWatch].
 *
 * [text] is two parts split by the first '\n': [headline], a plain instruction for the driver, and
 * [detail], the numbers (soot, EGT, minutes, clock) for the Telegram report and the event list.
 *
 * [notify] says whether the driver should be interrupted with it (sound / heads-up notification):
 * true for START, END and INTERRUPTED; false for PAUSED, RESUMED and TICK, which only refresh the
 * screens and may still go to the Telegram log.
 */
class RegenEvent(val type: Type, val text: String = "", val notify: Boolean = type.notifies) {
    enum class Type(val notifies: Boolean) {
        NONE(false), START(true), END(true), INTERRUPTED(true), TICK(false), PAUSED(false), RESUMED(false)
    }

    val headline: String get() = text.substringBefore('\n')
    val detail: String get() = text.substringAfter('\n', "")

    companion object {
        val NONE = RegenEvent(Type.NONE)
    }
}

// units: read on every message, so a change in the settings applies to the next one.
class RegenWatch(private val zone: ZoneId = ZoneId.systemDefault(), private val units: () -> Units = { Units.UK }) {
    private var inRegen = false
    private var isPaused = false
    private var activeStreak = 0
    private var inactiveStreak = 0
    private var stoppedStreak = 0
    private var startMs = 0L
    private var lastValidMs = 0L
    private var lastTickMs = 0L
    private var pausedAtMs = 0L
    private var movingWhilePausedMs = 0L
    private var startWall = 0.0
    private var peakEgt = 0.0
    private var sootStart = 0.0
    private var lastSoot = 0.0
    private var haveSoot = false
    private var distRef = Double.NaN     // furthest distance-since-regen seen during this regen
    private var resetSeen = false        // distance-since-regen fell to ~0 during this regen

    /** A regen is open: running or on hold. */
    val active get() = inRegen
    /** The open regen is on hold: the flag is off (or data stopped) but it has not been called finished or cut short yet. */
    val paused get() = inRegen && isPaused
    val startedAtMs get() = startMs

    private fun fmt(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

    private fun clock(wall: Double): String {
        if (wall.isNaN() || wall < 1735689600.0) return ""   // clock not set (before 2025-01-01)
        return " at " + DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(wall.toLong()))
    }

    private fun pressure(p: Double) = if (p.isNaN()) "" else ", DPF pressure ${fmt(p, 0)} hPa"

    private fun event(type: RegenEvent.Type, detail: String) = RegenEvent(type, Messages.regenHeadline(type) + "\n" + detail)

    private fun pause(atMs: Long) {
        isPaused = true
        pausedAtMs = atMs
        movingWhilePausedMs = 0
    }

    private fun soot() = if (haveSoot) lastSoot else Double.NaN
    private fun emptyFilter() = haveSoot && lastSoot <= EMPTY_SOOT_G   // unknown soot never counts as empty

    // Closes the open regen. endMs: when it stopped burning (the flag drop, or the last data).
    private fun close(type: RegenEvent.Type, endMs: Long, wallClock: Double, why: String = "", headline: String? = null): RegenEvent {
        inRegen = false
        isPaused = false
        activeStreak = 0
        inactiveStreak = 0
        val mins = (endMs - startMs).coerceAtLeast(0) / 60000
        val sootNow = soot()
        val tail = "peak EGT ${units().temp(peakEgt)}, soot ${Units.num(sootStart, 1)} g -> ${Units.num(sootNow, 1)} g" + clock(wallClock)
        return if (type == RegenEvent.Type.END) event(type, "took about $mins min, $tail")
        else {
            val detail = "ran about $mins min${if (why.isEmpty()) "" else " ($why)"}, $tail"
            if (headline == null) event(type, detail) else RegenEvent(type, headline + "\n" + detail)
        }
    }

    // [unseenEnd]: the data simply stopped, so nobody saw how it ended; do not claim it was cut short.
    private fun closeByFilter(endMs: Long, wallClock: Double, why: String, unseenEnd: Boolean = false) =
        if (resetSeen || emptyFilter()) close(RegenEvent.Type.END, endMs, wallClock, why)
        else close(RegenEvent.Type.INTERRUPTED, endMs, wallClock, why, if (unseenEnd) Messages.REGEN_UNSEEN_END else null)

    /**
     * Call when logging stops (session end, service shutdown, crash handler) so a regen that is
     * still open is never left without a closing message. Returns END if the completion signal was
     * already seen, otherwise INTERRUPTED; NONE if no regen was open. The watch is idle afterwards.
     */
    fun finish(nowMs: Long, wallClock: Double): RegenEvent {
        if (!inRegen) return RegenEvent.NONE
        val endMs = if (isPaused) pausedAtMs else lastValidMs
        // The car's own "finished" signal was seen: finished. Otherwise nobody saw the end, which is
        // not the same as "cut short" (the engine may still be running and finishing it).
        return if (resetSeen || emptyFilter()) close(RegenEvent.Type.END, endMs, wallClock)
        else close(RegenEvent.Type.INTERRUPTED, endMs, wallClock, "logging stopped while it was running", Messages.REGEN_UNSEEN_END)
    }

    /**
     * One call per logged row.
     *
     * @param active the regen flag (1/0), NaN when the ECU did not answer.
     * @param speedKmh NaN when unknown.
     * @param rpm engine speed; NaN when the ECU did not answer it. Pass it on every call: with it, a
     *   flag drop is judged by the engine (stopped = cut short at once, running = on hold until
     *   the completion signal, the flag or a timeout settles it). `null` (the default, for callers
     *   written before this parameter) keeps the old speed-based rule: standing = PAUSED,
     *   moving or unknown speed = END.
     */
    fun update(
        nowMs: Long, wallClock: Double, active: Double, egtC: Double, sootG: Double, distMi: Double, pressureHpa: Double,
        speedKmh: Double = Double.NaN, rpm: Double? = null,
    ): RegenEvent {
        val byEngine = rpm != null
        if (active.isNaN()) {
            // The ECU has stopped answering altogether (asleep, adapter unplugged) mid-regen.
            if (byEngine && inRegen && nowMs - lastValidMs > GAP_MS) {
                return closeByFilter(if (isPaused) pausedAtMs else lastValidMs, wallClock, "no data from the engine", unseenEnd = true)
            }
            return RegenEvent.NONE
        }

        // Data is back after a long gap with a regen open: hold it and let the rows that follow
        // settle it (flag back on = carried on, distance reset = finished, engine off = cut short).
        if (inRegen && !isPaused && nowMs - lastValidMs > GAP_MS) {
            pause(lastValidMs)
            activeStreak = 0
            inactiveStreak = 0
            stoppedStreak = 0
            lastValidMs = nowMs
            return event(
                RegenEvent.Type.PAUSED,
                "no data for ${(nowMs - pausedAtMs) / 60000} min; ran about ${(pausedAtMs - startMs) / 60000} min, " +
                    "peak EGT ${units().temp(peakEgt)}" + clock(wallClock),
            )
        }
        val dt = (nowMs - lastValidMs).coerceIn(0, 5000)
        lastValidMs = nowMs
        if (!sootG.isNaN()) { lastSoot = sootG; haveSoot = true }
        val soot = soot()
        if (inRegen && !egtC.isNaN() && egtC > peakEgt) peakEgt = egtC

        if (active > 0.5) { activeStreak++; inactiveStreak = 0 } else { inactiveStreak++; activeStreak = 0 }
        val stopped = rpm == null || rpm.isNaN() || rpm < ENGINE_RUNNING_RPM
        stoppedStreak = if (stopped) stoppedStreak + 1 else 0
        val engineOff = byEngine && stoppedStreak >= STREAK_ROWS

        // The completion signal: distance-since-regen back near zero, well below where it was.
        if (inRegen && !distMi.isNaN()) {
            if (!distRef.isNaN() && distMi < RESET_MAX_DIST_MI && distMi + RESET_DROP_MI < distRef) resetSeen = true
            else if (distRef.isNaN() || distMi > distRef) distRef = distMi
        }

        if (paused) {
            if (activeStreak >= STREAK_ROWS) {
                isPaused = false
                lastTickMs = nowMs
                return event(
                    RegenEvent.Type.RESUMED,
                    "after ${(nowMs - pausedAtMs) / 1000} s off, soot ${Units.num(soot, 1)} g, " +
                        "EGT ${units().temp(if (egtC.isNaN()) 0.0 else egtC)}" + clock(wallClock),
                )
            }
            if (byEngine && active <= 0.5) {
                if (resetSeen || emptyFilter()) return close(RegenEvent.Type.END, pausedAtMs, wallClock)
                if (engineOff) return close(RegenEvent.Type.INTERRUPTED, pausedAtMs, wallClock, "engine switched off")
            }
            if (!speedKmh.isNaN() && speedKmh > STILL_KMH) movingWhilePausedMs += dt
            if (movingWhilePausedMs >= PAUSE_DRIVE_MS || nowMs - pausedAtMs >= PAUSE_MAX_MS) {
                return closeByFilter(pausedAtMs, wallClock, "it did not start again")
            }
            return RegenEvent.NONE
        }

        if (!inRegen && activeStreak >= STREAK_ROWS) {
            inRegen = true
            isPaused = false
            resetSeen = false
            distRef = distMi
            startMs = nowMs
            lastTickMs = nowMs
            peakEgt = if (egtC.isNaN()) 0.0 else egtC
            sootStart = soot   // NaN when not read yet: printed as "--"
            val distStart = if (distMi.isNaN()) 0.0 else distMi
            startWall = wallClock
            return event(
                RegenEvent.Type.START,
                // A value not read yet prints as "--", never as an invented 0.
                "soot ${Units.num(soot, 1)} g, ${units().distance(distMi, 1)} since the last regen, " +
                    "EGT ${units().temp(egtC)}" + pressure(pressureHpa) + clock(startWall),
            )
        }
        if (inRegen && inactiveStreak >= STREAK_ROWS) {
            if (resetSeen) return close(RegenEvent.Type.END, nowMs, wallClock)
            if (byEngine) {
                if (emptyFilter()) return close(RegenEvent.Type.END, nowMs, wallClock)
                if (engineOff) return close(RegenEvent.Type.INTERRUPTED, nowMs, wallClock, "engine switched off")
            } else {
                // Callers that do not pass rpm: the old rule. Standing with soot left = hold, otherwise finished.
                val standing = !speedKmh.isNaN() && speedKmh <= STILL_KMH
                if (!standing || emptyFilter()) return close(RegenEvent.Type.END, nowMs, wallClock)
            }
            pause(nowMs)
            return event(
                RegenEvent.Type.PAUSED,
                "regen flag went off with the engine running; ran ${(nowMs - startMs) / 60000} min so far, " +
                    "soot ${Units.num(soot, 1)} g" + clock(wallClock),
            )
        }
        if (inRegen && nowMs - lastTickMs >= TICK_MS) {
            lastTickMs = nowMs
            val mins = (nowMs - startMs) / 60000
            val secs = ((nowMs - startMs) / 1000) % 60
            return event(
                RegenEvent.Type.TICK,
                "$mins:${secs.toString().padStart(2, '0')} so far, EGT ${units().temp(if (egtC.isNaN()) 0.0 else egtC)}, " +
                    "soot ${Units.num(soot, 1)} g" + pressure(pressureHpa) + clock(wallClock),
            )
        }
        return RegenEvent.NONE
    }

    companion object {
        const val STREAK_ROWS = 3            // rows a change must hold (flag on, flag off, engine off)
        const val GAP_MS = 120_000L          // no data this long mid-regen: on hold, or cut short if rows keep coming empty
        const val TICK_MS = 30_000L          // status ping while a regen is running
        const val STILL_KMH = 3.0            // at or below this the car counts as standing
        const val EMPTY_SOOT_G = 3.0         // fallback completion signal: soot at or below this after a flag drop
        const val ENGINE_RUNNING_RPM = 500.0 // below this (or no rpm reading) the engine counts as stopped
        const val RESET_MAX_DIST_MI = 1.0    // completion signal: distance-since-regen below this ...
        const val RESET_DROP_MI = 0.5        // ... and at least this far below the furthest seen in this regen
        const val PAUSE_DRIVE_MS = 3 * 60_000L   // on hold and driving this long without the flag: cut short
        const val PAUSE_MAX_MS = 20 * 60_000L    // ... or this long on hold in all
    }
}
