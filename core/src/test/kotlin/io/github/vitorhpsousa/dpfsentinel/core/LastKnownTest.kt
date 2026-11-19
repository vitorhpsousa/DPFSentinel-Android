package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastKnownTest {
    private fun row(vararg kv: Pair<String, Double>) = Row().also { r -> kv.forEach { (k, v) -> r.values[Pids.col(k)] = v } }

    @Test fun `nothing until the car answers`() {
        val k = LastKnown()
        assertNull(k.row())
        assertFalse(k.update(Row(), 1000))
        assertNull(k.row())
        assertEquals(0L, k.atMs)
    }

    @Test fun `key-off rows without soot do not blank the last soot`() {
        val k = LastKnown()
        assertTrue(k.update(row("dpf_soot_level_g" to 12.5, "egt_before_dpf_c" to 240.0, "control_module_v" to 14.1), 1000))
        // Engine ECU gone quiet: only the battery voltage still answers, for a while.
        assertTrue(k.update(row("control_module_v" to 12.3), 9000))
        assertTrue(k.update(row("control_module_v" to 12.2), 15000))
        val r = k.row()!!
        assertEquals(12.5, r.soot)
        assertEquals(240.0, r.egt)
        assertEquals(12.2, r.batteryV)
        assertEquals(1000L, k.atMs, "the time shown is that of the soot reading")
    }

    @Test fun `time follows anything until soot is first seen`() {
        val k = LastKnown()
        k.update(row("control_module_v" to 12.3), 500)
        assertEquals(500L, k.atMs)
        k.update(row("control_module_v" to 12.4), 900)
        assertEquals(900L, k.atMs)
        k.update(row("dpf_soot_level_g" to 9.0), 1500)
        k.update(row("control_module_v" to 12.4), 5000)
        assertEquals(1500L, k.atMs)
        k.update(row("dpf_soot_level_g" to 9.4), 7000)
        assertEquals(7000L, k.atMs)
    }

    @Test fun `carries on from what was stored`() {
        val k = LastKnown(Row.decode(row("dpf_soot_level_g" to 11.0).encode()), 4242)
        assertEquals(4242L, k.atMs)
        k.update(row("engine_rpm" to 800.0), 9999)
        assertEquals(11.0, k.row()!!.soot)
        assertEquals(800.0, k.row()!!.rpm)
        assertEquals(4242L, k.atMs)
    }

    @Test fun `a stored row with no soot still gets a newer time`() {
        // What version 1.5 may have left behind: a last row with only the battery voltage in it.
        val k = LastKnown(row("control_module_v" to 12.1), 100)
        k.update(row("control_module_v" to 12.6), 200)
        assertEquals(200L, k.atMs)
    }
}
