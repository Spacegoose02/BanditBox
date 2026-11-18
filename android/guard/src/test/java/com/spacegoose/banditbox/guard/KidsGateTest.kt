package com.spacegoose.banditbox.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsGateTest {
    private val self = "com.spacegoose.banditbox"
    private val approved = setOf("app.wave")

    @Test
    fun approvedAppsStayOpenUntilALock() {
        assertFalse(KidsGate.blocks("app.wave", self, approved, locked = false, isLaunchable = true))
        assertTrue(KidsGate.blocks("app.wave", self, approved, locked = true, isLaunchable = true))
    }

    @Test
    fun otherAppsAndSettingsAreBlocked() {
        assertTrue(KidsGate.blocks("app.youtube", self, approved, locked = false, isLaunchable = true))
        assertTrue(KidsGate.blocks("com.android.tv.settings", self, approved, locked = false, isLaunchable = false))
    }

    @Test
    fun systemWindowsAndBanditBoxAreLeftAlone() {
        assertFalse(KidsGate.blocks("com.google.android.inputmethod.latin", self, approved, locked = true, isLaunchable = false))
        assertFalse(KidsGate.blocks(self, self, approved, locked = true, isLaunchable = true))
    }
}
