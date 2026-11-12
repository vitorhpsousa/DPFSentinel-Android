package io.github.vitorhpsousa.dpfsentinel.core

import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset
import kotlin.math.abs

// Replays logged session CSVs through RegenWatch / IdleBlockageWatch / HealthTracker in the same
// order LoggerService calls them, one call per logged row, and collects what they emit. Columns
// are matched by header name, so the firmware's, the Pi's and the phone's layouts all load.

class ReplayRow(val line: Int, val ms: Long, val wall: Double, val row: Row)

class ReplayEvent(val line: Int, val ms: Long, val event: RegenEvent) {
    val type get() = event.type
}

class ReplaySession(val name: String, val rows: List<ReplayRow>) {
    val firstClock: Double = rows.firstOrNull { it.wall >= 1735689600.0 }?.wall ?: Double.NaN
    val firstOdo: Double = rows.firstOrNull { !it.row.odometer.isNaN() }?.row?.odometer ?: Double.NaN
}

class ReplayResult(
    val session: ReplaySession,
    val events: List<ReplayEvent>,
    /** A regen was still open when the rows ran out, before any end-of-session call. */
    val openAtEnd: Boolean,
    val idleFlaggedRows: Int,
)

object Replay {
    fun parse(name: String, lines: List<String>): ReplaySession {
        if (lines.isEmpty()) return ReplaySession(name, emptyList())
        val head = lines[0].trim().split(',')
        val msCol = head.indexOf("millis")
        val wallCol = head.indexOf("unix_time")
        val map = head.mapIndexed { i, h -> i to Pids.col(h) }.filter { it.second >= 0 }
        val out = ArrayList<ReplayRow>()
        var wall0 = Double.NaN
        for (n in 1 until lines.size) {
            val p = lines[n].split(',')
            if (p.size < 2) continue
            fun num(i: Int) = if (i < 0 || i >= p.size) Double.NaN else p[i].trim().toDoubleOrNull() ?: Double.NaN
            val wall = num(wallCol)
            var ms = num(msCol)
            if (ms.isNaN()) {
                // No millis column (the Pi logger): run the clock off unix_time.
                if (wall.isNaN()) continue
                if (wall0.isNaN()) wall0 = wall
                ms = (wall - wall0) * 1000.0
            }
            val row = Row()
            for ((i, c) in map) row.values[c] = num(i)
            out.add(ReplayRow(n + 1, ms.toLong(), wall, row))
        }
        return ReplaySession(name, out)
    }

    fun load(file: File, name: String = file.name) = parse(name, file.readLines())

    /** Every session_*.csv under root, identical files (by content hash) taken once. */
    fun loadUnique(root: File): List<ReplaySession> {
        val seen = HashSet<String>()
        val md = MessageDigest.getInstance("SHA-256")
        return root.walkTopDown().filter { it.isFile && it.name.startsWith("session_") && it.name.endsWith(".csv") }
            .sortedBy { it.path }
            .filter { seen.add(md.digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }) }
            .map { load(it, it.relativeTo(root).path) }
            .toList()
    }

    /** By clock; a session without one goes just before the first clocked session further on the odometer. */
    fun order(sessions: List<ReplaySession>): List<ReplaySession> {
        val clocked = sessions.filter { !it.firstClock.isNaN() }.sortedBy { it.firstClock }.toMutableList()
        val out = ArrayList(clocked)
        for (s in sessions.filter { it.firstClock.isNaN() }.sortedBy { if (it.firstOdo.isNaN()) Double.MAX_VALUE else it.firstOdo }) {
            val at = if (s.firstOdo.isNaN()) out.size
            else out.indexOfFirst { !it.firstOdo.isNaN() && it.firstOdo > s.firstOdo }.let { if (it < 0) out.size else it }
            out.add(at, s)
        }
        return out
    }

    /** One session, as LoggerService.loop() drives the core. health carries over between sessions. */
    fun run(s: ReplaySession, health: HealthTracker = HealthTracker(zone = ZoneOffset.UTC)): ReplayResult {
        val watch = RegenWatch(ZoneOffset.UTC)
        val idle = IdleBlockageWatch()
        val events = ArrayList<ReplayEvent>()
        var flagged = 0
        for (r in s.rows) {
            val row = r.row
            val ev = watch.update(r.ms, r.wall, row["dpf_regen_active"], row.egt, row.soot, row.sinceRegen, row.diffPressure, row.speed)
            if (idle.update(r.ms, row.rpm, row.speed, row.diffPressure, row.coolant, watch.active || row.regenActive)) flagged++
            if (ev.type != RegenEvent.Type.NONE) events.add(ReplayEvent(r.line, r.ms, ev))
            health.update(row, ev, r.ms, r.wall)
        }
        return ReplayResult(s, events, watch.active, flagged)
    }
}

