package io.github.vitorhpsousa.dpfsentinel.core

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

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
    }
}
