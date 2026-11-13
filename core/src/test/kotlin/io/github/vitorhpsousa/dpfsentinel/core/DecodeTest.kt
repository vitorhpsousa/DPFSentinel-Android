package io.github.vitorhpsousa.dpfsentinel.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The firmware's golden vectors (test/test_decode/test_decode.cpp), captured from the real ix35.
class DecodeTest {
    private fun extract(raw: String) = IsoTp.extract(raw, "7E8")
    private fun dec(name: String, p: IntArray) = Pids.byName(name).decode(p)

    @Test fun diffPressure() {
        val p = extract("7E804611B0012")
        assertEquals(4, p.size)
        assertEquals(9.0, dec("dpf_diff_pressure_hpa", p))
    }

    @Test fun sootAndIntercooler() {
        val p = extract("7E8100C61FF11001E8B\r7E8215500000305FF55")
        assertEquals(12, p.size)
        assertEquals(1.96, dec("dpf_soot_level_g", p), 0.01)
        assertEquals(35.0, dec("intercooler_temp_c", p))
    }

    @Test fun mode21Multiframe() {
        val p = extract(
            "7E8104B6103FFFFFFFF\r7E82100010001000000\r7E82200001000010010\r7E82300000000000100\r" +
                "7E82401000100010001\r7E82500010001000100\r7E82601000100FF0000\r7E8270000000C077B28\r" +
                "7E828000049DC0C07C5\r7E82904000100000000\r7E82A010001FFFFFF55",
        )
        assertEquals(75, p.size)
        assertEquals(0x61, p[0])
        assertEquals(0x03, p[1])
        // Exact values: raw EGT 0x0000, distance 0x0049DC m, odometer 0x0C07C504 m, state byte 1, [19] = 0x10.
        assertEquals(0.0, dec("dpf_regen_active", p))
        assertEquals(0.0, dec("dpf_regen_burning", p))
        assertEquals(99.013, dec("egt_before_dpf_c", p), 1e-4)
        assertEquals(18908 * 0.000621371, dec("dpf_dist_since_regen_mi", p), 1e-5)
        assertEquals(125414.8984375, dec("odometer_mi", p), 0.0)   // float32, as the firmware logs it
    }

    private val reply2103 =
        "7E8104B6103FFFFFFFF\r7E82100010001000000\r7E82200001000010010\r7E82300000000000100\r" +
            "7E82401000100010001\r7E82500010001000100\r7E82601000100FF0000\r7E8270000000C077B28\r" +
            "7E828000049DC0C07C5\r7E82904000100000000\r7E82A010001FFFFFF55"

    @Test fun sootPidRejectsA2103Payload() {
        val p = extract(reply2103)
        assertEquals(75, p.size)
        assertTrue(dec("dpf_soot_level_g", p).isNaN())
        assertTrue(dec("intercooler_temp_c", p).isNaN())
        assertEquals(listOf(0x61, 0xFF), Pids.byName("dpf_soot_level_g").prefix.toList())
        // Right prefix, wrong length: also refused.
        assertTrue(dec("dpf_soot_level_g", IntArray(13).also { it[0] = 0x61; it[1] = 0xFF; it[10] = 45 }).isNaN())
        assertEquals(17.65, dec("dpf_soot_level_g", IntArray(12).also { it[0] = 0x61; it[1] = 0xFF; it[10] = 45 }), 0.01)
    }

    @Test fun responsePendingFramesAreSkipped() {
        // 7F 21 78 = "busy, answer coming", then the answer.
        assertEquals(listOf(0x61, 0x1B, 0x00, 0x12), extract("7E8037F2178\r7E804611B0012").toList())
        assertEquals(12, extract("7E8037F2178\r7E8037F2178\r7E8100C61FF11001E8B\r7E8215500000305FF55").size)
        assertEquals(0, extract("7E8037F2178").size)
        // Any other negative response is still handed back (and fails the caller's prefix check).
        assertEquals(listOf(0x7F, 0x21, 0x11), extract("7E8037F2111").toList())
    }

    @Test fun consecutiveFramesOutOfSequenceAreRejected() {
        assertEquals(12, extract("7E8100C61FF11001E8B\r7E8215500000305FF55").size)
        assertEquals(0, extract("7E8100C61FF11001E8B\r7E8225500000305FF55").size)       // 22 where 21 belongs
        assertEquals(0, extract(reply2103.replace("7E823", "7E824")).size)                // a repeated 24
        assertEquals(0, extract(reply2103.replace("\r7E82300000000000100", "")).size)   // one frame lost
        // 17 consecutive frames: the counter wraps F -> 0 -> 1.
        val long = "7E8107A6103AABBCCDD\r" + (1..17).joinToString("\r") { "7E82%X00000000000000".format(it and 0x0F) }
        assertEquals(96, extract(long).size)   // capped at 96 of the 122 bytes
    }

    @Test fun shortAndForeignFramesRejected() {
        assertEquals(0, extract("NO DATA").size)
        assertEquals(0, IsoTp.extract("7EA804611B0012", "7E8").size)
        assertTrue(dec("dpf_soot_level_g", intArrayOf(0x61, 0, 0)).isNaN())
    }

    @Test fun batteryVoltage() {
        assertEquals(14.0, dec("control_module_v", intArrayOf(0x41, 0x42, 0x36, 0xB0)), 0.01)
    }

    @Test fun negativeSteeringAngle() {
        val p = IntArray(13).also { it[0] = 0x61; it[1] = 0x01; it[11] = 0xFF; it[12] = 0x9C }   // -100 -> -10.0 deg
        assertEquals(-10.0, dec("eps_steering_deg", p), 0.001)
    }

    @Test fun csvHeaderMatchesFirmwareLogs() {
        val example = listOf(File("../../logs/examples/session_example.csv"), File("../logs/examples/session_example.csv"))
            .first { it.exists() }
        assertEquals(example.readLines().first(), CsvFormat.HEADER)
    }

    @Test fun csvRowFormat() {
        val v = DoubleArray(Pids.COLUMNS.size) { Double.NaN }
        v[0] = 888.5
        assertEquals("15320,," + "888.500" + ",".repeat(Pids.COLUMNS.size - 1), CsvFormat.row(15320, Double.NaN, v))
    }
}
