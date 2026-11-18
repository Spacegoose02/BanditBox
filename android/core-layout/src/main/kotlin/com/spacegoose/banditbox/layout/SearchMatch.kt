package com.spacegoose.banditbox.layout

import java.text.Normalizer

/**
 * Scores how well a typed query matches some text, for the search screen. Case, accents and
 * punctuation are ignored, so "pokemon" finds "Pokémon: The Series".
 */
object SearchMatch {
    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase()
            .replace(nonWord, " ")
            .trim()

    /**
     * 0 when [query] doesn't match [text]; otherwise higher is better: the whole text, then
     * its start, then the start of a word, then anywhere, then every query word starting a word.
     */
    fun score(query: String, text: String?): Int {
        val q = normalize(query)
        if (q.isEmpty() || text.isNullOrBlank()) return 0
        val t = normalize(text)
        val words = t.split(' ')
        return when {
            t == q -> 100
            t.startsWith(q) -> 80
            words.any { it.startsWith(q) } -> 60
            t.contains(q) -> 40
            q.split(' ').all { part -> words.any { it.startsWith(part) } } -> 30
            else -> 0
        }
    }

    /**
     * The items that match, best first, at most [limit]. [texts] gives each item's main text
     * first; matches in the other texts (an episode name, the app's name) count for half.
     */
    fun <T> rank(query: String, items: List<T>, limit: Int, texts: (T) -> List<String?>): List<T> =
        items.asSequence()
            .map { item ->
                val all = texts(item)
                val best = all.withIndex().maxOfOrNull { (i, text) -> score(query, text) / (if (i == 0) 1 else 2) } ?: 0
                item to best
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second } // stable: ties keep their order
            .take(limit)
            .map { it.first }
            .toList()
}
