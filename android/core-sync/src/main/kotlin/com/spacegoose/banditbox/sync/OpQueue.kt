package com.spacegoose.banditbox.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Changes made on the TV that the server hasn't confirmed yet, oldest first. One JSON line per
 * entry, appended as changes happen, so nothing is lost if the TV is off or the server is down.
 */
class OpQueue(private val file: File) {
    sealed interface Entry {
        val profileId: String

        /** Changes to an existing profile. */
        data class Ops(override val profileId: String, val ops: List<Op>) : Entry

        /** A profile made on the TV. Its document is read from disk when it's sent. */
        data class Create(override val profileId: String) : Entry

        /** A profile deleted on the TV. */
        data class Delete(override val profileId: String) : Entry
    }

    @Synchronized
    fun append(entry: Entry) {
        file.parentFile?.mkdirs()
        // If a crash left a half-written last line, start on a fresh line so this entry survives.
        val needsNewline = file.isFile && file.length() > 0 &&
            java.io.RandomAccessFile(file, "r").use { it.seek(file.length() - 1); it.read() != '\n'.code }
        if (needsNewline) file.appendText("\n")
        file.appendText(Json.encodeToString(JsonElement.serializer(), encode(entry)) + "\n")
    }

    @Synchronized
    fun entries(): List<Entry> {
        if (!file.isFile) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            // A line cut short by a crash mid-write is skipped rather than blocking the queue.
            runCatching { decode(Json.parseToJsonElement(line).jsonObject) }.getOrNull()
        }
    }

    /** Removes the first [count] entries (the ones just sent). */
    @Synchronized
    fun drop(count: Int) {
        val rest = entries().drop(count)
        replace(rest)
    }

    @Synchronized
    fun replace(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(entries.joinToString("") { Json.encodeToString(JsonElement.serializer(), encode(it)) + "\n" })
        tmp.renameTo(file)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun encode(e: Entry): JsonObject = when (e) {
        is Entry.Ops -> JsonObject(mapOf("type" to JsonPrimitive("ops"), "profileId" to JsonPrimitive(e.profileId), "ops" to JsonArray(e.ops.map { it.toJson() })))
        is Entry.Create -> JsonObject(mapOf("type" to JsonPrimitive("create"), "profileId" to JsonPrimitive(e.profileId)))
        is Entry.Delete -> JsonObject(mapOf("type" to JsonPrimitive("delete"), "profileId" to JsonPrimitive(e.profileId)))
    }

    private fun decode(o: JsonObject): Entry {
        val id = o.getValue("profileId").jsonPrimitive.content
        return when (o.getValue("type").jsonPrimitive.content) {
            "ops" -> Entry.Ops(id, o.getValue("ops").jsonArray.map(Op::fromJson))
            "create" -> Entry.Create(id)
            "delete" -> Entry.Delete(id)
            else -> error("unknown entry")
        }
    }
}
