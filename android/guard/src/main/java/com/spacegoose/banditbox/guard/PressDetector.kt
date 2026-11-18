package com.spacegoose.banditbox.guard

import com.spacegoose.banditbox.layout.Press

/**
 * Turns raw key downs and ups into short, long and double presses. Only keys that have a rule
 * are touched; every other key passes straight through. A key's events are all consumed once
 * it has a rule, so its normal behaviour must be replayed when a press has no rule of its own.
 *
 * Waiting is only added where it is needed: a short press fires on release unless the key also
 * has a double-press rule, in which case it waits [DOUBLE_MS] for a second tap.
 */
class PressDetector(
    /** Which presses have a rule for this key name, for example "PROG_RED". */
    private val pressesFor: (String) -> Set<Press>,
    private val scheduler: Scheduler,
    private val onPress: (key: String, press: Press) -> Unit,
) {
    fun interface Scheduler {
        fun schedule(delayMs: Long, block: () -> Unit): Cancellable
    }

    fun interface Cancellable {
        fun cancel()
    }

    private var key: String? = null
    private var down = false
    private var longFired = false
    private var taps = 0
    private var timer: Cancellable? = null

    /** Returns true when the event is consumed and must not reach the app in front. */
    fun onKey(key: String, isDown: Boolean, repeatCount: Int): Boolean {
        val presses = pressesFor(key)
        if (presses.isEmpty()) {
            if (isDown) finishPending() // another button: settle the pending tap first
            return false
        }
        if (key != this.key) finishPending()

        if (isDown) {
            if (repeatCount > 0 || down) return true
            this.key = key
            down = true
            cancelTimer()
            if (taps == 0 && Press.LONG in presses) {
                timer = scheduler.schedule(LONG_MS) {
                    timer = null
                    longFired = true
                    onPress(key, Press.LONG)
                }
            }
            return true
        }

        // Key up. An up whose down we never saw (pressed before the rule existed) passes through.
        if (!down || this.key != key) return false
        down = false
        cancelTimer()
        if (longFired) {
            reset()
            return true
        }
        taps++
        when {
            taps >= 2 -> {
                reset()
                onPress(key, Press.DOUBLE)
            }
            Press.DOUBLE in presses -> timer = scheduler.schedule(DOUBLE_MS) {
                reset()
                onPress(key, Press.SHORT)
            }
            else -> {
                reset()
                onPress(key, Press.SHORT)
            }
        }
        return true
    }

    /** Drops any half-finished press, for example when the rules change. */
    fun clear() {
        cancelTimer()
        reset()
    }

    private fun finishPending() {
        val pendingKey = key
        val pendingTap = taps == 1 && !down
        cancelTimer()
        reset()
        if (pendingTap && pendingKey != null) onPress(pendingKey, Press.SHORT)
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private fun reset() {
        key = null
        down = false
        longFired = false
        taps = 0
        timer = null
    }

    companion object {
        /** Held this long counts as a long press, close to Android's own long-press timeout. */
        const val LONG_MS = 500L

        /** A second tap within this time counts as a double press. */
        const val DOUBLE_MS = 300L
    }
}
