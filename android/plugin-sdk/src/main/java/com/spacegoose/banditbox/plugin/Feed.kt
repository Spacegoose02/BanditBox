package com.spacegoose.banditbox.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * One feed of cards for the BanditBox home screen. It follows BanditBox's widget feed format
 * (schema/widget-feed.schema.json in the BanditBox repo). A BanditBox layout shows it in a
 * widget row named by [id], for example "wavelength.now-playing".
 *
 * @param id "<plugin>.<feed>": lower-case letters, digits, - and _, with at least one dot.
 * @param template How BanditBox draws the cards: [CARD_ROW], [NOW_PLAYING], [STAT], [WEATHER] or [SCOREBOARD].
 * @param kidsSafe Whether kids profiles may show it.
 */
data class Feed(
    val id: String,
    val name: String,
    val template: String = CARD_ROW,
    val cards: List<Card> = emptyList(),
    val kidsSafe: Boolean = false,
    val sizes: List<String> = listOf("row"),
) {
    init {
        require(ID.matches(id)) { "Feed id must look like myapp.my-feed, got $id" }
        require(cards.size <= MAX_CARDS) { "At most $MAX_CARDS cards" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("id", id)
        .put("name", name.take(60))
        .put("template", template)
        .put("sizes", JSONArray(sizes))
        .put("kidsSafe", kidsSafe)
        .put("cards", JSONArray(cards.map { it.toJson() }))

    companion object {
        const val CARD_ROW = "cardRow"
        const val NOW_PLAYING = "nowPlaying"
        const val STAT = "stat"
        const val WEATHER = "weather"
        const val SCOREBOARD = "scoreboard"
        const val MAX_CARDS = 50
        private val ID = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
    }
}

/**
 * One card.
 *
 * @param image A content:// URI your provider serves (see [BanditBoxFeedProvider.openFile]),
 *   an android.resource:// URI, or an https URL.
 * @param imageAspect Width over height: 0.67 for posters, 1.78 for stills, 1 for album art.
 * @param progress 0 to 1, drawn as a bar along the bottom.
 * @param link What selecting the card opens: an intent URI (Intent.toUri(Intent.URI_INTENT_SCHEME))
 *   or any URI your app handles. Without one, BanditBox opens your app.
 * @param actions Up to 3 long-press actions.
 */
data class Card(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val imageAspect: Float? = null,
    val progress: Float? = null,
    val badge: String? = null,
    val link: String? = null,
    val actions: List<Action> = emptyList(),
    val stat: Stat? = null,
) {
    init {
        require(actions.size <= 3) { "At most 3 actions" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id.take(128))
        put("title", title.take(120))
        subtitle?.let { put("subtitle", it.take(200)) }
        image?.let { put("image", it) }
        imageAspect?.let { put("imageAspect", it.coerceIn(0.25f, 4f).toDouble()) }
        progress?.let { put("progress", it.coerceIn(0f, 1f).toDouble()) }
        badge?.let { put("badge", it.take(12)) }
        link?.let { put("link", it) }
        if (actions.isNotEmpty()) put("actions", JSONArray(actions.map { it.toJson() }))
        stat?.let { put("stat", it.toJson()) }
    }
}

/** A long-press action: a label and what it opens. */
data class Action(val label: String, val link: String) {
    fun toJson(): JSONObject = JSONObject().put("label", label.take(30)).put("link", link)
}

/** The big number of a [Feed.STAT] card. [trend] is "up", "down" or "flat". */
data class Stat(val value: String, val unit: String? = null, val trend: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("value", value.take(16))
        unit?.let { put("unit", it.take(12)) }
        trend?.let { put("trend", it) }
    }
}
