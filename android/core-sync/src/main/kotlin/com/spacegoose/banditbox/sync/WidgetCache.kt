package com.spacegoose.banditbox.sync

import com.spacegoose.banditbox.layout.LayoutJson
import com.spacegoose.banditbox.layout.WidgetFeed
import com.spacegoose.banditbox.layout.WidgetFeeds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The server's widget feeds as last fetched, and the version they were at. */
data class WidgetSnapshot(
    val version: Long = 0,
    /** Feeds by id. */
    val feeds: Map<String, WidgetFeed> = emptyMap(),
) {
    companion object {
        val EMPTY = WidgetSnapshot()

        /** Reads the server's {version, feeds} reply, skipping feeds this build can't read. */
        fun parse(text: String): WidgetSnapshot {
            val o = LayoutJson.json.parseToJsonElement(text) as JsonObject
            val version = (o["version"] as? JsonPrimitive)?.longOrNull ?: 0
            val feeds = (o["feeds"] as? JsonArray)?.let(WidgetFeeds::decodeList).orEmpty()
            return WidgetSnapshot(version, feeds.associateBy { it.id })
        }
    }

    fun encode(): String = "{\"version\":$version,\"feeds\":${WidgetFeeds.encodeList(feeds.values.toList())}}"
}

/**
 * Keeps the last widget feeds on disk, so the home screen draws them at once after a restart
 * and while the server is down.
 */
class WidgetCache(private val file: File) {
    fun load(): WidgetSnapshot = try {
        if (file.isFile) WidgetSnapshot.parse(file.readText()) else WidgetSnapshot.EMPTY
    } catch (_: Exception) {
        WidgetSnapshot.EMPTY
    }

    fun save(snapshot: WidgetSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(snapshot.encode())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun clear() {
        file.delete()
    }
}
