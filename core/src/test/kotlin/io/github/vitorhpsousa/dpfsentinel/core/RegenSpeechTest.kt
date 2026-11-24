package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegenSpeechTest {
    @Test fun `a normal regen says start, halfway, nearly done, finished, once each`() {
        val s = RegenSpeech()
        val out = ArrayList<String>()
        out += s.started(18.0)
        var soot = 18.0
        while (soot > 4.7) { s.update(soot)?.let { out += it }; soot -= 0.015 }   // about 1 Hz at 0.89 g per min
        out += s.ended(finished = true)
        assertEquals(4, out.size, out.toString())
        assertEquals(RegenSpeech.STARTED, out[0])
        assertEquals("Filter cleaning halfway. 11 grams left, about 7 minutes to go. Keep driving if you can.", out[1])
        assertEquals("Filter cleaning nearly done. About 3 minutes to go.", out[2])
        assertEquals(RegenSpeech.FINISHED, out[3])
    }

    @Test fun `nothing before a regen or after it`() {
        val s = RegenSpeech()
        assertNull(s.update(9.0))
        s.started(18.0)
        s.ended(finished = false)
        assertNull(s.update(6.0))
    }

    @Test fun `a regen that starts below a milestone does not announce it`() {
        val s = RegenSpeech()
        s.started(10.5)   // a second attempt in the same cycle
        assertNull(s.update(10.4))
        assertTrue(s.update(6.9)!!.startsWith("Filter cleaning nearly done"))
        val low = RegenSpeech()
        low.started(7.5)
        assertNull(low.update(6.0))
    }

    @Test fun `unknown soot is ignored and a late first reading becomes the start`() {
        val s = RegenSpeech()
        s.started(Double.NaN)
        assertNull(s.update(Double.NaN))
        assertNull(s.update(17.5))
        assertTrue(s.update(10.9)!!.startsWith("Filter cleaning halfway"))
    }

    @Test fun `a jump past both milestones says only the later one`() {
        val s = RegenSpeech()
        s.started(18.0)
        assertTrue(s.update(6.5)!!.startsWith("Filter cleaning nearly done"))
        assertNull(s.update(6.0))
    }

    @Test fun `cut short wording and minutes never below one`() {
        assertEquals(RegenSpeech.CUT_SHORT, RegenSpeech().ended(finished = false))
        assertEquals(1, RegenSpeech.minutesLeft(4.8))
        assertEquals(15, RegenSpeech.minutesLeft(18.0))
    }
}
