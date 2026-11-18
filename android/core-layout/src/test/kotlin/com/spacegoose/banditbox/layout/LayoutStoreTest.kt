package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LayoutStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun savesAndLoads() {
        val store = LayoutStore(tmp.root)
        val doc = DefaultLayout.create(listOf("com.plexapp.android"))
        store.save(doc)
        assertEquals(doc, store.load(DefaultLayout.PROFILE_ID))
        assertEquals(listOf(DefaultLayout.PROFILE_ID), store.profileIds())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun loadOrCreateOnlyCreatesOnce() {
        val store = LayoutStore(tmp.root)
        var calls = 0
        repeat(2) { store.loadOrCreate("p_a") { calls++; DefaultLayout.create(emptyList(), profileId = "p_a") } }
        assertEquals(1, calls)
    }

    @Test
    fun movesUnreadableFileAsideInsteadOfLosingIt() {
        File(tmp.root, "p_bad.json").writeText("{ not json")
        val store = LayoutStore(tmp.root)
        assertNull(store.load("p_bad"))
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("p_bad.json.unreadable-") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathLikeProfileIds() {
        LayoutStore(tmp.root).load("../escape")
    }

    @Test
    fun listenerHearsSavesAndDeletesButNotQuietOnes() {
        val store = LayoutStore(tmp.root)
        val heard = mutableListOf<String>()
        store.listener = object : LayoutStore.Listener {
            override fun saved(before: LayoutDocument?, after: LayoutDocument) {
                heard += "saved ${after.profileId} over ${before?.profile?.name}"
            }

            override fun deleted(profileId: String) {
                heard += "deleted $profileId"
            }
        }
        val doc = DefaultLayout.create(emptyList())
        store.save(doc)
        store.save(doc.copy(profile = doc.profile.copy(name = "Den")))
        store.saveQuietly(doc)
        store.delete(DefaultLayout.PROFILE_ID)
        store.delete(DefaultLayout.PROFILE_ID) // already gone: nothing to report
        store.saveQuietly(doc)
        store.deleteQuietly(DefaultLayout.PROFILE_ID)
        assertEquals(listOf("saved p_default over null", "saved p_default over Home", "deleted p_default"), heard)
    }
}