// What the logs themselves say happened, without any state machine: a run of regen-flag rows is an
// episode; it completed if distance-since-regen fell to ~0 right after it.
class TruthEpisode(
    val session: String,
    val startLine: Int,
    val endLine: Int,          // last row with the flag set
    val startIdx: Int,
    val endIdx: Int,
    val endMs: Long,
    val completed: Boolean,
    val keyOff: Boolean,       // engine at 0 rpm (or silent) right after the flag went
    val endOfFile: Boolean,    // the file stops with the flag still set
    val speedAtDrop: Double,
    val sootStart: Double,
    val sootEnd: Double,
    val cycleOdo: Double,      // odometer at the last completed regen (odometer - distance since regen)
)

object Truth {
    private fun flag(r: ReplayRow) = r.row["dpf_regen_active"]

    fun episodes(s: ReplaySession): List<TruthEpisode> {
        val out = ArrayList<TruthEpisode>()
        val rows = s.rows
        var i = 0
        while (i < rows.size) {
            if (flag(rows[i]) != 1.0) { i++; continue }
            val a = i
            var b = i
            var j = i
            while (j < rows.size && flag(rows[j]) != 0.0) { if (flag(rows[j]) == 1.0) b = j; j++ }
            fun back(from: Int, f: (Row) -> Double) = (from downTo maxOf(0, from - 30)).map { f(rows[it].row) }.firstOrNull { !it.isNaN() } ?: Double.NaN
            fun fwd(from: Int, n: Int, f: (Row) -> Double) =
                (from until minOf(rows.size, from + n)).map { f(rows[it].row) }.firstOrNull { !it.isNaN() } ?: Double.NaN
            val d0 = back(b, Row::sinceRegen)
            val completed = (b until minOf(rows.size, b + 200)).any {
                val d = rows[it].row.sinceRegen
                !d.isNaN() && d < 1.0 && d + 0.5 < d0
            }
            val eof = j >= rows.size
            val rpmAfter = if (eof) Double.NaN else fwd(j, 3, Row::rpm)
            out.add(
                TruthEpisode(
                    s.name, rows[a].line, rows[b].line, a, b, rows[b].ms, completed,
                    keyOff = !eof && (rpmAfter.isNaN() || rpmAfter == 0.0), endOfFile = eof,
                    speedAtDrop = if (eof) back(b, Row::speed) else fwd(j, 3, Row::speed),
                    sootStart = fwd(a, 30, Row::soot).let { if (it.isNaN()) back(a, Row::soot) else it }, sootEnd = back(b, Row::soot),
                    cycleOdo = back(b, Row::odometer) - d0,
                ),
            )
            i = j
        }
        return out
    }

    fun sameCycle(a: TruthEpisode, b: TruthEpisode) = abs(a.cycleOdo - b.cycleOdo) < 1.0
}

/** How one logged episode came out of the state machine. */
class EpisodeOutcome(val ep: TruthEpisode, val ends: Int, val interrupts: Int, val merged: Boolean, val delayS: Long, val events: String) {
    val falseFinish get() = !ep.completed && ends > 0
    val ok get() = if (ep.completed) ends == 1 && interrupts == 0 else ends == 0 && (interrupts == 1 || merged)
    val silent get() = !ep.completed && ends == 0 && interrupts == 0 && !merged
    /** A key-off or end-of-log interruption reported more than [PROMPT_S] after the flag went. */
    val late get() = !ep.completed && interrupts > 0 && delayS > PROMPT_S

    companion object {
        const val PROMPT_S = 15L
    }
}

class ReplayReport(val results: List<ReplayResult>, val outcomes: List<EpisodeOutcome>, val regensSinceOil: Int, val historySize: Int) {
    private fun count(t: RegenEvent.Type) = results.sumOf { r -> r.events.count { it.type == t } }

    val episodes get() = outcomes.size
    val completed get() = outcomes.count { it.ep.completed }
    val falseFinishes get() = outcomes.count { it.falseFinish }
    val completedOk get() = outcomes.count { it.ep.completed && it.ok }
    val notCompletedOk get() = outcomes.count { !it.ep.completed && it.ok }
    val silent get() = outcomes.count { it.silent }
    val late get() = outcomes.count { it.late }
    val openAtEnd get() = results.count { it.openAtEnd }
    val unclassified get() = outcomes.filter { !it.ok }

    /** PAUSED messages that promised the regen would carry on, and it did not (next event was not RESUMED). */
    val wrongPromises: Int get() = results.sumOf { r ->
        val ev = r.events.filter { it.type != RegenEvent.Type.TICK }
        ev.indices.count { i ->
            ev[i].type == RegenEvent.Type.PAUSED && Regex("carr(y|ies) on").containsMatchIn(ev[i].event.text) &&
                ev.getOrNull(i + 1)?.type != RegenEvent.Type.RESUMED
        }
    }
    val promises: Int get() = results.sumOf { r -> r.events.count { it.type == RegenEvent.Type.PAUSED && Regex("carr(y|ies) on").containsMatchIn(it.event.text) } }

