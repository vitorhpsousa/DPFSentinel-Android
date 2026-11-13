package io.github.vitorhpsousa.dpfsentinel.core

import java.time.ZoneOffset
import io.github.vitorhpsousa.dpfsentinel.core.RegenEvent.Type as T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthTest {
    private val nan = Double.NaN
    private val day = 1_800_000_000.0   // some instant; tests only use offsets from it

    private fun row(soot: Double = nan, dist: Double = nan, odo: Double = nan, coolant: Double = nan, rpm: Double = nan): Row = Row().also {
        it.values[Pids.col("dpf_soot_level_g")] = soot
        it.values[Pids.col("dpf_dist_since_regen_mi")] = dist
        it.values[Pids.col("odometer_mi")] = odo
        it.values[Pids.col("coolant_temp")] = coolant
        it.values[Pids.col("engine_rpm")] = rpm
    }

    private fun ev(t: T) = RegenEvent(t, "x")

    @Test fun interruptedThenRetriedIsOneRegen() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        h.update(row(18.0, 60.0, 50_060.0), ev(T.START), 0, day)
        h.update(row(15.3, 61.5, 50_061.5), ev(T.INTERRUPTED), 1000, day + 300)
        assertTrue(h.state.regenHistory.last().interrupted)
        h.update(row(17.6, 69.3, 50_069.3), ev(T.START), 2000, day + 3600)
        h.update(row(16.9, 70.0, 50_070.0), ev(T.INTERRUPTED), 3000, day + 3800)
        h.update(row(17.6, 89.0, 50_089.0), ev(T.START), 4000, day + 7200)
        assertEquals(1, h.state.regenHistory.size)
        assertEquals(3, h.state.regenHistory[0].attempts)
        assertFalse(h.state.regenHistory[0].interrupted)
        assertEquals(60.0, h.state.regenHistory[0].milesSincePrev)
        assertEquals(1, h.state.regensSinceOil)
        assertEquals(listOf(18.0), h.state.triggerHist)     // only the first start teaches the trigger
        assertEquals(1, h.regensToday(day + 7300))
        assertEquals(1, h.regensThisWeek(day + 7300))
        h.update(row(1.0, 0.01, 50_104.0), ev(T.END), 5000, day + 8100)
        assertFalse(h.state.regenHistory[0].interrupted)
    }

    @Test fun retryInALaterSessionIsStillOneRegen() {
        val a = HealthTracker(zone = ZoneOffset.UTC)
        a.update(row(18.0, 52.7, 60_052.7), ev(T.START), 0, day)
        a.update(row(16.1, 53.0, 60_053.0), ev(T.INTERRUPTED), 1000, day + 200)
        // Next drive, new session: state comes back from its saved form.
        val b = HealthTracker(HealthState.decode(a.state.encode()), ZoneOffset.UTC)
        b.update(row(16.1, 53.1, 60_053.1), ev(T.START), 0, day + 5000)
        assertEquals(1, b.state.regenHistory.size)
        assertEquals(2, b.state.regenHistory[0].attempts)
        assertEquals(1, b.state.regensSinceOil)
        assertEquals(2, HealthState.decode(b.state.encode()).regenHistory[0].attempts)
    }

    @Test fun regenAfterADistanceResetIsANewOne() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        h.update(row(18.0, 90.0, 50_090.0), ev(T.START), 0, day)
        h.update(row(1.0, 0.01, 50_102.0), ev(T.END), 1000, day + 900)
        h.update(row(18.0, 85.0, 50_187.0), ev(T.START), 2000, day + 90_000)
        assertEquals(2, h.state.regenHistory.size)
        assertEquals(2, h.state.regensSinceOil)
        assertEquals(listOf(18.0, 18.0), h.state.triggerHist)
    }

    // The previous regen was cut short, then completed while nothing was logging, and the next
    // cycle's regen starts further along: the odometer tells them apart even though no reset was seen.
    @Test fun newCycleIsRecognisedEvenIfItsResetWasNeverLogged() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        h.update(row(18.0, 60.0, 50_060.0), ev(T.START), 0, day)
        h.update(row(15.0, 60.5, 50_060.5), ev(T.INTERRUPTED), 1000, day + 100)
        h.update(row(18.0, 90.0, 50_170.0), ev(T.START), 2000, day + 90_000)
        assertEquals(2, h.state.regenHistory.size)
        assertEquals(2, h.state.regensSinceOil)
    }

    @Test fun withoutAnOdometerTheSavedCycleFlagDecides() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        h.update(row(18.0, 60.0), ev(T.START), 0, day)
        h.update(row(15.0, 61.0), ev(T.INTERRUPTED), 1000, day)
        h.update(row(17.0, 65.0), ev(T.START), 2000, day)
        assertEquals(1, h.state.regensSinceOil)
        // Distance resets (two rows near zero with an empty filter): the next start is a new regen.
        h.update(row(1.0, 0.1), RegenEvent.NONE, 3000, day)
        h.update(row(1.0, 0.2), RegenEvent.NONE, 4000, day)
        h.update(row(18.0, 70.0), ev(T.START), 5000, day)
        assertEquals(2, h.state.regensSinceOil)
    }

    @Test fun blobWrittenBeforeAttemptsStillDecodes() {
        val old = "ver=1\nregens=50060.0,60.0,true,1.8E9;50200.0,80.0,false,1.80009E9\nsinceOil=7\noilOdo=49000.0\n" +
            "warmups=6.5;7.0\nlastWarm=7.0\nbaseSoot=2.0\nbaseDist=0.5\ntriggers=18.0;17.6\ncycStart=true\nlastRate=0.15"
        val s = HealthState.decode(old)
        assertEquals(2, s.regenHistory.size)
        assertEquals(1, s.regenHistory[0].attempts)
        assertTrue(s.regenHistory[0].interrupted)
        assertEquals(80.0, s.regenHistory[1].milesSincePrev)
        assertEquals(7, s.regensSinceOil)
        assertEquals(listOf(6.5, 7.0), s.warmupMinutes)
        assertEquals(listOf(18.0, 17.6), s.triggerHist)
        assertTrue(s.cycleHadStart)
        assertEquals(0.15, s.lastRateGPerMi)
        // And what is written now reads back the same.
        val again = HealthState.decode(s.encode())
        assertEquals(s.encode(), again.encode())
    }

    // ---- warm-up ----

    private fun warm(h: HealthTracker, fromMs: Long, secs: Int, coolant: Double, rpm: Double): Long {
        for (i in 0 until secs) h.update(row(coolant = coolant, rpm = rpm), RegenEvent.NONE, fromMs + i * 1000L, nan)
        return fromMs + secs * 1000L
    }

    @Test fun warmupCountsOnlyEngineRunningTime() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        var t = warm(h, 0, 180, 20.0, 900.0)         // 3 min running
        t = warm(h, t, 100, 30.0, 0.0)               // 100 s stalled / key on, engine off
        t = warm(h, t, 60, 30.0, nan)                // rpm not answering
        t = warm(h, t, 120, 60.0, 1500.0)            // 2 more min running
        warm(h, t, 1, 80.0, 1500.0)
        assertEquals(5.0, h.state.lastWarmupMinutes, 0.05)
        assertEquals(1, h.state.warmupMinutes.size)
    }

    @Test fun warmupSampleIsDroppedOnAGap() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        var t = warm(h, 0, 120, 20.0, 900.0)
        t += 10 * 60_000L                            // adapter out of range for 10 min
        t = warm(h, t, 60, 70.0, 1500.0)
        warm(h, t, 5, 85.0, 1500.0)
        assertTrue(h.state.warmupMinutes.isEmpty())
        assertTrue(h.state.lastWarmupMinutes.isNaN())
    }

    @Test fun warmupSampleIsDroppedWhenCoolantGoesMissing() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        var t = warm(h, 0, 120, 20.0, 900.0)
        t = warm(h, t, 130, nan, 900.0)              // rows keep coming, coolant does not
        warm(h, t, 5, 85.0, 1500.0)
        assertTrue(h.state.warmupMinutes.isEmpty())
    }

    @Test fun warmupNeedsAColdFirstReading() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        var t = warm(h, 0, 60, 55.0, 900.0)          // logging started with the engine part-warm
        t = warm(h, t, 60, 35.0, 0.0)                // cools while parked with the adapter live
        t = warm(h, t, 600, 60.0, 1500.0)
        warm(h, t, 5, 85.0, 1500.0)
        assertTrue(h.state.warmupMinutes.isEmpty())
    }
}
