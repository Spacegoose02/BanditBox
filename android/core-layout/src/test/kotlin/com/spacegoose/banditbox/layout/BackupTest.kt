package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val schemaDir = File(requireNotNull(System.getProperty("banditbox.schemaDir")))

    private fun doc(id: String, name: String) = DefaultLayout.create(listOf("com.plexapp.android"), profileId = id, name = name)

    @Test
    fun roundTripsEveryProfile() {
        val store = LayoutStore(tmp.newFolder("a"))
        store.save(doc("p_default", "Living room"))
        store.save(LayoutEdits.setRemap(doc("p_kids", "Kids"), "PROG_RED", Press.LONG, Action.AllApps))
        val text = Backup.encode(Backup.create(store, "2026-11-18T00:30:00Z", "0.1.0"))
        val back = Backup.decode(text)
        assertEquals(listOf("p_default", "p_kids"), back.layouts.map { it.profileId })
        assertEquals(store.load("p_kids"), back.layouts[1])
    }

    @Test
    fun restoreReplacesAllProfiles() {
        val store = LayoutStore(tmp.newFolder("b"))
        store.save(doc("p_default", "Old"))
        store.save(doc("p_old", "Gone after restore"))
        val backup = BackupFile(createdAt = "2026-11-18T00:30:00Z", layouts = listOf(doc("p_default", "New"), doc("p_3", "Three")))
        Backup.restore(store, backup)
        assertEquals(listOf("p_3", "p_default"), store.profileIds())
        assertEquals("New", store.load("p_default")?.profile?.name)
    }

    @Test
    fun readsTheSchemaExample() {
        val backup = Backup.decode(File(schemaDir, "examples/backup/two-profiles.json").readText())
        assertEquals(listOf("p_default", "p_kids"), backup.layouts.map { it.profileId })
    }

    @Test
    fun rejectsTheSchemaInvalidExamples() {
        val files = File(schemaDir, "examples/backup/invalid").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertTrue(files.isNotEmpty())
        for (file in files) assertRejected(file.readText(), file.name)
    }

    @Test
    fun rejectsDuplicateProfilesAndOtherFiles() {
        val twice = BackupFile(createdAt = "2026-11-18T00:30:00Z", layouts = listOf(doc("p_a", "A"), doc("p_a", "A again")))
        assertRejected(Backup.encode(twice), "duplicate ids")
        assertRejected(LayoutJson.encode(doc("p_a", "A")), "a bare layout document")
        assertRejected("{ not json", "broken JSON")
        val badId = BackupFile(createdAt = "x", layouts = listOf(doc("../evil", "A")))
        assertRejected(Backup.encode(badId), "a path in the id")
    }

    private fun assertRejected(text: String, what: String) {
        try {
            Backup.decode(text)
            fail("Expected $what to be rejected")
        } catch (_: InvalidBackupException) {
            // expected
        }
    }
}
