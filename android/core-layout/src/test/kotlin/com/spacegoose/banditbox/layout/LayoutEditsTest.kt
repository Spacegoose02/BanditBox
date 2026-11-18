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

    @Test
    fun setRemapReplacesTheSameKeyAndPress() {
        val red = LayoutEdits.setRemap(base, "PROG_RED", Press.SHORT, Action.OpenApp("com.netflix.ninja"))
        val redLong = LayoutEdits.setRemap(red, "PROG_RED", Press.LONG, Action.AllApps)
        val changed = LayoutEdits.setRemap(redLong, "PROG_RED", Press.SHORT, Action.Home)
        assertEquals(
            listOf(Remap("PROG_RED", Press.SHORT, Action.Home), Remap("PROG_RED", Press.LONG, Action.AllApps)),
            changed.remap,
        )
        assertEquals(8, changed.version)
        assertSame(changed, LayoutEdits.setRemap(changed, "PROG_RED", Press.SHORT, Action.Home))
    }

    @Test
    fun removeRemapLeavesOtherPresses() {
        val doc = LayoutEdits.setRemap(
            LayoutEdits.setRemap(base, "HOME", Press.LONG, Action.Settings),
            "HOME",
            Press.DOUBLE,
            Action.AllApps,
        )
        val removed = LayoutEdits.removeRemap(doc, "HOME", Press.LONG)
        assertEquals(listOf(Remap("HOME", Press.DOUBLE, Action.AllApps)), removed.remap)
        assertSame(removed, LayoutEdits.removeRemap(removed, "HOME", Press.LONG))
    }

    @Test
    fun contentRowsCanBeAddedRenamedAndConfigured() {
        val (withWatchNext, w) = LayoutEdits.addWatchNextRow(base, "Continue watching")
        val (doc, c) = LayoutEdits.addChannelRow(withWatchNext, " YouTube ", "com.google.android.youtube.tv", "Recommended")
        assertEquals(listOf("favorites", "r1", "r2"), doc.rows.map { it.id })
        assertEquals(7L, doc.version)
        assertEquals(Row.Channel(id = c, title = "YouTube", packageName = "com.google.android.youtube.tv", channel = "Recommended"), doc.rows[2])

        val renamed = LayoutEdits.renameRow(doc, w, "Up next")
        assertEquals("Up next", renamed.rows[1].title)
        assertEquals(null, LayoutEdits.renameRow(renamed, c, "  ").rows[2].title)

        val sized = LayoutEdits.setContentSize(renamed, c, TileSize.LARGE)
        assertEquals(TileSize.LARGE, (sized.rows[2] as Row.Channel).size)
        assertSame(sized, LayoutEdits.setContentSize(sized, "favorites", TileSize.SMALL))

        val all = WatchNextKind.entries.toList()
        val kinds = LayoutEdits.setWatchNextKinds(sized, w, all)
        assertEquals(all, (kinds.rows[1] as Row.WatchNext).kinds)
        assertSame(kinds, LayoutEdits.setWatchNextKinds(kinds, w, emptyList()))
        assertEquals(kinds, LayoutJson.decode(LayoutJson.encode(kinds)))
    }
}
