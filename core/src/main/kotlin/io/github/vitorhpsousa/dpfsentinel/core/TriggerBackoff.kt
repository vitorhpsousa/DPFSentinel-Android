package io.github.vitorhpsousa.dpfsentinel.core

// The OBD port stays powered with the car parked, so a linked adapter keeps "appearing" to Android
// every time the app lets go of it: start, no answer from the sleeping car, stop, appear, start...
// all night. After a session that never heard from the car, the adapter trigger is ignored for a
// while, longer each time; after a Stop tapped by hand, for a fixed time. Car Bluetooth, Android
// Auto, the daily time and opening the app are never held back.
object TriggerBackoff {
    const val FIRST_MS = 5 * 60_000L
    const val MAX_MS = 2 * 60 * 60_000L
    const val AFTER_STOP_MS = 15 * 60_000L

    /** How long to ignore the adapter after [emptySessions] sessions in a row without car data. */
    fun waitMs(emptySessions: Int): Long {
        if (emptySessions <= 0) return 0L
        var w = FIRST_MS
        repeat(minOf(emptySessions - 1, 10)) { w = minOf(w * 2, MAX_MS) }
        return w
    }

    /**
     * Whether an "adapter in range" trigger at [nowMs] should be ignored. Times are wall-clock ms,
     * 0 = never. A clock set backwards never blocks.
     */
    fun ignoreAdapter(nowMs: Long, emptySessions: Int, lastEmptyEndMs: Long, lastManualStopMs: Long): Boolean {
        if (lastManualStopMs > 0L && nowMs >= lastManualStopMs && nowMs - lastManualStopMs < AFTER_STOP_MS) return true
        if (emptySessions > 0 && lastEmptyEndMs > 0L && nowMs >= lastEmptyEndMs && nowMs - lastEmptyEndMs < waitMs(emptySessions)) return true
        return false
    }
}
