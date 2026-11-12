package io.github.vitorhpsousa.dpfsentinel.core

import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The shared regen test vectors (src/test/resources/regen-vectors, format in core/SPEC-regen.md):
// real episodes with everything identifying removed. The firmware's port runs the same files.
class RegenVectorTest {
    private class Line(val finish: Boolean, val tMs: Long, val v: DoubleArray, val expected: String)

    private val dir = File(javaClass.getResource("/regen-vectors")!!.toURI())

    private fun read(f: File): List<Line> = f.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
        val p = line.split(',')
        fun num(i: Int) = p.getOrNull(i)?.toDoubleOrNull() ?: Double.NaN
        Line(p[0] == "finish", p[0].toLongOrNull() ?: 0L, DoubleArray(7) { num(it + 1) }, p.getOrNull(8).orEmpty().trim())
    }

    /** Runs one vector; returns "row TYPE" for every event (TICKs left out), rows counted from 0, the finish row as -1. */
    private fun run(lines: List<Line>, health: HealthTracker? = null): List<String> {
        val w = RegenWatch(ZoneOffset.UTC)
        val out = ArrayList<String>()
        var t = 0L
        lines.forEachIndexed { i, l ->
            val ev = if (l.finish) w.finish(t, Double.NaN) else {
                t = l.tMs
                // columns: rpm, speed_kmh, regen_flag, soot_g, dist_since_regen_mi, egt_c, dp_hpa
                w.update(l.tMs, Double.NaN, l.v[2], l.v[5], l.v[3], l.v[4], l.v[6], l.v[1], l.v[0])
            }
            if (health != null) {
                val row = Row()
                row.values[Pids.col("engine_rpm")] = l.v[0]
                row.values[Pids.col("dpf_soot_level_g")] = l.v[3]
                row.values[Pids.col("dpf_dist_since_regen_mi")] = l.v[4]
                health.update(row, ev, t, Double.NaN)
            }
            if (ev.type != RegenEvent.Type.NONE && ev.type != RegenEvent.Type.TICK) out.add("${if (l.finish) -1 else i} ${ev.type}")
        }
        return out
    }

    private fun expected(lines: List<Line>) =
        lines.mapIndexedNotNull { i, l -> if (l.expected.isEmpty()) null else "${if (l.finish) -1 else i} ${l.expected}" }

    @Test fun everyVectorGivesItsExpectedEvents() {
        val files = dir.listFiles { f -> f.name.endsWith(".csv") }!!.sortedBy { it.name }
        assertTrue(files.size >= 6, "vectors missing from $dir")
        val actual = StringBuilder()
        val results = files.map { f ->
            val lines = read(f)
            assertTrue(lines.last().finish, "${f.name} must end with a finish row")
            val got = run(lines)
            got.forEach { actual.appendLine("${f.name.removeSuffix(".csv")} $it") }
            Triple(f.name, expected(lines), got)
        }
        File("build/reports").mkdirs()
        File("build/reports/regen-vectors-actual.txt").writeText(actual.toString())
        for ((name, want, got) in results) assertEquals(want, got, name)
    }

    private fun types(name: String) = run(read(File(dir, "$name.csv"))).map { it.substringAfter(' ') }

    // What each vector is there to show, said once more in words so a wrong "expected" column cannot hide.
    @Test fun vectorsTellTheirStories() {
        assertEquals(listOf("START", "END"), types("01_clean_completion"))
        assertEquals(listOf("START", "INTERRUPTED"), types("02_keyoff_interrupt"))
        assertEquals(listOf("START", "END"), types("03_standstill_engine_running_completes"))
        assertEquals(listOf("START", "INTERRUPTED", "START", "PAUSED", "RESUMED", "INTERRUPTED"), types("04_reattempt_same_cycle"))
        assertEquals(listOf("START", "PAUSED", "RESUMED", "INTERRUPTED"), types("05_flag_blink_while_driving"))
        assertEquals(listOf("START", "INTERRUPTED"), types("06_log_ends_mid_regen"))
    }

    @Test fun keyOffIsReportedWithinFiveSecondsOfTheFlagDropping() {
        val lines = read(File(dir, "02_keyoff_interrupt.csv"))
        val drop = lines.indexOfLast { it.v[2] == 1.0 } + 1
        val at = lines.indexOfFirst { it.expected == "INTERRUPTED" }
        assertTrue(at >= drop && lines[at].tMs - lines[drop].tMs <= 5000)
    }

    @Test fun reattemptInTheSameCycleCountsAsOneRegen() {
        val h = HealthTracker(zone = ZoneOffset.UTC)
        run(read(File(dir, "04_reattempt_same_cycle.csv")), h)
        assertEquals(1, h.state.regensSinceOil)
        assertEquals(1, h.state.regenHistory.size)
        assertEquals(2, h.state.regenHistory[0].attempts)
        assertTrue(h.state.regenHistory[0].interrupted)
        assertEquals(1, h.state.triggerHist.size)
    }

    @Test fun vectorsCarryNothingIdentifying() {
        for (f in dir.listFiles { f -> f.name.endsWith(".csv") }!!) {
            val lines = f.readLines()
            assertEquals("t_ms,rpm,speed_kmh,regen_flag,soot_g,dist_since_regen_mi,egt_c,dp_hpa,expected", lines[0], f.name)
            assertTrue(lines[1].startsWith("0,"), "${f.name} time must start at 0")
            val firstDist = lines.drop(1).firstNotNullOf { it.split(',').getOrNull(5)?.toDoubleOrNull() }
            assertEquals(2.0, firstDist, 1e-9, "${f.name} distance must be rebased to start at 2 mi")
            assertTrue(lines.all { it.split(',').size == 9 }, "${f.name} must have 9 fields on every line")
        }
    }
}
