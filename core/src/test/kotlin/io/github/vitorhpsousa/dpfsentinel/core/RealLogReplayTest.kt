package io.github.vitorhpsousa.dpfsentinel.core

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Acceptance test over the real car logs. They are private and live outside the repository, so
// this skips when the folder is absent (CI, other machines). -Ddpf.logs=/path overrides the folder.
class RealLogReplayTest {
    private val root = File(System.getProperty("dpf.logs") ?: "/Users/vitor/Claude/OBD/logs")

    @Test fun replayRealLogs() {
        assumeTrue(root.isDirectory, "no real logs at $root")
        val report = ReplayReport.of(Replay.order(Replay.loadUnique(root)))
        val table = report.table("replay of $root")
        println(table)
        File("build/reports").mkdirs()
        File("build/reports/replay-summary.txt").writeText(table)

        assertTrue(report.episodes >= 31, "expected the 31 episodes logged up to 2026-11-07")
        assertEquals(emptyList(), report.unclassified.map { "${it.ep.session}:${it.ep.startLine} ${it.events}" })
        assertEquals(report.completed, report.completedOk)
        assertEquals(0, report.falseFinishes)
        assertEquals(0, report.silent)
        assertEquals(0, report.late)
        assertEquals(0, report.promises)
        assertEquals(0, report.openAndSilent)
        // Retried attempts are one regen: the oil counter matches the cycles, not the starts.
        assertEquals(report.truthCycles, report.regensSinceOil)
        assertEquals(report.completed, report.results.sumOf { r -> r.events.count { it.type == RegenEvent.Type.END } })
    }
}
