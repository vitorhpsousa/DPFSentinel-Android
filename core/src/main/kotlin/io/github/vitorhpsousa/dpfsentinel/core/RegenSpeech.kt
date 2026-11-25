package io.github.vitorhpsousa.dpfsentinel.core

import kotlin.math.roundToInt

// What the phone says out loud during a regeneration, through the car's speakers: started, halfway,
// nearly done, finished. The numbers come from this car's own logs (14 completed regens up to
// 2026-11): each took 14 to 16 minutes and soot fell steadily from about 18 g to about 4.7 g, where
// the car ends the burn. So "halfway" is 11 g and "nearly done" is 7 g, each said once per regen,
// and only if the regen started above it.
class RegenSpeech {
    private var open = false
    private var startSoot = Double.NaN
    private val said = HashSet<Double>()

    /** A regen started. Returns what to say. */
    fun started(soot: Double): String {
        open = true
        startSoot = soot
        said.clear()
        return STARTED
    }

    /** A regen ended ([finished] = the car completed it). Returns what to say. */
    fun ended(finished: Boolean): String {
        open = false
        return if (finished) FINISHED else CUT_SHORT
    }

    /** The regen's data stopped and nobody saw how it ended: say nothing, just forget it. */
    fun lost() { open = false }

    /** Call for every row while the regen is running (not on hold). Returns a milestone to say, or null. */
    fun update(soot: Double): String? {
        if (!open || soot.isNaN()) return null
        if (startSoot.isNaN()) { startSoot = soot; return null }   // soot was not read yet at the start
        var hit: Double? = null
        for (t in MILESTONES_G) {
            if (t !in said && soot <= t) {
                said.add(t)
                if (startSoot >= t + MARGIN_G) hit = t   // the lowest one passed wins when soot jumps
            }
        }
        val t = hit ?: return null
        val grams = soot.roundToInt()
        val left = minutesLeft(soot)
        return if (t == HALFWAY_G) "Filter cleaning halfway. $grams grams left, about $left minutes to go. Keep driving if you can."
        else "Filter cleaning nearly done. About $left ${if (left == 1) "minute" else "minutes"} to go."
    }

    companion object {
        const val HALFWAY_G = 11.0
        const val NEARLY_G = 7.0
        const val MARGIN_G = 1.0
        /** Where this car ends the burn, and how fast soot falls during it. */
        const val END_G = 4.7
        const val BURN_G_PER_MIN = 0.89
        val MILESTONES_G = listOf(HALFWAY_G, NEARLY_G)

        const val STARTED = "Filter cleaning started. It takes about 15 minutes. Keep driving if you can."
        const val FINISHED = "Filter cleaning finished."
        const val CUT_SHORT = "Filter cleaning was cut short. It will try again on the next drive."
        const val TEST = "D P F Sentinel voice test. This is how filter cleaning alerts sound."

        fun minutesLeft(soot: Double): Int = ((soot - END_G) / BURN_G_PER_MIN).roundToInt().coerceAtLeast(1)
    }
}
