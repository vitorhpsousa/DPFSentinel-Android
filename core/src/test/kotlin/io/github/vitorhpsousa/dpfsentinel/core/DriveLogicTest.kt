package io.github.vitorhpsousa.dpfsentinel.core

import io.github.vitorhpsousa.dpfsentinel.core.DriveLogic.LogFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveLogicTest {
    private fun pair(n: Int, csv: Long = 1000, raw: Long = 5000) =
        listOf(LogFile("raw_$n.log", n, raw), LogFile("session_$n.csv", n, csv))

    private fun names(plan: List<Pair<Int, List<LogFile>>>) = plan.map { (n, f) -> n to f.map { it.name } }

    @Test fun `oldest session first, csv before raw`() {
        val plan = DriveLogic.plan(pair(3) + pair(1) + pair(2), lastDoneSession = 0, doneNames = emptySet(), currentSession = -1)
        assertEquals(
            listOf(
                1 to listOf("session_1.csv", "raw_1.log"),
                2 to listOf("session_2.csv", "raw_2.log"),
                3 to listOf("session_3.csv", "raw_3.log"),
            ),
            names(plan),
        )
    }

    @Test fun `sent sessions and the one being written are left out`() {
        val plan = DriveLogic.plan(pair(1) + pair(2) + pair(3) + pair(4), lastDoneSession = 2, doneNames = emptySet(), currentSession = 4)
        assertEquals(listOf(3 to listOf("session_3.csv", "raw_3.log")), names(plan))
    }

    @Test fun `a half-sent session only sends what is missing`() {
        val plan = DriveLogic.plan(pair(5), lastDoneSession = 4, doneNames = setOf("session_5.csv"), currentSession = -1)
        assertEquals(listOf(5 to listOf("raw_5.log")), names(plan))
    }

    @Test fun `empty files are skipped but the session still gets marked`() {
        val plan = DriveLogic.plan(pair(7, csv = 0, raw = 0), lastDoneSession = 0, doneNames = emptySet(), currentSession = -1)
        assertEquals(listOf(7 to emptyList<String>()), names(plan))
    }

    @Test fun `nothing new, nothing to do`() {
        assertTrue(DriveLogic.plan(pair(1) + pair(2), lastDoneSession = 2, doneNames = emptySet(), currentSession = -1).isEmpty())
        assertTrue(DriveLogic.plan(emptyList(), 0, emptySet(), -1).isEmpty())
    }

    @Test fun `a long backlog goes a few sessions at a time`() {
        val files = (1..20).flatMap { pair(it) }
        val plan = DriveLogic.plan(files, 0, emptySet(), -1)
        assertEquals(DriveLogic.MAX_SESSIONS, plan.size)
        assertEquals(1, plan.first().first)
        assertEquals(DriveLogic.MAX_SESSIONS, plan.last().first)
    }

    @Test fun `folder search escapes quotes`() {
        assertEquals(
            "name = 'DPF Sentinel' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
            DriveLogic.folderQuery(),
        )
        assertTrue(DriveLogic.folderQuery("Vitor's logs").startsWith("name = 'Vitor\\'s logs' and"))
    }

    @Test fun `file search is tied to the folder`() {
        assertEquals("name = 'session_3.csv' and 'abc123' in parents and trashed = false", DriveLogic.fileQuery("session_3.csv", "abc123"))
    }

    @Test fun `file types and error texts`() {
        assertEquals("text/csv", DriveLogic.mime("session_3.csv"))
        assertEquals("text/plain", DriveLogic.mime("raw_3.log"))
        assertTrue(DriveLogic.describe(0).contains("no connection"))
        assertTrue(DriveLogic.describe(401).contains("Reconnect"))
        assertTrue(DriveLogic.describe(503).contains("503"))
    }
}
