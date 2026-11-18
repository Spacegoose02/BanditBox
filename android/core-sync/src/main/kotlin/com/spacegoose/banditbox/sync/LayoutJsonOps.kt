package com.spacegoose.banditbox.sync

import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Converting layouts to JSON for diffing, and applying ops to them. */
internal object LayoutJsonOps {
    fun toJson(doc: LayoutDocument): JsonObject =
        LayoutJson.json.encodeToJsonElement(LayoutDocument.serializer(), doc).jsonObject

    /** Null if the JSON isn't a layout this build can read. */
    fun fromJson(o: JsonObject): LayoutDocument? = runCatching { LayoutJson.decode(o.toString()) }.getOrNull()

    /** Applies [ops] in order, skipping any that no longer fit. */
    fun applyAll(doc: JsonObject, ops: List<Op>): JsonObject =
        ops.fold(doc) { acc, op -> LayoutPaths.apply(acc, op) ?: acc }
}

/**
 * Applies the edit from [before] to [after] on top of [current], the document now on disk.
 * The launcher edits a copy in memory; if sync replaced the file meanwhile, saving [after]
 * whole would undo the server's changes. Merging keeps both.
 */
fun mergeEdit(current: LayoutDocument, before: LayoutDocument, after: LayoutDocument): LayoutDocument {
    if (current == before) return after
    val ops = LayoutPaths.ops(LayoutJsonOps.toJson(before), LayoutJsonOps.toJson(after), { "local" }, { "0000000000000-00000" })
    if (ops.isEmpty()) return current
    val merged = LayoutJsonOps.applyAll(LayoutJsonOps.toJson(current), ops)
    return LayoutJsonOps.fromJson(merged) ?: after
}
