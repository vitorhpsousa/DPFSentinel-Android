package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartNoticeTest {
    private fun row(vararg kv: Pair<String, Double>) = Row().also { r -> kv.forEach { (k, v) -> r.values[Pids.col(k)] = v } }

    @Test fun `nothing while the car is silent`() {
        val n = StartNotice()
        for (t in 0L..600_000L step 1000L) assertFalse(n.due(t, hasData = false, hasSoot = false))
        assertFalse(n.done)
    }

    @Test fun `fires once on the first soot reading`() {
        val n = StartNotice()
        assertFalse(n.due(1000, hasData = true, hasSoot = false))
        assertFalse(n.due(2000, hasData = true, hasSoot = false))
        assertTrue(n.due(3000, hasData = true, hasSoot = true))
        for (t in 4000L..120_000L step 1000L) assertFalse(n.due(t, hasData = true, hasSoot = true))
    }

    @Test fun `fires without soot after the wait, counted from the first answer`() {
        val n = StartNotice()
        assertFalse(n.due(50_000, hasData = false, hasSoot = false))
        assertFalse(n.due(60_000, hasData = true, hasSoot = false))
        assertFalse(n.due(79_000, hasData = true, hasSoot = false))
        assertTrue(n.due(80_000, hasData = true, hasSoot = false))
        assertFalse(n.due(81_000, hasData = true, hasSoot = true))
    }

    @Test fun `silence on the same connection does not fire again`() {
        val n = StartNotice()
        assertTrue(n.due(1000, hasData = true, hasSoot = true))
        assertFalse(n.due(2000, hasData = false, hasSoot = false))
        assertFalse(n.due(400_000, hasData = true, hasSoot = true))
    }

    @Test fun `a new connection fires again, once`() {
        val n = StartNotice()
        assertTrue(n.due(1000, hasData = true, hasSoot = true))
        n.reset()
        assertFalse(n.done)
        assertFalse(n.due(300_000, hasData = false, hasSoot = false))
        assertFalse(n.due(301_000, hasData = true, hasSoot = false))
        assertTrue(n.due(302_000, hasData = true, hasSoot = true))
        assertFalse(n.due(303_000, hasData = true, hasSoot = true))
    }

    @Test fun `last reading survives being stored`() {
        val r = row("dpf_soot_level_g" to 12.34, "engine_rpm" to 800.0)
        val back = Row.decode(r.encode())!!
        assertEquals(12.34, back.soot)
        assertEquals(800.0, back.rpm)
        assertTrue(back.egt.isNaN())
        assertEquals(null, Row.decode(""))
        assertEquals(null, Row.decode("1,2,3"))
    }

    @Test fun `gap rule between messages`() {
        val t = 1_800_000_000_000L
        assertTrue(StartNotice.allowed(t, 0))
        assertFalse(StartNotice.allowed(t, t - 60_000))
        assertFalse(StartNotice.allowed(t, t - StartNotice.MIN_GAP_MS + 1))
        assertTrue(StartNotice.allowed(t, t - StartNotice.MIN_GAP_MS))
        assertTrue(StartNotice.allowed(t, t + 5_000), "clock set backwards must not block it for good")
    }

    @Test fun `message with readings`() {
        val r = row("dpf_soot_level_g" to 12.34, "dpf_dist_since_regen_mi" to 45.6, "dpf_diff_pressure_hpa" to 18.0,
            "egt_before_dpf_c" to 210.0, "control_module_v" to 14.2)
        val m = Messages.loggingStarted("08:15", 124, "by itself", reconnect = false, row = r, estimate = "~60 mi")
        val lines = m.lines()
        assertEquals("DPF Sentinel is logging, 08:15 (session 124, started by itself)", lines[0])
        assertEquals(4, lines.size)
        assertTrue(lines[1].startsWith("soot 12.3 g, "))
        assertTrue(lines[2].contains("battery 14.2 V"))
        assertEquals("next regen: ~60 mi", lines[3])
        assertFalse(m.contains("NaN"))
    }

    @Test fun `message without a soot reading has no numbers`() {
        val m = Messages.loggingStarted("08:15", 7, "by hand", reconnect = false, row = row("engine_rpm" to 800.0), estimate = "--")
        assertEquals("DPF Sentinel is logging, 08:15 (session 7, started by hand)\nConnected to the car; no filter reading yet.", m)
        assertEquals(m, Messages.loggingStarted("08:15", 7, "by hand", reconnect = false, row = null, estimate = "--"))
    }

    @Test fun `a later connection in the same session reads as a reconnection`() {
        val m = Messages.loggingStarted("08:40", 7, "by itself", reconnect = true, row = null, estimate = "--")
        assertEquals("DPF Sentinel reconnected to the car, 08:40 (session 7)", m.lines()[0])
        assertEquals("when the app was opened" in Messages.loggingStarted("08:40", 7, "when the app was opened", false, null, "--"), true)
    }

    @Test fun `a shared bot token is named as the problem`() {
        assertEquals(TelegramLogic.SHARED_BOT, TelegramLogic.describeError(409, """{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}"""))
    }
}
