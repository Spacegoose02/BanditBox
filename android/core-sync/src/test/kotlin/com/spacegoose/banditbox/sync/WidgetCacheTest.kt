package com.spacegoose.banditbox.sync

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WidgetCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val examples = File(System.getProperty("banditbox.schemaDir"), "examples/widget-feed")

    @Test
    fun parsesTheServerReplyAndKeepsItOnDisk() {
        val feeds = listOf("weather.json", "scores.json").joinToString(",") { File(examples, it).readText() }
        val reply = """{"version": 1794971854632, "feeds": [$feeds, {"schema": 9, "id": "new.thing", "name": "N", "cards": []}]}"""
        val snap = WidgetSnapshot.parse(reply)
        assertEquals(1794971854632, snap.version)
        assertEquals(setOf("weatherscout.now", "statscraps.scores"), snap.feeds.keys)

        val cache = WidgetCache(File(tmp.root, "w/widgets.json"))
        assertEquals(WidgetSnapshot.EMPTY, cache.load())
        cache.save(snap)
        assertEquals(snap, cache.load())
        File(tmp.root, "w/widgets.json").writeText("{broken")
        assertEquals(WidgetSnapshot.EMPTY, cache.load())
    }
}
