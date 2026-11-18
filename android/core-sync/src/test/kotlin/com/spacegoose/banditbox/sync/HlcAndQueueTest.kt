package com.spacegoose.banditbox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HlcAndQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun clockKeepsIncreasingWhenTimeStandsStillOrGoesBack() {
        var now = 1_763_430_000_000L
        val hlc = Hlc({ now })
        val a = hlc.next()
        val b = hlc.next()
        now -= 5_000
        val c = hlc.next()
        assertTrue(a < b && b < c)
        assertEquals("1763430000000-00002", c)
        assertTrue(Hlc({ 0 }, start = c).next() > c)
    }

    @Test
    fun queueKeepsOrderAndSurvivesABrokenLine() {
        val file = tmp.root.resolve("queue.jsonl")
        val q = OpQueue(file)
        val op = Op("op_1", "1763430000000-00000", Op.Kind.DELETE, "/theme")
        q.append(OpQueue.Entry.Create("p_a"))
        q.append(OpQueue.Entry.Ops("p_a", listOf(op)))
        file.appendText("{\"type\":\"ops\",\"profi") // a crash mid-write
        q.append(OpQueue.Entry.Delete("p_b"))
        val reread = OpQueue(file).entries()
        assertEquals(listOf(OpQueue.Entry.Create("p_a"), OpQueue.Entry.Ops("p_a", listOf(op)), OpQueue.Entry.Delete("p_b")), reread)
        q.drop(2)
        assertEquals(listOf(OpQueue.Entry.Delete("p_b")), q.entries())
    }
}
