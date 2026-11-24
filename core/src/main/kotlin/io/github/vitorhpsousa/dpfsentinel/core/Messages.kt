package io.github.vitorhpsousa.dpfsentinel.core

import java.util.Locale

// Telegram texts, worded like the firmware's (src/report/telegram_report.cpp).
object Messages {
    const val KOFI_URL = "https://ko-fi.com/vitoi"

    // Regen messages, first line: what the driver should do, in plain words. The numbers follow
    // on a second line built by RegenWatch (see RegenEvent.headline / detail).
    const val REGEN_START = "The engine is cleaning its exhaust filter. If you can, keep driving for about 10 more minutes. " +
        "If you've arrived, it's fine to switch off."
    const val REGEN_END = "Filter cleaning finished. Nothing to do."
    const val REGEN_INTERRUPTED = "Filter cleaning was cut short. It will try again on the next drive."
    /** Logging stopped (Stop, phone left the car) with a regen still open: nobody saw how it ended. */
    const val REGEN_UNSEEN_END = "Logging stopped while the filter was still cleaning, so it is not known whether it finished."
    const val REGEN_PAUSED = "Filter cleaning is on hold."
    const val REGEN_RESUMED = "Filter cleaning carried on."
    const val REGEN_TICK = "Filter cleaning in progress."

    fun regenHeadline(type: RegenEvent.Type): String = when (type) {
        RegenEvent.Type.START -> REGEN_START
        RegenEvent.Type.END -> REGEN_END
        RegenEvent.Type.INTERRUPTED -> REGEN_INTERRUPTED
        RegenEvent.Type.PAUSED -> REGEN_PAUSED
        RegenEvent.Type.RESUMED -> REGEN_RESUMED
        RegenEvent.Type.TICK -> REGEN_TICK
        RegenEvent.Type.NONE -> ""
    }

    private fun n(v: Double, d: Int) = Units.num(v, d)

    fun latestLine(row: Row?, u: Units = Units.UK): String =
        if (row == null || row.soot.isNaN()) "No live engine reading right now (car off or adapter out of range)."
        else "Latest: soot ${n(row.soot, 1)} g, ${u.distance(row.sinceRegen, 1)} since regen, DPF ${n(row.diffPressure, 0)} hPa, " +
            "EGT ${u.temp(row.egt)}, battery ${n(row.batteryV, 1)} V"

    fun status(time: String, row: Row?, estimate: String, u: Units = Units.UK): String {
        if (row == null || row.soot.isNaN()) return "Status $time: car off or adapter out of range"
        return "Status $time: rpm ${n(row.rpm, 0)}, speed ${u.speed(row.speed)}\n" +
            "soot ${n(row.soot, 1)} g, ${u.distance(row.sinceRegen, 1)} since regen\n" +
            "DPF ${n(row.diffPressure, 0)} hPa, EGT ${u.temp(row.egt)}, battery ${n(row.batteryV, 1)} V\n" +
            "next regen: $estimate"
    }

    /**
     * Sent each time the app connects to the adapter and the car answers: proof that it is working,
     * with the first readings. [how] says what started the session ("by itself", "when the app was
     * opened", "by hand"); [reconnect] = not the first connection of this session.
     */
    fun loggingStarted(time: String, session: Int, how: String, reconnect: Boolean, row: Row?, estimate: String, u: Units = Units.UK): String {
        val head = if (reconnect) "DPF Sentinel reconnected to the car, $time (session $session)"
        else "DPF Sentinel is logging, $time (session $session, started $how)"
        if (row == null || row.soot.isNaN()) return "$head\nConnected to the car; no filter reading yet."
        return "$head\n" +
            "soot ${n(row.soot, 1)} g, ${u.distance(row.sinceRegen, 1)} since regen\n" +
            "DPF ${n(row.diffPressure, 0)} hPa, EGT ${u.temp(row.egt)}, battery ${n(row.batteryV, 1)} V\n" +
            "next regen: $estimate"
    }

    fun dailyReport(date: String, fileCount: Int, bytes: Long, latestLine: String): String =
        "DPF Sentinel (phone) report, $date\n$fileCount file(s), ${fmtBytes(bytes)} to send\n$latestLine\n\nFound this useful? $KOFI_URL"

    fun fmtBytes(b: Long): String = if (b > 1_048_576) String.format(Locale.US, "%.1f MB", b / 1_048_576.0) else "${b / 1024} KB"

    /** Next-regen text as the screen shows it: "not soon", "~42 mi", "due now", or "--". */
    fun estimate(h: HealthState, u: Units = Units.UK): String = when {
        h.estMilesToRegen.isNaN() -> "--"
        h.estNotSoon -> "not soon"
        h.estMilesToRegen < 1.0 -> "due now"
        else -> "~${u.distance(h.estMilesToRegen)}" + if (h.estFromLiveRate) "" else " (avg)"
    }
}
