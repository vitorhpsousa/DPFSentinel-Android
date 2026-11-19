package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthLatchTest {
    @Test fun `a standing oil reminder is not repeated by a new session`() {
        val state = HealthState()
        state.regensSinceOil = 15
        val rebuilt = HealthTracker(HealthState.decode(state.encode()))
        rebuilt.update(Row(), RegenEvent.NONE, 1000, 1_800_000_000.0)
        assertEquals(emptyList(), rebuilt.takeAlerts())
    }

    @Test fun `soot zone carried over from the last drive does not alert again, a rise does`() {
        val z = SootZone(1)
        assertEquals(null, z.update(15.0))
        assertEquals(2, z.update(17.2))
        assertEquals(null, SootZone(2).update(18.0))
        assertEquals(1, SootZone(0).update(14.5))
    }

    @Test fun `a new adapter link forgets what was read over the old one`() {
        var reply = "7E80441 0C 0C 80".replace(" ", "")
        val elm = object : ElmTransport {
            override fun send(cmd: String, timeoutMs: Long): String = when {
                cmd.startsWith("AT") -> "OK"
                cmd.startsWith("010C") -> reply
                else -> "NO DATA"
            }
        }
        val p = ObdPoller(elm)
        assertTrue(p.initAdapter(0))
        repeat(40) { p.step(it * 10L, false) }
        assertEquals(800.0, p.assembleRow(500).rpm)
        reply = "NO DATA"
        assertTrue(p.initAdapter(1000))
        assertTrue(p.assembleRow(1100).rpm.isNaN(), "rpm from before the reconnect must not be reused")
    }
}
