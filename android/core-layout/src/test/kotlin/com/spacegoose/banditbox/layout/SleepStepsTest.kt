package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepStepsTest {
    @Test
    fun stepsUpThenOff() {
        assertEquals(15, SleepSteps.next(null))
        assertEquals(30, SleepSteps.next(15))
        assertEquals(15, SleepSteps.next(14))
        assertEquals(120, SleepSteps.next(90))
        assertNull(SleepSteps.next(120))
    }

    @Test
    fun minutesLeftRoundsUp() {
        assertEquals(15, SleepSteps.minutesLeft(15 * 60_000L, 0))
        assertEquals(1, SleepSteps.minutesLeft(1_000L, 0))
        assertNull(SleepSteps.minutesLeft(1_000L, 1_000L))
        assertNull(SleepSteps.minutesLeft(null, 0))
    }
}
