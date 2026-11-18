package com.spacegoose.banditbox.sync

import com.spacegoose.banditbox.layout.DefaultLayout
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.LayoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun editSavedAfterASyncKeepsBoth() {
        val store = LayoutStore(tmp.newFolder())
        val loaded = DefaultLayout.create(emptyList())
        store.save(loaded)
        val copy = DiskCopy().apply { loaded(loaded) }

        // Sync renames the profile on disk; meanwhile the user adds a row to the copy on screen.
        store.saveQuietly(LayoutEdits.renameProfile(loaded, "Living room"))
        val edited = LayoutEdits.addRow(loaded, "Games").first
        copy.save(store, edited)

        val saved = store.load(loaded.profileId)!!
        assertEquals("Living room", saved.profile.name)
        assertEquals(edited.rows.size, saved.rows.size)
    }

    @Test
    fun reloadReplaysUnsavedEdits() {
        val store = LayoutStore(tmp.newFolder())
        val loaded = DefaultLayout.create(emptyList())
        store.save(loaded)
        val copy = DiskCopy().apply { loaded(loaded) }

        store.saveQuietly(LayoutEdits.renameProfile(loaded, "Living room"))
        val shown = LayoutEdits.addRow(loaded, "Games").first
        val next = copy.reload(store, shown)!!
        assertEquals("Living room", next.profile.name)
        assertEquals(shown.rows.size, next.rows.size)

        // Saving what is now shown writes it as is.
        copy.save(store, next)
        assertEquals(next, store.load(loaded.profileId))
    }

    @Test
    fun reloadOfDeletedProfileIsNull() {
        val store = LayoutStore(tmp.newFolder())
        val loaded = DefaultLayout.create(emptyList())
        store.save(loaded)
        val copy = DiskCopy().apply { loaded(loaded) }
        store.deleteQuietly(loaded.profileId)
        assertNull(copy.reload(store, loaded))
    }
}
