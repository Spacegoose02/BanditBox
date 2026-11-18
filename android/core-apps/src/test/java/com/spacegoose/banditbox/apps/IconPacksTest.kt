package com.spacegoose.banditbox.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPacksTest {
    @Test
    fun parsesAppfilterComponents() {
        assertEquals("com.plexapp.android" to "com.plexapp.android.Main", IconPacks.parseComponent("ComponentInfo{com.plexapp.android/com.plexapp.android.Main}"))
        assertEquals("org.jellyfin.androidtv" to "org.jellyfin.androidtv.ui.Start", IconPacks.parseComponent("ComponentInfo{org.jellyfin.androidtv/.ui.Start}"))
        assertNull(IconPacks.parseComponent("ComponentInfo{broken}"))
        assertNull(IconPacks.parseComponent(":CALENDAR"))
    }
}
