package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutEditsTest {
    private val base = LayoutDocument(
        profileId = "p",
        version = 5,
        profile = Profile("P"),
        rows = listOf(Row.Apps(id = "favorites", items = listOf("a.one", "a.gone", "a.two", "a.three"))),
    )
    private fun items(doc: LayoutDocument, rowId: String = "favorites") =
        (doc.rows.first { it.id == rowId } as Row.Apps).items

    @Test
    fun addToRowAppendsOnceAndBumpsVersion() {
        val once = LayoutEdits.addToRow(base, "favorites", "a.four")
        assertEquals(listOf("a.one", "a.gone", "a.two", "a.three", "a.four"), items(once))
        assertEquals(6, once.version)
        assertSame(once, LayoutEdits.addToRow(once, "favorites", "a.four"))
    }

    @Test
    fun moveStepsOverInvisibleItems() {
        val visible = { item: String -> item != "a.gone" }
        val right = LayoutEdits.moveInRow(base, "favorites", "a.one", +1, visible)
        assertEquals(listOf("a.gone", "a.two", "a.one", "a.three"), items(right))
        val left = LayoutEdits.moveInRow(base, "favorites", "a.two", -1, visible)
        assertEquals(listOf("a.two", "a.one", "a.gone", "a.three"), items(left))
    }

    @Test
    fun moveAtTheEdgeIsANoOp() {
        assertSame(base, LayoutEdits.moveInRow(base, "favorites", "a.three", +1) { true })
        assertSame(base, LayoutEdits.moveInRow(base, "favorites", "a.one", -1) { true })
    }

    @Test
    fun createFolderTakesTheAppsPlace() {
        val (doc, id) = LayoutEdits.createFolder(base, " Sports ", "a.two", "favorites")
        assertEquals("f1", id)
        assertEquals(listOf("a.one", "a.gone", "folder:f1", "a.three"), items(doc))
        assertEquals(Folder("f1", "Sports", listOf("a.two")), doc.folders.single())
        assertEquals(6, doc.version)
    }

    @Test
    fun addToFolderMovesAppOutOfRow() {
        val (withFolder, id) = LayoutEdits.createFolder(base, "Sports", "a.two", "favorites")
        val doc = LayoutEdits.addToFolder(withFolder, id, "a.three", fromRowId = "favorites")
        assertEquals(listOf("a.two", "a.three"), doc.folders.single().items)
        assertEquals(listOf("a.one", "a.gone", "folder:f1"), items(doc))
    }

    @Test
    fun deleteFolderRemovesRowLinks() {
        val (withFolder, id) = LayoutEdits.createFolder(base, "Sports", "a.two", "favorites")
        val doc = LayoutEdits.deleteFolder(withFolder, id)
        assertEquals(emptyList<Folder>(), doc.folders)
        assertEquals(listOf("a.one", "a.gone", "a.three"), items(doc))
    }

    @Test
    fun hideAndUnhide() {
        val hidden = LayoutEdits.hide(base, "a.one")
        assertEquals(listOf("a.one"), hidden.hiddenApps)
        assertSame(hidden, LayoutEdits.hide(hidden, "a.one"))
        assertEquals(emptyList<String>(), LayoutEdits.unhide(hidden, "a.one").hiddenApps)
    }

    @Test
    fun rowsCanBeAddedMovedRetiledAndRemoved() {
        val (added, id) = LayoutEdits.addRow(base, "Games")
        assertEquals(listOf("favorites", "r1"), added.rows.map { it.id })
        val moved = LayoutEdits.moveRow(added, id, -1)
        assertEquals(listOf("r1", "favorites"), moved.rows.map { it.id })
        val tile = Tile(size = TileSize.SMALL, style = TileStyle.ICON, shape = TileShape.CIRCLE)
        assertEquals(tile, (LayoutEdits.setTile(moved, id, tile).rows.first() as Row.Apps).tile)
        assertEquals(listOf("favorites"), LayoutEdits.removeRow(moved, id).rows.map { it.id })
        assertSame(moved, LayoutEdits.moveRow(moved, id, -1))
    }

    @Test
    fun editedDocumentsStillMatchTheSchemaShape() {
        val (doc, _) = LayoutEdits.createFolder(base, "Sports", "a.two", "favorites")
        assertEquals(doc, LayoutJson.decode(LayoutJson.encode(doc)))
    }
}
