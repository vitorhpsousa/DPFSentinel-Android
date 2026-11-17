package io.github.vitorhpsousa.dpfsentinel.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// "Look for the car every day at HH:MM": the times, without Android. The app sets an alarm for
// next() and, when it fires, tries to reach the adapter for whatever remaining() says is left.
object DailyWindowLogic {
    const val WINDOW_MS = 15 * 60_000L

    private fun at(day: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime =
        day.toLocalDate().atTime(hour, minute).atZone(day.zone)

    /** Wall-clock ms of the next start of the window strictly after [nowMs]. */
    fun next(nowMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = at(now, hour, minute)
        val t = if (today.toInstant().toEpochMilli() > nowMs) today else at(now.plusDays(1), hour, minute)
        return t.toInstant().toEpochMilli()
    }

    /** How much of today's window is left at [nowMs]; 0 outside it (an alarm delivered too late). */
    fun remaining(nowMs: Long, hour: Int, minute: Int, zone: ZoneId, windowMs: Long = WINDOW_MS): Long {
        val start = at(Instant.ofEpochMilli(nowMs).atZone(zone), hour, minute).toInstant().toEpochMilli()
        val left = start + windowMs - nowMs
        return if (nowMs >= start && left > 0) left else 0L
    }

    /** "8:20" / "08:20" to (hour, minute), or null. */
    fun parse(text: String): Pair<Int, Int>? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) Pair(h, min) else null
    }
}
