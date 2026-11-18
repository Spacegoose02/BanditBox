package com.spacegoose.banditbox.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One change to a layout: schema/op.schema.json. */
data class Op(
    val opId: String,
    val hlc: String,
    val kind: Kind,
    val path: String,
    val value: JsonElement? = null,
    /** For insert and move: the id to go after, or null for first. */
    val after: String? = null,
) {
    enum class Kind(val wire: String) { SET("set"), INSERT("insert"), MOVE("move"), DELETE("delete") }

    fun toJson(): JsonObject {
        val m = linkedMapOf<String, JsonElement>(
            "opId" to JsonPrimitive(opId),
            "hlc" to JsonPrimitive(hlc),
            "kind" to JsonPrimitive(kind.wire),
            "path" to JsonPrimitive(path),
        )
        if (kind == Kind.SET || kind == Kind.INSERT) m["value"] = value ?: JsonNull
        if (kind == Kind.INSERT || kind == Kind.MOVE) m["after"] = after?.let(::JsonPrimitive) ?: JsonNull
        return JsonObject(m)
    }

    companion object {
        fun fromJson(e: JsonElement): Op {
            val o = e.jsonObject
            val kind = Kind.entries.first { it.wire == o.getValue("kind").jsonPrimitive.content }
            return Op(
                opId = o.getValue("opId").jsonPrimitive.content,
                hlc = o.getValue("hlc").jsonPrimitive.content,
                kind = kind,
                path = o.getValue("path").jsonPrimitive.content,
                value = o["value"],
                after = (o["after"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}
