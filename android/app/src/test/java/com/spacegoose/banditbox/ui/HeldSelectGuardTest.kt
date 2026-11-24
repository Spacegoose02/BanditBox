package com.spacegoose.banditbox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldSelectGuardTest {
    @Test
    fun `swallows the repeats and release of the press that opened the dialog`() {
        val guard = HeldSelectGuard()
        assertTrue(guard.shouldConsume(isSelectKey = true, isDown = true, repeatCount = 12))
        assertTrue(guard.shouldConsume(isSelectKey = true, isDown = true, repeatCount = 13))
        assertTrue(guard.shouldConsume(isSelectKey = true, isDown = false, repeatCount = 0))
    }

    @Test
    fun `a fresh press after that goes through`() {
        val guard = HeldSelectGuard()
        guard.shouldConsume(isSelectKey = true, isDown = false, repeatCount = 0)
        assertFalse(guard.shouldConsume(isSelectKey = true, isDown = true, repeatCount = 0))
        assertFalse(guard.shouldConsume(isSelectKey = true, isDown = false, repeatCount = 0))
        assertFalse(guard.shouldConsume(isSelectKey = true, isDown = true, repeatCount = 5))
    }

    @Test
    fun `other keys are never swallowed`() {
        val guard = HeldSelectGuard()
        assertFalse(guard.shouldConsume(isSelectKey = false, isDown = true, repeatCount = 3))
        assertFalse(guard.shouldConsume(isSelectKey = false, isDown = false, repeatCount = 0))
    }
}
