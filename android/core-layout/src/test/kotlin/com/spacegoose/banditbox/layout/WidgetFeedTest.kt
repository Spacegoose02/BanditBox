package com.spacegoose.banditbox.layout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetFeedTest {
    private val dir = File(System.getProperty("banditbox.schemaDir"), "examples/widget-feed")

    private fun example(name: String) = WidgetFeeds.decode(File(dir, name).readText())

    @Test
    fun decodesEveryExample() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertTrue(files.size >= 3)
        for (f in files) WidgetFeeds.decode(f.readText())
    }

    @Test
    fun readsEachTemplate() {
        val weather = example("weather.json")
        assertEquals(WidgetFeed.WEATHER, weather.template)
        assertEquals(52.0, weather.cards[0].weather!!.temp, 0.0)
        assertEquals("Wind Advisory", weather.cards[0].weather!!.alert)

        val scores = example("scores.json")
        val score = scores.cards[0].score!!
        assertEquals("in", score.state)
        assertEquals("VT", score.away.abbr)
        assertEquals(8, score.home.rank)

        val row = example("continue-watching.json")
        assertEquals("com.bingebandit.tv", row.packageName)
        assertEquals(0.42f, row.cards[0].progress!!, 0.001f)
        assertEquals(1, row.cards[0].actions.size)
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            WidgetFeeds.mediaSha(row.cards[0].image),
        )
        assertNull(WidgetFeeds.mediaSha("https://example.com/a.png"))
        assertNull(WidgetFeeds.mediaSha("media:sha256-../../x"))
    }

    @Test
    fun oneBadFeedDoesNotHideTheRest() {
        val good = LayoutJson.json.parseToJsonElement(File(dir, "weather.json").readText())
        val newer = JsonObject((good as JsonObject) + ("schema" to JsonPrimitive(2)))
        val broken = JsonObject(mapOf("id" to JsonPrimitive("x.y")))
        val feeds = WidgetFeeds.decodeList(JsonArray(listOf(good, newer, broken)))
        assertEquals(listOf("weatherscout.now"), feeds.map { it.id })
    }

    @Test
    fun kidsSeeOnlyKidsSafeFeedsAndApprovedApps() {
        val row = example("continue-watching.json")
        val weather = example("weather.json")
        val feeds = mapOf(row.id to row, weather.id to weather)
        val none: (String) -> Boolean = { false }
        assertEquals(row, WidgetFeeds.visible(feeds, row.id, kids = false, allowed = none))
        assertNull(WidgetFeeds.visible(feeds, row.id, kids = true, allowed = { true }))
        assertEquals(weather, WidgetFeeds.visible(feeds, weather.id, kids = true, allowed = none))
        assertNull(WidgetFeeds.visible(feeds, "missing.feed", kids = false, allowed = none))

        val safeRow = row.copy(kidsSafe = true)
        val shown = WidgetFeeds.visible(mapOf(row.id to safeRow), row.id, kids = true, allowed = { it == "com.other" })
        assertEquals(0, shown!!.cards.size)
    }

    @Test
    fun roundTrips() {
        val feeds = listOf(example("scores.json"), example("weather.json"))
        val text = WidgetFeeds.encodeList(feeds)
        val back = WidgetFeeds.decodeList(LayoutJson.json.parseToJsonElement(text) as JsonArray)
        assertEquals(feeds, back)
    }
}
