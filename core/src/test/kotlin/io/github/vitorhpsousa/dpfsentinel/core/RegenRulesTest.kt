package io.github.vitorhpsousa.dpfsentinel.core

import java.time.ZoneOffset
import io.github.vitorhpsousa.dpfsentinel.core.RegenEvent.Type as T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// One test per rule of core/SPEC-regen.md, on made-up rows. The real episodes are in RegenVectorTest.
class RegenRulesTest {
    private val nan = Double.NaN

    private class Car(val w: RegenWatch = RegenWatch(ZoneOffset.UTC)) {
        var t = 0L
        val events = mutableListOf<RegenEvent>()
        val types get() = events.map { it.type }
        fun rows(n: Int, flag: Double, rpm: Double, speed: Double = 50.0, soot: Double = 18.0, dist: Double = 80.0, stepMs: Long = 1000) = repeat(n) {
            val ev = w.update(t, 1.8e9, flag, 600.0, soot, dist, 30.0, speed, rpm)
            if (ev.type != RegenEvent.Type.NONE && ev.type != RegenEvent.Type.TICK) events += ev
            t += stepMs
        }
        fun regen(secs: Int = 120) = rows(secs, 1.0, 2000.0)
    }

    @Test fun keyOffIsCutShortWithinThreeRows() {
        val c = Car()
        c.regen()
        c.rows(2, 0.0, 0.0, speed = 0.0, soot = 15.3)
        assertEquals(listOf(T.START), c.types)
        c.rows(1, 0.0, 0.0, speed = 0.0, soot = 15.3)
        assertEquals(listOf(T.START, T.INTERRUPTED), c.types)
        assertFalse(c.w.active)
        assertEquals(Messages.REGEN_INTERRUPTED, c.events[1].headline)
        assertTrue("soot 18.0 g -> 15.3 g" in c.events[1].detail)
        assertTrue("engine switched off" in c.events[1].detail)
    }

    @Test fun flagDropWithNoRpmReadingIsCutShort() {
        val c = Car()
        c.regen()
        c.rows(3, 0.0, nan, speed = nan)
        assertEquals(listOf(T.START, T.INTERRUPTED), c.types)
    }

    @Test fun distanceResetIsAFinishEvenWithSootStillShowing() {
        val c = Car()
        c.regen(900)
        // The ECU's soot counter lags: 4.7 g on the row the flag and the distance go.
        c.rows(3, 0.0, 2000.0, soot = 4.7, dist = 0.01)
        assertEquals(listOf(T.START, T.END), c.types)
        assertEquals(Messages.REGEN_END, c.events[1].headline)
        assertTrue(c.events[1].detail.startsWith("took about 15 min"))
    }

    @Test fun distanceResetAtAStandstillWithEngineRunningIsAFinish() {
        val c = Car()
        c.regen(800)
        c.rows(100, 1.0, 800.0, speed = 0.0)                       // waiting at lights, regen carries on
        c.rows(3, 0.0, 800.0, speed = 0.0, soot = 4.7, dist = 0.0)
        assertEquals(listOf(T.START, T.END), c.types)
    }

    @Test fun completionAtKeyOffIsAFinishNotAnInterruption() {
        val c = Car()
        c.regen(900)
        c.rows(3, 0.0, 0.0, speed = 0.0, soot = 4.7, dist = 0.02)
        assertEquals(listOf(T.START, T.END), c.types)
    }

    @Test fun emptyFilterStaysAFallbackWhenDistanceIsMissing() {
        val c = Car()
        c.rows(120, 1.0, 2000.0, dist = nan)
        c.rows(3, 0.0, 2000.0, soot = 1.2, dist = nan)
        assertEquals(listOf(T.START, T.END), c.types)
    }

    @Test fun flagBlinkWhileDrivingIsHeldQuietlyAndCarriesOn() {
        val c = Car()
        c.regen(10)
        c.rows(13, 0.0, 1500.0, speed = 35.0)
        c.rows(60, 1.0, 1500.0, speed = 35.0)
        assertEquals(listOf(T.START, T.PAUSED, T.RESUMED), c.types)
        assertTrue(c.w.active && !c.w.paused)
        assertFalse(c.events[1].notify)
        assertFalse(c.events[2].notify)
        assertFalse("carry on" in c.events[1].text || "carries on" in c.events[1].text)
    }

    @Test fun heldRegenFinishesWhenTheResetShowsUpLater() {
        val c = Car()
        c.regen()
        c.rows(5, 0.0, 800.0, speed = 0.0, soot = 4.7)
        assertTrue(c.w.paused)
        c.rows(1, 0.0, 800.0, speed = 0.0, soot = 4.7, dist = 0.0)
        assertEquals(listOf(T.START, T.PAUSED, T.END), c.types)
    }

