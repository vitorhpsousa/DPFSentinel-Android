package io.github.vitorhpsousa.dpfsentinel.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyWindowTest {
    private val london = ZoneId.of("Europe/London")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(london).toInstant().toEpochMilli()

    @Test fun `next is today when the time is still ahead`() {
        assertEquals(ms(2026, 11, 17, 8, 20), DailyWindowLogic.next(ms(2026, 11, 17, 7, 0), 8, 20, london))
    }

    @Test fun `next is tomorrow at or after the time`() {
        assertEquals(ms(2026, 11, 18, 8, 20), DailyWindowLogic.next(ms(2026, 11, 17, 8, 20), 8, 20, london))
        assertEquals(ms(2026, 11, 18, 8, 20), DailyWindowLogic.next(ms(2026, 11, 17, 23, 59), 8, 20, london))
    }

    @Test fun `next keeps the clock time across a clock change`() {
        // Clocks go forward on 28 March 2027: still 08:20 local the next morning.
        assertEquals(ms(2027, 3, 28, 8, 20), DailyWindowLogic.next(ms(2027, 3, 27, 9, 0), 8, 20, london))
    }

    @Test fun `remaining inside and outside the window`() {
        assertEquals(15 * 60_000L, DailyWindowLogic.remaining(ms(2026, 11, 17, 8, 20), 8, 20, london))
        assertEquals(10 * 60_000L, DailyWindowLogic.remaining(ms(2026, 11, 17, 8, 25), 8, 20, london))
        assertEquals(0L, DailyWindowLogic.remaining(ms(2026, 11, 17, 8, 35), 8, 20, london))
        assertEquals(0L, DailyWindowLogic.remaining(ms(2026, 11, 17, 8, 19, 59), 8, 20, london))
        assertEquals(0L, DailyWindowLogic.remaining(ms(2026, 11, 17, 12, 0), 8, 20, london))
    }

    @Test fun `parse times`() {
        assertEquals(Pair(8, 20), DailyWindowLogic.parse("8:20"))
        assertEquals(Pair(8, 20), DailyWindowLogic.parse(" 08:20 "))
        assertEquals(Pair(0, 0), DailyWindowLogic.parse("0:00"))
        assertEquals(null, DailyWindowLogic.parse("24:00"))
        assertEquals(null, DailyWindowLogic.parse("8:60"))
        assertEquals(null, DailyWindowLogic.parse("820"))
        assertEquals(null, DailyWindowLogic.parse(""))
    }
}
