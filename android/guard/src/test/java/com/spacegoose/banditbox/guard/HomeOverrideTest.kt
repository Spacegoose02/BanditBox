package com.spacegoose.banditbox.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeOverrideTest {
    private val googleTv = "com.google.android.apps.tv.launcherx"
    private val homeActivity = "com.google.android.apps.tv.launcherx.home.HomeActivity"
    private fun override() = HomeOverride(setOf(homeActivity))

    @Test
    fun redirectsTheStockHomeScreen() {
        assertTrue(override().shouldRedirect(googleTv, homeActivity, nowMs = 10_000))
    }

    @Test
    fun leavesOtherStockLauncherScreensAlone() {
        assertFalse(override().shouldRedirect(googleTv, "com.google.android.apps.tv.launcherx.settings.Panel", 10_000))
    }

    @Test
    fun leavesOtherAppsAndLaunchersAlone() {
        val o = HomeOverride(setOf(homeActivity, "com.spocky.projengmenu.ui.home.MainActivity"))
        assertFalse(o.shouldRedirect("com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity", 10_000))
        assertFalse(o.shouldRedirect("com.plexapp.android", homeActivity, 10_000))
    }

    @Test
    fun redirectsOncePerBounce() {
        val o = override()
        assertTrue(o.shouldRedirect(googleTv, homeActivity, 10_000))
        assertFalse(o.shouldRedirect(googleTv, homeActivity, 10_300))
        assertTrue(o.shouldRedirect(googleTv, homeActivity, 10_000 + HomeOverride.DEBOUNCE_MS))
    }
}
