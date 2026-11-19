package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerBackoffTest {
    private val t = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun `wait doubles and is capped`() {
        assertEquals(0L, TriggerBackoff.waitMs(0))
        assertEquals(5 * min, TriggerBackoff.waitMs(1))
        assertEquals(10 * min, TriggerBackoff.waitMs(2))
        assertEquals(40 * min, TriggerBackoff.waitMs(4))
        assertEquals(120 * min, TriggerBackoff.waitMs(6))
        assertEquals(120 * min, TriggerBackoff.waitMs(500))
    }

    @Test fun `no history, nothing ignored`() {
        assertFalse(TriggerBackoff.ignoreAdapter(t, 0, 0, 0))
        assertFalse(TriggerBackoff.ignoreAdapter(t, 0, t - min, 0), "a session that heard the car resets the count")
    }

    @Test fun `ignored after a session without car data, then allowed again`() {
        assertTrue(TriggerBackoff.ignoreAdapter(t, 1, t - 10_000, 0))
        assertTrue(TriggerBackoff.ignoreAdapter(t, 1, t - 5 * min + 1, 0))
        assertFalse(TriggerBackoff.ignoreAdapter(t, 1, t - 5 * min, 0))
        assertTrue(TriggerBackoff.ignoreAdapter(t, 3, t - 19 * min, 0))
        assertFalse(TriggerBackoff.ignoreAdapter(t, 3, t - 20 * min, 0))
    }

    @Test fun `ignored for a while after Stop is tapped`() {
        assertTrue(TriggerBackoff.ignoreAdapter(t, 0, 0, t - min))
        assertFalse(TriggerBackoff.ignoreAdapter(t, 0, 0, t - 15 * min))
    }

    @Test fun `a clock set backwards never blocks`() {
        assertFalse(TriggerBackoff.ignoreAdapter(t, 5, t + min, 0))
        assertFalse(TriggerBackoff.ignoreAdapter(t, 0, 0, t + min))
    }
}
