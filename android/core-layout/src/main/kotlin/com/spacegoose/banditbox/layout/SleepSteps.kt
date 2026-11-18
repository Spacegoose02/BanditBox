package com.spacegoose.banditbox.layout

/** The sleep timer's choices, shared by the settings list and the remote button that steps through them. */
object SleepSteps {
    val MINUTES = listOf(15, 30, 45, 60, 90, 120)

    /**
     * Where one press of the sleep button goes: the first step above the minutes left, or off
     * (null) after the last step. With the timer off it starts at the first step.
     */
    fun next(minutesLeft: Int?): Int? =
        if (minutesLeft == null) MINUTES.first() else MINUTES.firstOrNull { it > minutesLeft }

    /** Whole minutes left until [endsAtMs], rounded up, or null when the timer is off or done. */
    fun minutesLeft(endsAtMs: Long?, nowMs: Long): Int? {
        if (endsAtMs == null || endsAtMs <= nowMs) return null
        return ((endsAtMs - nowMs + 59_999) / 60_000).toInt()
    }
}
