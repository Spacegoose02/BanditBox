package com.spacegoose.banditbox.sync

import com.spacegoose.banditbox.layout.DefaultLayout
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.LayoutStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** A small in-memory server with the same answers as the real one for these cases. */
private class FakeServer : SyncApi {
    val layouts = linkedMapOf<String, Pair<Long, JsonObject>>()
    var fresh = false
    var offline = false
    var duringSendOps: () -> Unit = {}
    val received = mutableListOf<Op>()

    private fun check() {
        if (offline) throw IOException("no route to host")
    }

    fun put(doc: JsonObject, version: Long) {
        val id = (doc["profileId"] as JsonPrimitive).content
        layouts[id] = version to JsonObject(doc + ("version" to JsonPrimitive(version)))
    }

    override fun me(): SyncApi.Me {
        check()
        return SyncApi.Me(SyncApi.Device("d_1"), layouts.map { (id, l) -> SyncApi.ProfileInfo(id, "", l.first) }, fresh)
    }

    override fun layout(profileId: String): SyncApi.ServerLayout {
        check()
        val (v, d) = layouts[profileId] ?: throw ServerException(404, "no such profile")
        return SyncApi.ServerLayout(profileId, v, d)
    }

    override fun sendOps(profileId: String, baseVersion: Long, ops: List<Op>): SyncApi.OpsResult {
        check()
        duringSendOps()
        received += ops
        val (v, d) = layouts.getValue(profileId)
        val next = LayoutJsonOps.applyAll(d, ops)
        put(next, v + 1)
        return SyncApi.OpsResult(v + 1, ops.map { it.opId }, emptyList(), layouts.getValue(profileId).second)
    }

    override fun seed(layouts: List<JsonObject>): List<SyncApi.ServerLayout> {
        check()
        if (!fresh) throw ServerException(409, "not fresh")
        this.layouts.clear()
        layouts.forEach { put(it, 1) }
        fresh = false
        return this.layouts.map { (id, l) -> SyncApi.ServerLayout(id, l.first, l.second) }
    }

    override fun createProfile(doc: JsonObject): SyncApi.ServerLayout {
        check()
        val id = (doc["profileId"] as JsonPrimitive).content
        if (id in layouts) throw ServerException(409, "exists")
        put(doc, 0)
        return SyncApi.ServerLayout(id, 0, layouts.getValue(id).second)
    }

