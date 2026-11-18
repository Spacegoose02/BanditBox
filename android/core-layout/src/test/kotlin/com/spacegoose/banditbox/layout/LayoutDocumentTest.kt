package com.spacegoose.banditbox.layout

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayoutDocumentTest {
    private val schemaDir = File(requireNotNull(System.getProperty("banditbox.schemaDir")))
    private fun example(name: String) = File(schemaDir, "examples/$name").readText()

    @Test
    fun decodesKidsExample() {
        val doc = LayoutJson.decode(example("kids.json"))

        assertEquals("p_kids", doc.profileId)
        assertEquals(42L, doc.version)
        assertEquals(120, doc.profile.kids?.dailyLimitMin)
        assertEquals(Bedtime("20:00", "07:00"), doc.profile.kids?.bedtime)
        assertEquals("#39FF14", doc.theme?.accent)

        val favorites = doc.rows[0] as Row.Apps
        assertEquals(TileSize.LARGE, favorites.tile.size)
        assertEquals(TileShape.ROUNDED, favorites.tile.shape) // default
        assertEquals(
            listOf(ItemRef.App("com.plexapp.android"), ItemRef.FolderRef("f1")),
            favorites.items.map(ItemRef::parse),
        )
        assertEquals("bingebandit.continue-watching", (doc.rows[1] as Row.Widget).feed)
        assertTrue(doc.rows[2] is Row.WatchNext)

        assertEquals(Action.OpenInput("HDMI2"), doc.remap[0].action)
        assertEquals(Press.LONG, doc.remap[0].press)
        assertEquals(Action.AllApps, doc.remap[1].action)
    }

    @Test
    fun decodesMinimalExampleWithDefaults() {
        val doc = LayoutJson.decode(example("minimal.json"))

        assertEquals("Home", doc.profile.name)
        assertNull(doc.profile.pin)
        assertNull(doc.theme)
        assertTrue(doc.rows.isEmpty())
        assertTrue(doc.hiddenApps.isEmpty())
    }

    @Test
    fun roundTripsWithoutLoss() {
        val doc = LayoutJson.decode(example("kids.json"))
        assertEquals(doc, LayoutJson.decode(LayoutJson.encode(doc)))
    }

    @Test
    fun ignoresUnknownOptionalFields() {
        val doc = LayoutJson.decode(
            """{"schema":1,"profileId":"p","version":1,"profile":{"name":"A","futureField":true},"rows":[]}""",
        )
        assertEquals("A", doc.profile.name)
    }

    @Test(expected = SerializationException::class)
    fun rejectsNewerSchemaVersion() {
        LayoutJson.decode(example("invalid/schema-2.json"))
    }

    @Test(expected = SerializationException::class)
    fun rejectsUnknownRowType() {
        LayoutJson.decode(example("invalid/bad-row-type.json"))
    }

    @Test
    fun decodesContentRowsWithDefaults() {
        val doc = LayoutJson.decode(example("content-rows.json"))

        val continueWatching = doc.rows[1] as Row.WatchNext
        assertEquals(listOf(WatchNextKind.CONTINUE, WatchNextKind.NEXT), continueWatching.kinds)
        assertNull(continueWatching.apps)
        assertEquals(ContentRow.DEFAULT_LIMIT, continueWatching.limit)
        assertEquals(TileSize.MEDIUM, continueWatching.size)

        val plex = doc.rows[2] as Row.WatchNext
        assertEquals(listOf(WatchNextKind.NEXT, WatchNextKind.NEW), plex.kinds)
        assertEquals(listOf("com.plexapp.android"), plex.apps)
        assertEquals(10, plex.limit)
        assertEquals(TileSize.LARGE, plex.size)

        val youtube = doc.rows[3] as Row.Channel
        assertEquals("com.google.android.youtube.tv", youtube.packageName)
        assertEquals("Recommended", youtube.channel)
        assertEquals(TileSize.SMALL, youtube.size)
        assertNull((doc.rows[4] as Row.Channel).channel)

        assertEquals(doc, LayoutJson.decode(LayoutJson.encode(doc)))
        assertTrue(LayoutJson.encode(doc).contains("\"package\":\"org.jellyfin.androidtv\""))
    }
}
