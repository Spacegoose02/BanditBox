package com.spacegoose.banditbox.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEntryTest {
    private fun app(pkg: String, label: String, leanback: Boolean, system: Boolean = false) =
        AppEntry(pkg, "$pkg.Main${if (leanback) "Tv" else ""}", label, leanback, system)

    @Test
    fun prefersTvActivityAndKeepsOneEntryPerPackage() {
        val merged = mergeEntries(
            leanback = listOf(app("com.plexapp.android", "Plex", leanback = true)),
            launcher = listOf(app("com.plexapp.android", "Plex", leanback = false)),
            selfPackage = "com.spacegoose.banditbox",
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().isLeanback)
        assertEquals("com.plexapp.android.MainTv", merged.single().activityName)
    }

    @Test
    fun keepsPhoneOnlyApps() {
        val merged = mergeEntries(
            leanback = emptyList(),
            launcher = listOf(app("org.sideloaded", "Sideloaded", leanback = false)),
            selfPackage = "com.spacegoose.banditbox",
        )
        assertEquals(listOf("org.sideloaded"), merged.map { it.packageName })
        assertFalse(merged.single().isLeanback)
    }

    @Test
    fun hidesItself() {
        val merged = mergeEntries(
            leanback = listOf(app("com.spacegoose.banditbox.debug", "BanditBox", leanback = true)),
            launcher = emptyList(),
            selfPackage = "com.spacegoose.banditbox.debug",
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun hidesOtherBuildsOfItself() {
        val merged = mergeEntries(
            leanback = listOf(
                app("com.spacegoose.banditbox", "BanditBox", leanback = true),
                app("com.spacegoose.banditbox.ramcheck", "BanditBox", leanback = true),
                app("com.spacegoose.bingebandit", "BingeBandit", leanback = true),
            ),
            launcher = emptyList(),
            selfPackage = "com.spacegoose.banditbox.debug",
        )
        assertEquals(listOf("com.spacegoose.bingebandit"), merged.map { it.packageName })
    }

    @Test
    fun sortsByLabelIgnoringCase() {
        val merged = mergeEntries(
            leanback = listOf(
                app("c.youtube", "YouTube", leanback = true),
                app("a.jellyfin", "jellyfin", leanback = true),
                app("b.plex", "Plex", leanback = true),
            ),
            launcher = emptyList(),
            selfPackage = "self",
        )
        assertEquals(listOf("jellyfin", "Plex", "YouTube"), merged.map { it.label })
    }

    @Test
    fun systemAppsCannotBeUninstalled() {
        assertFalse(AppMenuAction.UNINSTALL in menuFor(app("com.android.tv.settings", "Settings", true, system = true)))
        assertTrue(AppMenuAction.UNINSTALL in menuFor(app("b.plex", "Plex", true)))
        assertEquals(AppMenuAction.OPEN, menuFor(app("b.plex", "Plex", true)).first())
    }
}