    /** Distinct regen cycles in the logs: attempts with no distance reset between them are one. */
    val truthCycles: Int get() {
        var n = 0
        var prev: TruthEpisode? = null
        for (o in outcomes) { if (prev == null || !Truth.sameCycle(prev, o.ep)) n++; prev = o.ep }
        return n
    }

    fun table(title: String): String {
        val sb = StringBuilder()
        sb.appendLine("== $title ==")
        sb.appendLine("sessions replayed (unique files)      ${results.size}")
        sb.appendLine("regen-flag episodes in the logs       $episodes ($completed completed, ${episodes - completed} not)")
        sb.appendLine("regen cycles in the logs              $truthCycles")
        for (t in listOf(RegenEvent.Type.START, RegenEvent.Type.END, RegenEvent.Type.PAUSED, RegenEvent.Type.RESUMED, RegenEvent.Type.INTERRUPTED))
            sb.appendLine("${t.name.padEnd(12)} events                   ${count(t)}")
        sb.appendLine("completed -> exactly one END          $completedOk / $completed")
        sb.appendLine("not completed -> INTERRUPTED/merged   $notCompletedOk / ${episodes - completed}")
        sb.appendLine("false finishes (END, no reset)        $falseFinishes")
        sb.appendLine("not completed and no message at all   $silent")
        sb.appendLine("INTERRUPTED later than ${EpisodeOutcome.PROMPT_S} s          $late")
        sb.appendLine("'will carry on' said / wrong          $promises / $wrongPromises")
        sb.appendLine("sessions ending with a regen open     $openAtEnd")
        sb.appendLine("regensSinceOil after the replay       $regensSinceOil (history rows $historySize)")
        sb.appendLine("idle-blockage rows flagged            ${results.sumOf { it.idleFlaggedRows }}")
        sb.appendLine("-- per episode: file, rows, truth, soot, events (seconds after the flag dropped) --")
        outcomes.forEachIndexed { i, o ->
            val e = o.ep
            val truth = when {
                e.completed -> "completed"
                e.endOfFile -> "log ends mid-regen"
                e.keyOff -> "key-off"
                else -> "flag drop, engine running"
            }
            sb.appendLine(
                "${(i + 1).toString().padStart(2)} ${e.session} ${e.startLine}-${e.endLine} [$truth] soot ${Units.num(e.sootStart, 1)}->${Units.num(e.sootEnd, 1)} " +
                    "=> ${o.events.ifEmpty { "nothing" }}${if (o.ok) "" else "   <-- WRONG"}",
            )
        }
        return sb.toString()
    }

    companion object {
        /** Replays the sessions in order, carrying HealthState across them through encode()/decode() as the app does. */
        fun of(sessions: List<ReplaySession>): ReplayReport {
            var saved: String? = null
            val results = ArrayList<ReplayResult>()
            val outcomes = ArrayList<EpisodeOutcome>()
            var sinceOil = 0
            var history = 0
            for (s in sessions) {
                val health = HealthTracker(HealthState.decode(saved), ZoneOffset.UTC)
                val r = Replay.run(s, health)
                saved = health.state.encode()
                sinceOil = health.state.regensSinceOil
                history = health.state.regenHistory.size
                results.add(r)
                val eps = Truth.episodes(s)
                eps.forEachIndexed { k, e ->
                    val until = eps.getOrNull(k + 1)?.startLine ?: Int.MAX_VALUE
                    val mine = r.events.filter { it.line >= e.startLine && it.line < until && it.type != RegenEvent.Type.TICK }
                    val next = r.events.filter { it.line >= until && it.type != RegenEvent.Type.TICK }
                    val terminal = mine.firstOrNull { it.type == RegenEvent.Type.END || it.type == RegenEvent.Type.INTERRUPTED }
                    val merged = terminal == null && next.firstOrNull()?.type == RegenEvent.Type.RESUMED
                    outcomes.add(
                        EpisodeOutcome(
                            e, mine.count { it.type == RegenEvent.Type.END }, mine.count { it.type == RegenEvent.Type.INTERRUPTED }, merged,
                            if (terminal == null) -1 else (terminal.ms - e.endMs) / 1000,
                            mine.joinToString(" ") { "${it.type}@${if (it.ms >= e.endMs) "+" else ""}${(it.ms - e.endMs) / 1000}s" } +
                                if (merged) " (carried into the next episode)" else "",
                        ),
                    )
                }
            }
            return ReplayReport(results, outcomes, sinceOil, history)
        }
    }
}
