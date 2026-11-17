package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutResolverTest {
    private val doc = LayoutDocument(
        profileId = "p",
        profile = Profile("P"),
        rows = listOf(
            Row.Apps(id = "r1", items = listOf("a.plex", "folder:f1", "a.gone", "a.hidden", "a.plex", "folder:empty")),
            Row.Widget(id = "r2", feed = "x.y"),
            Row.Apps(id = "r3", items = listOf("a.gone")),
        ),
        folders = listOf(
            Folder("f1", "Sports", listOf("a.espn", "a.gone")),
            Folder("empty", "Empty", listOf("a.gone")),
        ),
        hiddenApps = listOf("a.hidden"),
    )
    private val installed = setOf("a.plex", "a.espn", "a.hidden", "a.youtube")

    @Test
    fun dropsHiddenUninstalledAndDuplicateApps() {
        val rows = LayoutResolver.appsRows(doc, installed)
        assertEquals(
            listOf(
                ResolvedItem.App("a.plex"),
                ResolvedItem.FolderItem("f1", "Sports", listOf("a.espn")),
            ),
            rows.single().items,
        )
    }

    @Test
    fun skipsEmptyRowsAndNonAppRows() {
        assertEquals(listOf("r1"), LayoutResolver.appsRows(doc, installed).map { it.id })
    }

    @Test
    fun allAppsHidesHiddenAndKeepsOrder() {
        assertEquals(listOf("a.espn", "a.plex", "a.youtube"), LayoutResolver.allApps(doc, listOf("a.espn", "a.hidden", "a.plex", "a.youtube")))
    }

    @Test
    fun defaultLayoutCapsFavorites() {
        val d = DefaultLayout.create((1..20).map { "a.app$it" })
        val favorites = d.rows.single() as Row.Apps
        assertEquals(8, favorites.items.size)
        assertTrue(d.topBar.containsAll(listOf("clock", "settings")))
    }
}
