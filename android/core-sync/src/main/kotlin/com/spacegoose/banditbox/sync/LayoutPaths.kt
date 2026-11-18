package com.spacegoose.banditbox.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The path rules from schema/README.md, matching the server's syncdoc package: paths are like
 * JSON Pointer, except elements of id-keyed arrays (rows, folders) are addressed by id.
 */
object LayoutPaths {
    const val ORDER = "@order"

    /** Fields the server manages; never sent as changes. */
    private val serverFields = setOf("version", "profileId")

    fun split(path: String): List<String> {
        require(path.startsWith("/") && path.length > 1) { "bad path $path" }
        return path.substring(1).split('/').map {
            require(it.isNotEmpty()) { "bad path $path" }
            it.replace("~1", "/").replace("~0", "~")
        }
    }

    fun join(parts: List<String>): String =
        parts.joinToString("") { "/" + it.replace("~", "~0").replace("/", "~1") }

    /**
     * The ops that turn [before] into [after], with ids and clocks from [ids] and [clock]. Order:
     * deletes, then inserts and moves in the new order, then field changes.
     */
    fun ops(before: JsonObject, after: JsonObject, ids: () -> String, clock: () -> String): List<Op> {
        val deletes = mutableListOf<Op>()
        val places = mutableListOf<Op>()
        val sets = mutableListOf<Op>()

        fun walk(at: List<String>, a: JsonElement?, b: JsonElement?) {
            if (a == b) return
            if (a is JsonObject && b is JsonObject) {
                for (k in (a.keys + b.keys)) {
                    if (at.isEmpty() && k in serverFields) continue
                    val x = a[k]
                    val y = b[k]
                    when {
                        y == null -> deletes += Op(ids(), clock(), Op.Kind.DELETE, join(at + k))
                        x == null -> sets += Op(ids(), clock(), Op.Kind.SET, join(at + k), y)
                        else -> walk(at + k, x, y)
                    }
                }
                return
            }
            if (a is JsonArray && b is JsonArray && keyed(a) && keyed(b) && (a.isNotEmpty() || b.isNotEmpty())) {
                val aIds = a.map { idOf(it)!! }
                val bIds = b.map { idOf(it)!! }
                for (id in aIds) if (id !in bIds) deletes += Op(ids(), clock(), Op.Kind.DELETE, join(at + id))
                // Walk the new order: insert new ids, and move any id whose predecessor changed.
                val kept = aIds.filter { it in bIds }.toMutableList()
                var prev: String? = null
                for ((i, id) in bIds.withIndex()) {
                    val el = b[i]
                    if (id !in aIds) {
                        places += Op(ids(), clock(), Op.Kind.INSERT, join(at + id), el, prev)
                        kept.add(if (prev == null) 0 else kept.indexOf(prev) + 1, id)
                    } else {
                        val pos = kept.indexOf(id)
                        val currentPrev = if (pos == 0) null else kept[pos - 1]
                        if (currentPrev != prev) {
                            places += Op(ids(), clock(), Op.Kind.MOVE, join(at + id), after = prev)
                            kept.removeAt(pos)
                            kept.add(if (prev == null) 0 else kept.indexOf(prev) + 1, id)
                        }
                        walk(at + id, a[aIds.indexOf(id)], el)
                    }
                    prev = id
                }
                return
            }
            if (at.isNotEmpty()) {
                if (b == null) deletes += Op(ids(), clock(), Op.Kind.DELETE, join(at)) else sets += Op(ids(), clock(), Op.Kind.SET, join(at), b)
            }
        }
        walk(emptyList(), before, after)
        return deletes + places + sets
    }

    /** Applies [op] to [doc] and returns the result, or null if the op doesn't fit (the path is gone). */
    fun apply(doc: JsonObject, op: Op): JsonObject? {
        val parts = runCatching { split(op.path) }.getOrNull() ?: return null
        if (parts.last() == ORDER) return null
        return applyAt(doc, parts, op) as? JsonObject
    }

    private fun applyAt(node: JsonElement, parts: List<String>, op: Op): JsonElement? {
        val seg = parts.first()
        val last = parts.size == 1
        when (node) {
            is JsonObject -> {
                if (last) {
                    return when (op.kind) {
                        Op.Kind.SET -> JsonObject(node + (seg to (op.value ?: return null)))
                        Op.Kind.DELETE -> JsonObject(node - seg)
                        else -> null
                    }
                }
                val child = node[seg] ?: return null
                val updated = applyAt(child, parts.drop(1), op) ?: return null
                return JsonObject(node + (seg to updated))
            }
            is JsonArray -> {
                if (!keyed(node)) return null
                val i = node.indexOfFirst { idOf(it) == seg }
                if (!last) {
                    if (i < 0) return null
                    val updated = applyAt(node[i], parts.drop(1), op) ?: return null
                    return JsonArray(node.toMutableList().also { it[i] = updated })
                }
                val list = node.toMutableList()
                return when (op.kind) {
                    Op.Kind.SET -> {
                        if (i < 0 || op.value?.let(::idOf) != seg) return null
                        list[i] = op.value
                        JsonArray(list)
                    }
                    Op.Kind.DELETE -> {
                        if (i >= 0) list.removeAt(i)
                        JsonArray(list)
                    }
                    Op.Kind.INSERT -> {
                        val v = op.value ?: return null
                        if (i >= 0 || idOf(v) != seg) return null
                        JsonArray(placeAfter(list, v, op.after))
                    }
                    Op.Kind.MOVE -> {
                        if (i < 0) return null
                        val el = list.removeAt(i)
                        JsonArray(placeAfter(list, el, op.after))
                    }
                }
            }
            else -> return null
        }
    }

    private fun placeAfter(list: MutableList<JsonElement>, el: JsonElement, after: String?): List<JsonElement> {
        val pos = if (after == null) 0 else list.indexOfFirst { idOf(it) == after }.let { if (it < 0) list.size else it + 1 }
        list.add(pos, el)
        return list
    }

    private fun keyed(arr: JsonArray) = arr.all { idOf(it) != null }

    private fun idOf(e: JsonElement): String? = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
