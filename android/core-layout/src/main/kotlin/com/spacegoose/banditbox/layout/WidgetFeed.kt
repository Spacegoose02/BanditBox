package com.spacegoose.banditbox.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Kotlin mirror of schema/widget-feed.schema.json (v1): one feed of cards from a server
 * connector or an app on the TV. A widget row or a topBar item names a feed by [id].
 * Read leniently: unknown fields are ignored and unknown templates draw as a card row.
 */
@Serializable
data class WidgetFeed(
    val schema: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    /** cardRow, stat, weather, nowPlaying or scoreboard. */
    val template: String = CARD_ROW,
    val sizes: List<String> = emptyList(),
    val refreshSec: Int? = null,
    val kidsSafe: Boolean = false,
    @SerialName("package") val packageName: String? = null,
    val updatedAt: String? = null,
    /** The last fetch failed; [cards] are the last good ones. */
    val error: String? = null,
    val cards: List<WidgetCard> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val CARD_ROW = "cardRow"
        const val STAT = "stat"
        const val WEATHER = "weather"
        const val NOW_PLAYING = "nowPlaying"
        const val SCOREBOARD = "scoreboard"
    }
}

@Serializable
data class WidgetCard(
    val id: String,
    val title: String = "",
    val subtitle: String? = null,
    /** https, content://, android.resource:// or media:sha256-<hex> on the BanditBox server. */
    val image: String? = null,
    val imageAspect: Float? = null,
    val progress: Float? = null,
    val badge: String? = null,
    /** An intent URI (intent:#Intent;...;end) or any URI an app handles. */
    val link: String? = null,
    @SerialName("package") val packageName: String? = null,
    val actions: List<WidgetAction> = emptyList(),
    val stat: WidgetStat? = null,
    val weather: WidgetWeather? = null,
    val score: WidgetScore? = null,
)

@Serializable
data class WidgetAction(val label: String, val link: String)

@Serializable
data class WidgetStat(val value: String, val unit: String? = null, val trend: String? = null)

@Serializable
data class WidgetWeather(
    val temp: Double,
    /** "°F" or "°C". */
    val unit: String,
    /** clear, partly-cloudy, cloudy, fog, drizzle, rain, sleet, snow or thunder. */
    val icon: String,
    val isDay: Boolean = true,
    val label: String? = null,
    val high: Double? = null,
    val low: Double? = null,
    val precipChance: Int? = null,
    val alert: String? = null,
    val days: List<WidgetDay> = emptyList(),
)

@Serializable
data class WidgetDay(
    val day: String,
    val icon: String,
    val high: Double,
    val low: Double,
    val precipChance: Int? = null,
)

@Serializable
data class WidgetScore(
    /** pre, in or post. */
    val state: String,
    val home: WidgetTeam,
    val away: WidgetTeam,
    val detail: String? = null,
    val startsAt: String? = null,
    val broadcast: String? = null,
)

@Serializable
data class WidgetTeam(
    val name: String,
    val abbr: String? = null,
    val score: String? = null,
    val logo: String? = null,
    val color: String? = null,
    val rank: Int? = null,
    val record: String? = null,
    val winner: Boolean = false,
)

/** Reading feeds, and choosing which ones a profile shows. */
object WidgetFeeds {
    private const val MEDIA_PREFIX = "media:sha256-"
    private val shaHex = Regex("^[0-9a-f]{64}$")

    /** Decodes one feed. Throws on a feed this build can't read (another schema version). */
    fun decode(text: String): WidgetFeed = check(LayoutJson.json.decodeFromString(WidgetFeed.serializer(), text))

    /**
     * Decodes a list of feeds, skipping any that this build can't read, so one bad feed never
     * hides the rest.
     */
    fun decodeList(array: JsonArray): List<WidgetFeed> = array.mapNotNull(::decodeOrNull)

    fun decodeOrNull(element: JsonElement): WidgetFeed? =
        runCatching { check(LayoutJson.json.decodeFromJsonElement(WidgetFeed.serializer(), element)) }.getOrNull()

    fun encodeList(feeds: List<WidgetFeed>): String = LayoutJson.json.encodeToString(ListSerializer(WidgetFeed.serializer()), feeds)

    private fun check(feed: WidgetFeed): WidgetFeed {
        require(feed.schema == WidgetFeed.SCHEMA_VERSION) { "Unsupported widget feed schema ${feed.schema}" }
        return feed
    }

    /**
     * The feed a profile may show under [id], or null. A kids profile shows only kids-safe
     * feeds, and only cards that open an app it may use.
     */
    fun visible(feeds: Map<String, WidgetFeed>, id: String, kids: Boolean, allowed: (String) -> Boolean): WidgetFeed? {
        val feed = feeds[id] ?: return null
        if (!kids) return feed
        if (!feed.kidsSafe) return null
        val cards = feed.cards.filter { card ->
            val pkg = card.packageName ?: feed.packageName
            pkg == null || allowed(pkg)
        }
        return feed.copy(cards = cards)
    }

    /** The sha256 of a picture stored on the BanditBox server, or null for any other image. */
    fun mediaSha(image: String?): String? =
        image?.takeIf { it.startsWith(MEDIA_PREFIX) }?.removePrefix(MEDIA_PREFIX)?.takeIf { shaHex.matches(it) }

    /** Whether a topBar item names a feed rather than a built-in item. */
    fun isFeedId(item: String): Boolean = '.' in item
}
