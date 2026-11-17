package io.github.vitorhpsousa.dpfsentinel.core

// When to send the "connected and logging" Telegram message: once per connection to the adapter,
// as soon as the car has given a soot reading (so the message carries real numbers), or after a
// short wait if the car answers but soot never comes. A link that drops and comes straight back
// does not send another (see MIN_GAP_MS).
class StartNotice(private val waitForSootMs: Long = WAIT_FOR_SOOT_MS) {
    private var firstDataMs = -1L
    var done = false
        private set

    /** A new connection to the adapter: the next data may send a message again. */
    fun reset() {
        firstDataMs = -1L
        done = false
    }

    /**
     * Call for every row. True exactly once per connection: when the message should be built from
     * this row. [nowMs] is session time, [hasData] = the car answered anything, [hasSoot] = soot was read.
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
        const val MIN_GAP_MS = 2 * 60_000L

        /** Wall-clock check against the last message sent (0 = never). A clock set backwards allows it. */
        fun allowed(nowWallMs: Long, lastSentWallMs: Long, minGapMs: Long = MIN_GAP_MS): Boolean =
            lastSentWallMs <= 0L || nowWallMs < lastSentWallMs || nowWallMs - lastSentWallMs >= minGapMs
    }
}
