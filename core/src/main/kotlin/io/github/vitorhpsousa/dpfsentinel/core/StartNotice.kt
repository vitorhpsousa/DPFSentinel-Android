package io.github.vitorhpsousa.dpfsentinel.core

// When to send the "logging has started" Telegram message: once per logging session, as soon as
// the car has given a soot reading (so the message carries real numbers), or after a short wait
// if the car answers but soot never comes. A restart soon after the last message (a crash and
// retry, Stop then Start) does not send another.
class StartNotice(private val waitForSootMs: Long = WAIT_FOR_SOOT_MS) {
    private var firstDataMs = -1L
    var done = false
        private set

    /**
     * Call for every row. True exactly once: when the message should be built from this row.
     * [nowMs] is session time, [hasData] = the car answered anything, [hasSoot] = soot was read.
     */
    fun due(nowMs: Long, hasData: Boolean, hasSoot: Boolean): Boolean {
        if (done || !hasData) return false
        if (firstDataMs < 0) firstDataMs = nowMs
        if (!hasSoot && nowMs - firstDataMs < waitForSootMs) return false
        done = true
        return true
    }

    companion object {
        const val WAIT_FOR_SOOT_MS = 20_000L
        const val MIN_GAP_MS = 10 * 60_000L

        /** Wall-clock check against the last message sent (0 = never). A clock set backwards allows it. */
        fun allowed(nowWallMs: Long, lastSentWallMs: Long, minGapMs: Long = MIN_GAP_MS): Boolean =
            lastSentWallMs <= 0L || nowWallMs < lastSentWallMs || nowWallMs - lastSentWallMs >= minGapMs
    }
}
