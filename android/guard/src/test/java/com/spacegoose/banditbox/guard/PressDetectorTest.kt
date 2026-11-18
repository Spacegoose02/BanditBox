package com.spacegoose.banditbox.guard

import com.spacegoose.banditbox.layout.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressDetectorTest {
    /** A clock the test moves by hand. */
    private class FakeScheduler : PressDetector.Scheduler {
        var now = 0L
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        override fun schedule(delayMs: Long, block: () -> Unit): PressDetector.Cancellable {
            val entry = (now + delayMs) to block
            pending += entry
            return PressDetector.Cancellable { pending.remove(entry) }
        }

        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = pending.filter { it.first <= now }.minByOrNull { it.first } ?: return
                pending.remove(due)
                due.second()
            }
        }
    }

    private val clock = FakeScheduler()
    private val fired = mutableListOf<Pair<String, Press>>()
    private fun detector(rules: Map<String, Set<Press>>) =
        PressDetector({ rules[it].orEmpty() }, clock) { key, press -> fired += key to press }

    private fun PressDetector.tap(key: String, holdMs: Long = 50): Pair<Boolean, Boolean> {
        val down = onKey(key, isDown = true, repeatCount = 0)
        clock.advance(holdMs)
        val up = onKey(key, isDown = false, repeatCount = 0)
        return down to up
    }

    @Test
    fun keysWithoutRulesPassThrough() {
        val d = detector(mapOf("PROG_RED" to setOf(Press.SHORT)))
        assertEquals(false to false, d.tap("DPAD_CENTER"))
        assertTrue(fired.isEmpty())
    }

    @Test
    fun shortOnlyFiresOnReleaseWithoutWaiting() {
        val d = detector(mapOf("PROG_RED" to setOf(Press.SHORT)))
        assertEquals(true to true, d.tap("PROG_RED", holdMs = 2_000))
        assertEquals(listOf("PROG_RED" to Press.SHORT), fired)
    }

    @Test
    fun longFiresWhileHeldAndNotAgainOnRelease() {
        val d = detector(mapOf("HOME" to setOf(Press.LONG)))
        d.onKey("HOME", true, 0)
        clock.advance(PressDetector.LONG_MS)
        assertEquals(listOf("HOME" to Press.LONG), fired)
        assertTrue(d.onKey("HOME", true, 1)) // auto-repeat is swallowed
        assertTrue(d.onKey("HOME", false, 0))
        assertEquals(1, fired.size)
    }

    @Test
    fun quickTapOnALongOnlyKeyIsAShortPress() {
        val d = detector(mapOf("HOME" to setOf(Press.LONG)))
        d.tap("HOME", holdMs = 100)
        clock.advance(1_000)
        assertEquals(listOf("HOME" to Press.SHORT), fired)
    }

    @Test
    fun doubleTapFiresDouble() {
        val d = detector(mapOf("BACK" to setOf(Press.DOUBLE)))
        d.tap("BACK")
        clock.advance(100)
        d.tap("BACK")
        clock.advance(1_000)
        assertEquals(listOf("BACK" to Press.DOUBLE), fired)
    }

    @Test
    fun singleTapWaitsForTheDoubleWindow() {
        val d = detector(mapOf("BACK" to setOf(Press.SHORT, Press.DOUBLE)))
        d.tap("BACK")
        clock.advance(PressDetector.DOUBLE_MS - 1)
        assertTrue(fired.isEmpty())
        clock.advance(1)
        assertEquals(listOf("BACK" to Press.SHORT), fired)
    }

    @Test
    fun anotherButtonSettlesAPendingTapFirst() {
        val d = detector(mapOf("BACK" to setOf(Press.DOUBLE), "PROG_RED" to setOf(Press.SHORT)))
        d.tap("BACK")
        assertFalse(d.onKey("DPAD_DOWN", true, 0))
        assertEquals(listOf("BACK" to Press.SHORT), fired)
        d.tap("PROG_RED")
        assertEquals(listOf("BACK" to Press.SHORT, "PROG_RED" to Press.SHORT), fired)
    }

    @Test
    fun anUpWithoutItsDownPassesThrough() {
        val d = detector(mapOf("PROG_RED" to setOf(Press.SHORT)))
        assertFalse(d.onKey("PROG_RED", false, 0))
        assertTrue(fired.isEmpty())
    }

    @Test
    fun allThreePressesOnOneKey() {
        val d = detector(mapOf("PROG_BLUE" to setOf(Press.SHORT, Press.LONG, Press.DOUBLE)))
        d.tap("PROG_BLUE", holdMs = 700)
        d.tap("PROG_BLUE")
        clock.advance(400)
        d.tap("PROG_BLUE")
        clock.advance(50)
        d.tap("PROG_BLUE")
        assertEquals(
            listOf("PROG_BLUE" to Press.LONG, "PROG_BLUE" to Press.SHORT, "PROG_BLUE" to Press.DOUBLE),
            fired,
        )
    }
}