    @Test fun heldRegenIsCutShortWhenTheEngineStops() {
        val c = Car()
        c.regen()
        c.rows(30, 0.0, 800.0, speed = 0.0)
        c.rows(3, 0.0, 0.0, speed = 0.0)
        assertEquals(listOf(T.START, T.PAUSED, T.INTERRUPTED), c.types)
        // Timed from the flag drop, not from when it was noticed.
        assertTrue(c.events[2].detail.startsWith("ran about 2 min"))
    }

    @Test fun heldRegenTimesOutAfterTwentyMinutes() {
        val c = Car()
        c.regen()
        c.rows(3 + 20 * 60 - 1, 0.0, 800.0, speed = 0.0)
        assertEquals(listOf(T.START, T.PAUSED), c.types)
        c.rows(2, 0.0, 800.0, speed = 0.0)
        assertEquals(listOf(T.START, T.PAUSED, T.INTERRUPTED), c.types)
    }

    @Test fun finishClosesAnOpenRegen() {
        val c = Car()
        assertEquals(T.NONE, c.w.finish(0, nan).type)      // nothing open
        c.regen()
        val ev = c.w.finish(c.t, 1.8e9)
        assertEquals(T.INTERRUPTED, ev.type)
        assertTrue(ev.notify)
        assertTrue("logging stopped" in ev.detail)
        assertFalse(c.w.active)
        assertEquals(T.NONE, c.w.finish(c.t, 1.8e9).type)  // only once
    }

    @Test fun finishClosesAHeldRegen() {
        val c = Car()
        c.regen()
        c.rows(10, 0.0, 800.0, speed = 0.0)
        assertEquals(T.INTERRUPTED, c.w.finish(c.t, nan).type)
    }

    @Test fun finishRightAfterTheResetSaysFinished() {
        val c = Car()
        c.regen(900)
        c.rows(1, 0.0, 2000.0, soot = 4.7, dist = 0.01)    // logging stops inside the 3-row debounce
        assertEquals(T.END, c.w.finish(c.t, nan).type)
    }

    @Test fun engineGoingSilentMidRegenIsCutShortAfterTwoMinutes() {
        val c = Car()
        c.regen()
        c.rows(120, nan, nan, speed = nan, soot = nan, dist = nan)
        assertEquals(listOf(T.START), c.types)
        c.rows(2, nan, nan, speed = nan, soot = nan, dist = nan)
        assertEquals(listOf(T.START, T.INTERRUPTED), c.types)
        assertTrue("no data" in c.events[1].detail)
        // Nobody saw the end, so it must not claim "cut short".
        assertEquals(Messages.REGEN_UNSEEN_END, c.events[1].headline)
    }

    @Test fun gapInRowsThenFlagStillOnCarriesOn() {
        val c = Car()
        c.regen()
        c.t += 300_000                                     // adapter link lost for 5 min, nothing logged
        c.rows(4, 1.0, 2000.0)
        assertEquals(listOf(T.START, T.PAUSED, T.RESUMED), c.types)
    }

    @Test fun gapInRowsThenEngineOffIsCutShort() {
        val c = Car()
        c.regen()
        c.t += 300_000
        c.rows(4, 0.0, 0.0, speed = 0.0)
        assertEquals(listOf(T.START, T.PAUSED, T.INTERRUPTED), c.types)
    }

    @Test fun onlyStartEndAndInterruptedAskForANotification() {
        assertEquals(setOf(T.START, T.END, T.INTERRUPTED), T.entries.filter { RegenEvent(it).notify }.toSet())
        assertEquals("Filter cleaning finished. Nothing to do.", Messages.regenHeadline(T.END))
        assertEquals("Filter cleaning was cut short. It will try again on the next drive.", Messages.regenHeadline(T.INTERRUPTED))
        assertEquals(
            "The engine is cleaning its exhaust filter. If you can, keep driving for about 10 more minutes. If you've arrived, it's fine to switch off.",
            Messages.regenHeadline(T.START),
        )
    }

    @Test fun detailLineKeepsTheNumbersAndFollowsTheUnits() {
        val w = RegenWatch(ZoneOffset.UTC) { Units.US }
        var ev = RegenEvent.NONE
        for (i in 0 until 3) ev = w.update(i * 1000L, 1.8e9, 1.0, 600.0, 18.0, 250.0, 30.0, 50.0, 2000.0)
        assertEquals("soot 18.0 g, 250.0 mi since the last regen, EGT 1112 °F, DPF pressure 30 hPa at 08:00", ev.detail)
        assertEquals(Messages.REGEN_START + "\n" + ev.detail, ev.text)
    }
}