    override fun deleteProfile(profileId: String) {
        check()
        layouts.remove(profileId)
    }
}

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: LayoutStore
    private lateinit var queue: OpQueue
    private lateinit var states: SyncState.Store
    private lateinit var server: FakeServer
    private lateinit var engine: SyncEngine
    private var backups = 0

    @Before
    fun setUp() {
        store = LayoutStore(tmp.newFolder("layouts"))
        queue = OpQueue(tmp.root.resolve("queue.jsonl"))
        states = SyncState.Store(tmp.root.resolve("sync.json"))
        server = FakeServer()
        store.listener = SyncRecorder(queue, states::load, Hlc())
        engine = SyncEngine(store, queue, states, Mutex(), { server }, beforeReplace = { backups++ })
        states.save(SyncState(serverUrl = "http://server:8686", deviceId = "d_1"))
    }

    private fun json(doc: com.spacegoose.banditbox.layout.LayoutDocument) = LayoutJsonOps.toJson(doc)
    private fun sync() = runBlocking { engine.sync() }

    @Test
    fun firstTVSeedsAFreshServer() {
        store.save(DefaultLayout.create(listOf("com.plexapp.android")))
        store.save(DefaultLayout.create(emptyList(), profileId = "p_2", name = "Den"))
        server.fresh = true
        server.put(json(DefaultLayout.create(emptyList(), name = "Server home")), 0)

        val r = sync() as SyncEngine.Result.Synced
        assertTrue(r.changed.isEmpty())
        assertEquals(setOf(DefaultLayout.PROFILE_ID, "p_2"), server.layouts.keys)
        assertEquals("Home", store.load(DefaultLayout.PROFILE_ID)!!.profile.name)
        assertEquals(mapOf(DefaultLayout.PROFILE_ID to 1L, "p_2" to 1L), states.load().versions)
        assertEquals(0, backups)
    }

    @Test
    fun laterTVTakesTheServerLayoutsAfterABackup() {
        store.save(DefaultLayout.create(emptyList(), name = "Old"))
        store.save(DefaultLayout.create(emptyList(), profileId = "p_local", name = "Only here"))
        server.put(json(DefaultLayout.create(emptyList(), name = "From server")), 5)

        sync()
        assertEquals(1, backups)
        assertEquals("From server", store.load(DefaultLayout.PROFILE_ID)!!.profile.name)
        assertNull(store.load("p_local"))
        assertTrue(queue.entries().isEmpty())
    }

    private fun initialized() {
        server.put(json(DefaultLayout.create(listOf("a.b"))), 3)
        sync()
    }

    @Test
    fun editsAreQueuedAndSent() {
        initialized()
        val doc = store.load(DefaultLayout.PROFILE_ID)!!
        store.save(LayoutEdits.setAutoLogin(doc, true))
        assertEquals(1, queue.entries().size)

        sync()
        assertTrue(queue.entries().isEmpty())
        assertEquals(listOf("/profile/autoLogin"), server.received.map { it.path })
        assertEquals(4L, states.load().versions[DefaultLayout.PROFILE_ID])
        assertEquals(true, server.layouts.getValue(DefaultLayout.PROFILE_ID).second["profile"]!!.jsonObject["autoLogin"].let { (it as JsonPrimitive).content.toBoolean() })
    }

    @Test
    fun offlineKeepsTheQueue() {
        initialized()
        store.save(LayoutEdits.setAutoLogin(store.load(DefaultLayout.PROFILE_ID)!!, true))
        server.offline = true
        assertTrue(sync() is SyncEngine.Result.Offline)
        assertEquals(1, queue.entries().size)
        server.offline = false
        sync()
        assertTrue(queue.entries().isEmpty())
    }

    @Test
    fun serverChangesAndDeletionsArrive() {
        initialized()
        server.put(json(DefaultLayout.create(emptyList(), name = "Renamed on the web")), 9)
        server.put(json(DefaultLayout.create(emptyList(), profileId = "p_new", name = "New")), 0)
        val r = sync() as SyncEngine.Result.Synced
        assertEquals(setOf(DefaultLayout.PROFILE_ID, "p_new"), r.changed)
        assertEquals("Renamed on the web", store.load(DefaultLayout.PROFILE_ID)!!.profile.name)
        assertTrue(queue.entries().isEmpty()) // the server's changes are never queued back

        server.layouts.remove("p_new")
        sync()
        assertNull(store.load("p_new"))
        assertFalse("p_new" in states.load().versions)
    }

    @Test
    fun profileMadeOnTheTVIsCreatedAndRenamedIfTaken() {
        initialized()
        server.put(json(DefaultLayout.create(emptyList(), profileId = "p_2", name = "Someone else's")), 0)
        states.save(states.load().copy(versions = states.load().versions)) // p_2 not known locally
        store.save(DefaultLayout.create(emptyList(), profileId = "p_2", name = "Mine"))
        assertEquals(OpQueue.Entry.Create("p_2"), queue.entries().single())

        val r = sync() as SyncEngine.Result.Synced
        val newId = r.renamed.getValue("p_2")
        assertEquals("Mine", store.load(newId)!!.profile.name)
        assertEquals("Mine", (server.layouts.getValue(newId).second["profile"]!!.jsonObject["name"] as JsonPrimitive).content)
        assertEquals("Someone else's", store.load("p_2")!!.profile.name) // the server's p_2 arrives too
    }

    @Test
    fun anEditMadeWhileSendingIsKept() {
        initialized()
        store.save(LayoutEdits.setAutoLogin(store.load(DefaultLayout.PROFILE_ID)!!, true))
        server.duringSendOps = {
            server.duringSendOps = {}
            val cur = store.load(DefaultLayout.PROFILE_ID)!!
            store.save(cur.copy(topBar = listOf("clock")))
        }
        sync()
        val local = store.load(DefaultLayout.PROFILE_ID)!!
        assertEquals(listOf("clock"), local.topBar)
        assertTrue(local.profile.autoLogin)
        assertEquals(1, queue.entries().size) // the second edit waits for the next sync
        sync()
        assertTrue(queue.entries().isEmpty())
        assertEquals(listOf("clock"), LayoutJsonOps.fromJson(server.layouts.getValue(DefaultLayout.PROFILE_ID).second)!!.topBar)
    }

    @Test
    fun mergeEditKeepsChangesMadeOnDiskMeanwhile() {
        val before = DefaultLayout.create(listOf("a.b"))
        val onDisk = before.copy(topBar = listOf("clock"))
        val edited = LayoutEdits.setAutoLogin(before, true)
        val merged = mergeEdit(onDisk, before, edited)
        assertEquals(listOf("clock"), merged.topBar)
        assertTrue(merged.profile.autoLogin)
    }
}
