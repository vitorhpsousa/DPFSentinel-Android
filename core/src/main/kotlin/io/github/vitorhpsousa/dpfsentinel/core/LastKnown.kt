package io.github.vitorhpsousa.dpfsentinel.core

// The last value the car gave for each column, for the screens to show (marked as old) while not
// connected. Kept per column, not per row: at key-off the engine ECU goes quiet first, so the final
// rows of a drive still "answer" (battery voltage, steering) but have no soot, pressure or
// temperatures in them. Storing the last row would store those blanks.
class LastKnown(start: Row? = null, startAtMs: Long = 0L) {
    private val values = DoubleArray(Pids.COLUMNS.size) { Double.NaN }
    private val sootCol = Pids.col("dpf_soot_level_g")

    /** Wall-clock ms of the newest soot reading; before any soot, of the newest reading of anything. 0 = none. */
    var atMs = 0L
        private set
    private var hasSoot = false

    init {
        if (start != null && start.values.size == values.size) {
            start.values.copyInto(values)
            atMs = startAtMs
            hasSoot = !values[sootCol].isNaN()
        }
    }

    /** Takes over every value [row] has. Returns true if anything was taken. */
    fun update(row: Row, wallMs: Long): Boolean {
        var any = false
        for (i in values.indices) {
            val v = row.values[i]
            if (!v.isNaN()) { values[i] = v; any = true }
        }
        if (!any) return false
        val soot = !row.values[sootCol].isNaN()
        if (soot || !hasSoot) atMs = wallMs
        if (soot) hasSoot = true
        return true
    }

    /** Null until the car has given anything. */
    fun row(): Row? = if (values.all { it.isNaN() }) null else Row(values.copyOf())
}
