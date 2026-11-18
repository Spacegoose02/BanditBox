package com.spacegoose.banditbox.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPathsTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private var n = 0
    private fun ops(a: JsonObject, b: JsonObject) = LayoutPaths.ops(a, b, { "op_${n++}" }, { "1763430000000-00000" })

    private val base = obj(
        """{"version":3,"profile":{"name":"Home"},"rows":[
            {"id":"a","type":"apps","title":"A","items":["x.y"]},
            {"id":"b","type":"apps","title":"B"},
            {"id":"c","type":"watchnext"}],"topBar":["clock"]}""",
    )

    /** Whatever the edit, applying the ops to the old document gives the new one. */
    private fun assertRoundTrip(after: JsonObject) {
        val result = LayoutJsonOps.applyAll(base, ops(base, after))
        assertEquals(after - "version", result - "version")
    }

    @Test
    fun noChangeNoOps() = assertTrue(ops(base, base).isEmpty())

    @Test
    fun serverFieldsAreIgnored() = assertTrue(ops(base, JsonObject(base + ("version" to Json.parseToJsonElement("9")))).isEmpty())

    @Test
    fun fieldChangeIsOneSetAtTheFinestPath() {
        val after = obj(base.toString().replace("\"B\"", "\"Bee\""))
        val o = ops(base, after)
        assertEquals(1, o.size)
        assertEquals(Op.Kind.SET, o[0].kind)
        assertEquals("/rows/b/title", o[0].path)
        assertRoundTrip(after)
    }

    @Test
    fun plainListsAreSetWhole() {
        val after = obj(base.toString().replace("[\"clock\"]", "[\"clock\",\"settings\"]"))
        val o = ops(base, after)
        assertEquals(listOf("/topBar"), o.map { it.path })
        assertRoundTrip(after)
    }

    @Test
    fun reorderInsertDeleteRoundTrip() {
        val after = obj(
            """{"version":3,"profile":{"name":"Home","autoLogin":true},"rows":[
                {"id":"c","type":"watchnext"},
                {"id":"n","type":"widget","feed":"x.y"},
                {"id":"a","type":"apps","title":"A2","items":["x.y","p.q"]}],"topBar":["clock"],"hiddenApps":[]}""",
        )
        val o = ops(base, after)
        assertEquals(Op.Kind.DELETE, o.first().kind) // deletes first
        assertTrue(o.any { it.kind == Op.Kind.INSERT && it.path == "/rows/n" && it.after == "c" })
        assertRoundTrip(after)
    }

    @Test
    fun emptyRowsToSomeAndBack() {
        val empty = obj("""{"profile":{"name":"Home"},"rows":[]}""")
        val one = obj("""{"profile":{"name":"Home"},"rows":[{"id":"r","type":"watchnext"}]}""")
        assertEquals(one, LayoutJsonOps.applyAll(empty, ops(empty, one)))
        assertEquals(empty, LayoutJsonOps.applyAll(one, ops(one, empty)))
    }

    @Test
    fun applyRefusesPathsThatDontFit() {
        val op = { kind: Op.Kind, path: String -> Op("o", "h", kind, path, Json.parseToJsonElement("1")) }
        assertNull(LayoutPaths.apply(base, op(Op.Kind.SET, "/theme/accent")))
        assertNull(LayoutPaths.apply(base, op(Op.Kind.SET, "/topBar/0")))
        assertNull(LayoutPaths.apply(base, op(Op.Kind.SET, "/rows/@order")))
        assertNull(LayoutPaths.apply(base, op(Op.Kind.MOVE, "/rows/zz")))
    }

    @Test
    fun opJsonMatchesTheSchemaShape() {
        val insert = Op("op_1", "1763430000000-00000", Op.Kind.INSERT, "/rows/n", obj("""{"id":"n","type":"watchnext"}"""), null)
        val json = insert.toJson()
        assertTrue("after" in json && "value" in json)
        assertEquals(insert, Op.fromJson(json))
        val del = Op("op_2", "1763430000000-00000", Op.Kind.DELETE, "/theme")
        assertEquals(setOf("opId", "hlc", "kind", "path"), del.toJson().keys)
        assertEquals("/a~1b/c~0d", LayoutPaths.join(listOf("a/b", "c~d")))
        assertEquals(listOf("a/b", "c~d"), LayoutPaths.split("/a~1b/c~0d"))
    }
}
