package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    @Test
    fun ignoresCaseAccentsAndPunctuation() {
        assertTrue(SearchMatch.score("pokemon", "Pokémon: The Series") > 0)
        assertEquals("spider man no way home", SearchMatch.normalize("Spider-Man: No Way Home"))
    }

    @Test
    fun ordersByHowItMatches() {
        val exact = SearchMatch.score("plex", "Plex")
        val start = SearchMatch.score("plex", "Plex Media Server")
        val word = SearchMatch.score("tube", "Smart Tube")
        val inside = SearchMatch.score("tube", "YouTube")
        val words = SearchMatch.score("way home", "No Way Home")
        val allWords = SearchMatch.score("spi home", "Spider-Man: No Way Home")
        assertTrue(exact > start && start > word && word > inside && inside > allWords)
        assertTrue(words > 0)
        assertEquals(0, SearchMatch.score("netflix", "Plex"))
        assertEquals(0, SearchMatch.score("  ", "Plex"))
    }

    @Test
    fun ranksAndLimits() {
        val items = listOf("YouTube", "Plex", "Plexamp", "Netflix", "Smart Tube")
        assertEquals(listOf("Plex", "Plexamp"), SearchMatch.rank("plex", items, 10) { listOf(it) })
        assertEquals(listOf("Smart Tube"), SearchMatch.rank("tube", items, 1) { listOf(it) })
    }

    @Test
    fun otherTextsCountForHalf() {
        data class Show(val title: String, val app: String)
        val shows = listOf(Show("Bluey", "Disney+"), Show("Disney Lorcana", "YouTube"))
        assertEquals(listOf("Disney Lorcana", "Bluey"), SearchMatch.rank("disney", shows, 10) { listOf(it.title, it.app) }.map { it.title })
    }
}
