package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdapterChoiceTest {
    private val saved = "AA:BB:CC:00:00:01"
    private val linked = "AA:BB:CC:00:00:02"

    @Test fun `nothing saved, any adapter may be found`() {
        assertEquals(AdapterChoice.Plan("", ""), AdapterChoice.plan("", "", 0))
        assertEquals(AdapterChoice.Plan("", ""), AdapterChoice.plan("", "", 2))
    }

    @Test fun `nothing saved but one linked, only the linked one`() {
        assertEquals(AdapterChoice.Plan("", linked), AdapterChoice.plan("", linked, 0))
    }

    @Test fun `saved adapter is connected by address`() {
        assertEquals(AdapterChoice.Plan(saved, ""), AdapterChoice.plan(saved, "", 0))
        assertEquals(AdapterChoice.Plan(saved, ""), AdapterChoice.plan(saved, "", 1))
        assertEquals(AdapterChoice.Plan(saved, ""), AdapterChoice.plan(saved, linked, 3))
    }

    @Test fun `every third attempt scans, for the saved adapter only`() {
        assertEquals(AdapterChoice.Plan("", saved), AdapterChoice.plan(saved, "", 2))
        assertEquals(AdapterChoice.Plan("", saved), AdapterChoice.plan(saved, "", 5))
    }

    @Test fun `a linked adapter is the one scanned for`() {
        assertEquals(AdapterChoice.Plan("", linked), AdapterChoice.plan(saved, linked, 2))
    }

    @Test fun `with an adapter saved, no attempt may pick a stranger`() {
        for (fails in 0..30) {
            val p = AdapterChoice.plan(saved, "", fails)
            assertTrue(p.connectTo == saved || p.scanFor == saved, "attempt $fails could take any adapter")
        }
    }
}
