package com.spacegoose.banditbox.content

import com.spacegoose.banditbox.layout.Row
import com.spacegoose.banditbox.layout.WatchNextKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentSelectTest {
    private val everyone: (String) -> Boolean = { true }

    private fun wn(id: Long, pkg: String, title: String, kind: WatchNextKind, at: Long) =
        TvProgram(id = id, packageName = pkg, title = title, kind = kind, lastEngagement = at)

    private val watchNext = listOf(
        wn(1, "plex", "Andor", WatchNextKind.NEXT, at = 100),
        wn(2, "plex", "Dune", WatchNextKind.CONTINUE, at = 300),
        wn(3, "jellyfin", "Severance", WatchNextKind.CONTINUE, at = 200),
        wn(4, "plex", "andor ", WatchNextKind.CONTINUE, at = 50),
        wn(5, "youtube", "Saved video", WatchNextKind.WATCHLIST, at = 400),
        wn(6, "hidden", "Secret", WatchNextKind.CONTINUE, at = 500),
    )

    @Test
    fun watchNextIsMostRecentFirstOnePerShow() {
        val cards = ContentSelect.watchNext(Row.WatchNext(id = "w"), watchNext) { it != "hidden" }
        assertEquals(listOf(2L, 3L, 1L), cards.map { it.id })
    }

    @Test
    fun watchNextFiltersKindsAppsAndLimit() {
        val row = Row.WatchNext(id = "w", kinds = listOf(WatchNextKind.CONTINUE, WatchNextKind.WATCHLIST), apps = listOf("plex", "youtube"), limit = 2)
        assertEquals(listOf(5L, 2L), ContentSelect.watchNext(row, watchNext, everyone).map { it.id })
    }

    private val channels = listOf(
        TvChannel(id = 20, packageName = "youtube", displayName = "Trending", internalProviderId = "trending"),
        TvChannel(id = 10, packageName = "youtube", displayName = "Recommended"),
        TvChannel(id = 30, packageName = "plex", displayName = "On Deck"),
    )
    private val previews = listOf(
        TvProgram(id = 1, packageName = "youtube", title = "A", channelId = 10, weight = 1),
        TvProgram(id = 2, packageName = "youtube", title = "B", channelId = 10, weight = 5),
        TvProgram(id = 3, packageName = "youtube", title = "C", channelId = 10, weight = 5),
        TvProgram(id = 4, packageName = "youtube", title = "D", channelId = 10, browsable = false),
        TvProgram(id = 5, packageName = "youtube", title = "E", channelId = 20),
    )

    @Test
    fun channelMatchesByProviderIdOrNameElseFirst() {
        assertEquals(20L, ContentSelect.findChannel(Row.Channel(id = "c", packageName = "youtube", channel = "trending"), channels)?.id)
        assertEquals(10L, ContentSelect.findChannel(Row.Channel(id = "c", packageName = "youtube", channel = "recommended"), channels)?.id)
        assertEquals(10L, ContentSelect.findChannel(Row.Channel(id = "c", packageName = "youtube"), channels)?.id)
        assertNull(ContentSelect.findChannel(Row.Channel(id = "c", packageName = "youtube", channel = "Gone"), channels))
    }

    @Test
    fun channelProgramsInAppOrderSkippingRemoved() {
        val row = Row.Channel(id = "c", packageName = "youtube", channel = "Recommended")
        assertEquals(listOf(2L, 3L, 1L), ContentSelect.channel(row, channels, previews, everyone).map { it.id })
        assertEquals(emptyList<TvProgram>(), ContentSelect.channel(row, channels, previews) { it != "youtube" })
    }

    @Test
    fun pickableChannelsSkipBlockedApps() {
        assertEquals(listOf(10L, 20L), ContentSelect.pickable(channels) { it == "youtube" }.map { it.id })
        assertEquals("trending", channels[0].ref)
        assertEquals("Recommended", channels[1].ref)
    }
}
