package com.spacegoose.banditbox.sync

import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Runs one sync: sends the queued changes, then fetches what changed on the server. The TV
 * never waits on this to draw: layouts are always read from [store], and this only updates it.
 *
 * [storeLock] is the same lock the launcher holds while it reads or writes [store], so a sync
 * never interleaves with an edit.
 */
class SyncEngine(
    private val store: LayoutStore,
    private val queue: OpQueue,
    private val states: SyncState.Store,
    private val storeLock: Mutex,
    private val api: (SyncState) -> SyncApi,
    /** Called before the first sync replaces this TV's layouts with the server's, to save a backup. */
    private val beforeReplace: suspend () -> Unit = {},
) {
    sealed interface Result {
        data object NotSignedIn : Result
        data object Revoked : Result

        /** The server couldn't be reached; the queue is kept for next time. */
        data class Offline(val error: Exception) : Result

        data class Synced(
            /** Profiles whose layout on disk changed (or were removed). */
            val changed: Set<String>,
            /** Changes the server didn't take, for a short notice on the TV. */
            val rejected: List<SyncApi.Rejected>,
            /** Profiles made on this TV whose id was taken on the server, old id to new. */
            val renamed: Map<String, String>,
        ) : Result
    }

    private val running = Mutex()

    suspend fun sync(): Result = running.withLock {
        var state = states.load()
        if (!state.signedIn) return Result.NotSignedIn
        val server = api(state)
        try {
            val me = server.me()
            if (state.revoked) state = state.copy(revoked = false).also(states::save)
            val result = if (!state.initialized) initialize(server, me, state) else regular(server, state)
            result
        } catch (_: RevokedException) {
            states.save(states.load().copy(revoked = true))
            Result.Revoked
        } catch (e: IOException) {
            Result.Offline(e)
        } catch (e: ServerException) {
            Result.Offline(e)
        }
    }

    /** First sync after pairing: upload this TV's layouts to a fresh server, or take the server's. */
    private suspend fun initialize(server: SyncApi, me: SyncApi.Me, state: SyncState): Result {
        val changed = mutableSetOf<String>()
        val versions = mutableMapOf<String, Long>()
        var seeded = false
        if (me.fresh) {
            val local = storeLock.withLock { store.profileIds().mapNotNull { store.load(it) } }
            if (local.isNotEmpty()) {
                try {
                    val out = server.seed(local.map(LayoutJsonOps::toJson))
                    storeLock.withLock {
                        for (l in out) {
                            LayoutJsonOps.fromJson(l.doc)?.let(store::saveQuietly)
                            versions[l.profileId] = l.version
                        }
                    }
                    seeded = true
                } catch (e: ServerException) {
                    // 403 (this TV may only use some profiles) or 409 (another TV seeded first): take the server's.
                    if (e.status != 403 && e.status != 409) throw e
                }
            }
        }
        if (!seeded) {
            val profiles = if (me.fresh) server.me().profiles else me.profiles
            val layouts = profiles.map { server.layout(it.id) }
            beforeReplace()
            storeLock.withLock {
                for (l in layouts) {
                    LayoutJsonOps.fromJson(l.doc)?.let {
                        store.saveQuietly(it)
                        versions[l.profileId] = l.version
                        changed += l.profileId
                    }
                }
                for (id in store.profileIds()) {
                    if (id !in versions) {
                        store.deleteQuietly(id)
                        changed += id
                    }
                }
            }
        }
        queue.clear()
        states.save(states.load().copy(initialized = true, versions = versions))
        return Result.Synced(changed, emptyList(), emptyMap())
    }

    private suspend fun regular(server: SyncApi, start: SyncState): Result {
        val versions = start.versions.toMutableMap()
        val changed = mutableSetOf<String>()
        val rejected = mutableListOf<SyncApi.Rejected>()
        val renamed = mutableMapOf<String, String>()

        // 1. Send the queue, oldest first.
        val entries = queue.entries()
        var sent = 0
        /** Ops already included in a profile document sent whole by a Create. */
        val createdNow = mutableSetOf<String>()
        try {
            var i = 0
            while (i < entries.size) {
                when (val e = entries[i]) {
                    is OpQueue.Entry.Create -> {
                        val doc = storeLock.withLock { store.load(e.profileId) }
                        if (doc != null) {
                            val id = createOnServer(server, doc, renamed)
                            versions[id] = 0
                            createdNow += e.profileId
                            changed += setOf(e.profileId, id)
                        }
                        i++
                    }
                    is OpQueue.Entry.Delete -> {
                        try {
                            server.deleteProfile(e.profileId)
                        } catch (ex: ServerException) {
                            // Already gone (404), not allowed (403) or the last profile (409): nothing to send.
                            if (ex.status !in setOf(403, 404, 409)) throw ex
                        }
                        versions -= e.profileId
                        i++
                    }
                    is OpQueue.Entry.Ops -> {
                        // Send consecutive changes to the same profile as one batch.
                        val id = e.profileId
                        val ops = mutableListOf<Op>()
                        while (i < entries.size && (entries[i] as? OpQueue.Entry.Ops)?.profileId == id) {
                            ops += (entries[i] as OpQueue.Entry.Ops).ops
                            i++
                        }
                        val base = versions[id]
                        if (id in createdNow || base == null) {
                            sent = i
                            continue
                        }
                        val res = try {
                            server.sendOps(id, base, ops)
                        } catch (ex: ServerException) {
                            if (ex.status == 403 || ex.status == 404) {
                                sent = i
                                continue
                            }
                            if (ex.status != 409) throw ex
                            // The server is behind us (restored from a backup): take its copy.
                            val l = server.layout(id)
                            SyncApi.OpsResult(l.version, emptyList(), emptyList(), l.doc)
                        }
                        rejected += res.rejected
                        storeLock.withLock { replaceKeepingNewer(id, res.doc, sentCount = i) }
                        versions[id] = res.version
                        changed += id
                    }
                }
                sent = i
            }
        } finally {
            queue.drop(sent)
            states.save(states.load().copy(versions = versions))
        }

        // 2. Fetch what changed on the server.
        val me = server.me()
        val onServer = me.profiles.associateBy { it.id }
        for (p in me.profiles) {
            val have = versions[p.id]
            if (have != null && have >= p.version) continue
            val l = server.layout(p.id)
            storeLock.withLock { replaceKeepingNewer(p.id, l.doc, sentCount = 0) }
            versions[p.id] = l.version
            changed += p.id
        }
        storeLock.withLock {
            for (id in versions.keys.toList()) {
                if (id !in onServer) {
                    // Deleted in the web UI, or this TV no longer has access.
                    store.deleteQuietly(id)
                    versions -= id
                    changed += id
                }
            }
        }
        states.save(states.load().copy(versions = versions))
        return Result.Synced(changed, rejected, renamed)
    }

    /** Creates [doc] on the server. If its id is taken there, gives the local profile a new id. */
    private suspend fun createOnServer(server: SyncApi, doc: LayoutDocument, renamed: MutableMap<String, String>): String {
        var current = doc
        repeat(3) {
            try {
                server.createProfile(LayoutJsonOps.toJson(current))
                return current.profileId
            } catch (e: ServerException) {
                if (e.status != 409) throw e
                val newId = current.profileId.take(55) + "_" + (100000..999999).random()
                val moved = current.copy(profileId = newId)
                storeLock.withLock {
                    store.saveQuietly(moved)
                    store.deleteQuietly(current.profileId)
                }
                renamed[doc.profileId] = newId
                current = moved
            }
        }
        throw ServerException(409, "couldn't find a free profile id")
    }

    /**
     * Saves the server's [doc] for [id], then re-applies changes queued after the first
     * [sentCount] entries (made on the TV while the request was out), so none are lost.
     * Callers hold [storeLock].
     */
    private fun replaceKeepingNewer(id: String, doc: JsonObject, sentCount: Int) {
        val newer = queue.entries().drop(sentCount).filterIsInstance<OpQueue.Entry.Ops>().filter { it.profileId == id }.flatMap { it.ops }
        val merged = LayoutJsonOps.applyAll(doc, newer)
        (LayoutJsonOps.fromJson(merged) ?: LayoutJsonOps.fromJson(doc))?.let(store::saveQuietly)
    }

    /** Forgets the server and everything queued. Layouts stay as they are. */
    fun signOut() {
        queue.clear()
        states.save(SyncState())
    }
}
