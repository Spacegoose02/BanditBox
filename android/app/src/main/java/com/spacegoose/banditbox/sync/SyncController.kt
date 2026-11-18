package com.spacegoose.banditbox.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.spacegoose.banditbox.BuildConfig
import com.spacegoose.banditbox.apps.AppIndex
import com.spacegoose.banditbox.layout.Backup
import com.spacegoose.banditbox.layout.LayoutStore
import com.spacegoose.banditbox.system.BackupFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.WebSocket
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Keeps this TV's layouts in step with a BanditBox server, if it is signed in to one. Lives as
 * long as the app. Layouts are always read from [store]; sync only ever updates it, in the
 * background, so the launcher never waits on the network.
 */
class SyncController(context: Context, val store: LayoutStore, val storeLock: Mutex) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "sync")
    private val states = SyncState.Store(File(dir, "state.json"))
    private val queue = OpQueue(File(dir, "queue.jsonl"))
    private val vault = TokenVault(app)
    private val clock = Hlc(start = states.load().hlc)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sync runs, pairing and sign-out happen one at a time. */
    private val lock = Mutex()

    data class Status(
        val signedIn: Boolean = false,
        val serverName: String? = null,
        val serverUrl: String? = null,
        /** The server no longer knows this TV. Layouts are kept; nothing is sent. */
        val revoked: Boolean = false,
        /** The last sync couldn't reach the server. Changes wait in the queue. */
        val offline: Boolean = false,
        val lastSynced: Instant? = null,
    )

    private val _status = MutableStateFlow(statusOf(states.load()))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _results = MutableSharedFlow<SyncEngine.Result.Synced>(extraBufferCapacity = 16)

    /** Each sync that changed something; the launcher reloads what it shows. */
    val results: SharedFlow<SyncEngine.Result.Synced> = _results.asSharedFlow()

    private val engine = SyncEngine(
        store = store,
        queue = queue,
        states = states,
        storeLock = storeLock,
        api = { s -> HttpSyncApi(s.serverUrl!!, vault.token().orEmpty(), BuildConfig.VERSION_NAME) },
        beforeReplace = ::backUpBeforeReplace,
    )

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        store.listener = SyncRecorder(queue, states::load, clock, onQueued = ::requestSync)
        scope.launch {
            for (unused in wake) {
                delay(DEBOUNCE_MS) // a burst of edits goes out as one request
                runSync()
            }
        }
    }

    /** Syncs soon. Cheap to call often: requests close together become one sync. */
    fun requestSync() {
        wake.trySend(Unit)
    }

    private suspend fun runSync(): Unit = lock.withLock<Unit> {
        val start = states.load()
        if (!start.signedIn) return@withLock
        val last = clock.last()
        val saved = start.hlc
        if (saved == null || last > saved) states.save(start.copy(hlc = last))
        when (val r = engine.sync()) {
            is SyncEngine.Result.Synced -> {
                _status.update { statusOf(states.load()).copy(lastSynced = Instant.now()) }
                if (r.changed.isNotEmpty() || r.rejected.isNotEmpty() || r.renamed.isNotEmpty()) _results.emit(r)
                reportApps()
                scope.launch { installLock.withLock { installs.run(api()) } }
                if (foreground) openStream()
            }
            is SyncEngine.Result.Offline -> {
                Log.i("BanditBox", "Sync: server not reachable: ${r.error.message}")
                _status.update { it.copy(offline = true) }
                retryLater()
            }
            SyncEngine.Result.Revoked -> {
                closeStream()
                _status.update { statusOf(states.load()).copy(lastSynced = it.lastSynced) }
            }
            SyncEngine.Result.NotSignedIn -> {
                _status.value = Status()
            }
        }
    }

    private val installs by lazy { ApkInstaller(app) }
    private val installLock = Mutex()

    private fun api(s: SyncState = states.load()) = HttpSyncApi(s.serverUrl!!, vault.token().orEmpty(), BuildConfig.VERSION_NAME)

    /** Reports an install's state to the server, in the background. */
    fun reportInstall(sha256: String, status: String, detail: String) {
        scope.launch {
            val s = states.load()
            if (!s.signedIn) return@launch
            try {
                api(s).reportInstall(sha256, status, detail)
            } catch (e: IOException) {
                Log.i("BanditBox", "Couldn't report install: ${e.message}")
            } catch (e: ServerException) {
                Log.i("BanditBox", "Server refused install report: ${e.message}")
            } catch (_: RevokedException) {
            }
        }
    }

    /** PackageInstaller finished an install that [ApkInstaller] started. */
    fun installFinished(sha256: String, success: Boolean, detail: String) {
        installs.finished(sha256, success)
        reportInstall(sha256, if (success) ApkInstaller.INSTALLED else ApkInstaller.FAILED, detail)
    }

    private val appIndex by lazy { AppIndex(app) }
    private val prefs by lazy { app.getSharedPreferences("sync", Context.MODE_PRIVATE) }

    /** Sends the installed apps to the server when they changed since last time. */
    private fun reportApps() {
        val s = states.load()
        if (!s.signedIn) return
        val apps = appIndex.load()
            .groupBy { it.packageName }
            .map { (pkg, entries) -> SyncApi.AppReport(pkg, entries.first().label, entries.any { it.isLeanback }) }
            .sortedBy { it.packageName }
        val key = "${s.serverUrl}|${s.deviceId}|${apps.hashCode()}"
        if (prefs.getString(APPS_SENT, null) == key) return
        try {
            HttpSyncApi(s.serverUrl!!, vault.token().orEmpty(), BuildConfig.VERSION_NAME).putApps(apps)
            prefs.edit().putString(APPS_SENT, key).apply()
        } catch (e: IOException) {
            Log.i("BanditBox", "Couldn't send the app list: ${e.message}")
        } catch (e: ServerException) {
            Log.i("BanditBox", "Server didn't take the app list: ${e.message}")
        } catch (_: RevokedException) {
            // The next sync finds out and shows "not synced".
        }
    }

    /** While the launcher is in front, keeps trying an unreachable server now and then. */
    private fun retryLater() {
        scope.launch {
            delay(RETRY_MS)
            if (foreground) requestSync()
        }
    }

    // --- Live updates while the launcher is in front -------------------------------------------

    @Volatile private var foreground = false
    private var socket: WebSocket? = null
    private var socketGeneration = 0

    /** The launcher came to the front (true) or left. In front, server changes arrive at once. */
    fun setForeground(on: Boolean) {
        foreground = on
        if (on) requestSync() else closeStream()
    }

    @Synchronized
    private fun openStream() {
        if (socket != null || !foreground) return
        val s = states.load()
        if (!s.signedIn || !s.initialized || s.revoked) return
        val generation = ++socketGeneration
        val api = HttpSyncApi(s.serverUrl!!, vault.token().orEmpty(), BuildConfig.VERSION_NAME)
        socket = api.stream(
            onVersion = { id, version -> if ((states.load().versions[id] ?: -1) < version) requestSync() },
            onProfiles = ::requestSync,
            onClosed = { revoked -> streamClosed(generation, revoked) },
            onInstalls = ::requestSync,
        )
    }

    @Synchronized
    private fun streamClosed(generation: Int, revoked: Boolean) {
        if (generation != socketGeneration) return // one we closed ourselves
        socket = null
        if (revoked) {
            requestSync() // finds out properly, and shows "not synced"
            return
        }
        // The server went away: try again in a while, as long as the launcher is in front.
        retryLater()
    }

    @Synchronized
    private fun closeStream() {
        socketGeneration++
        socket?.close(1000, null)
        socket = null
    }

    // --- Signing in and out --------------------------------------------------------------------

    sealed interface PairStep {
        /** Show [code]; the admin approves it under Devices at [serverUrl]. */
        data class Showing(val code: String, val serverUrl: String) : PairStep
        data object Approved : PairStep
        data object Denied : PairStep
        data object Expired : PairStep
        data class Failed(val error: Exception) : PairStep
    }

    /**
     * Pairs this TV with the server at [address] (a URL, or host[:port]). Ends with Approved,
     * Denied, Expired or Failed. Cancel collection to give up.
     */
    fun pair(address: String, serverName: String?): Flow<PairStep> = flow {
        val url = ServerUrl.normalize(address)
        if (url == null) {
            emit(PairStep.Failed(IOException("not a valid address")))
            return@flow
        }
        try {
            HttpSyncApi.probe(url)
            val start = HttpSyncApi.startPairing(url, deviceName(), Build.MODEL)
            emit(PairStep.Showing(start.code, url))
            while (true) {
                val r = HttpSyncApi.waitForPairing(url, start.pairId)
                when (r.status) {
                    "pending" -> continue
                    "approved" -> {
                        currentCoroutineContext().ensureActive() // given up on: don't sign in after all
                        val ok = signIn(url, serverName ?: url.toHttpUrlOrNull()?.host ?: url, r.deviceId, r.token)
                        emit(if (ok) PairStep.Approved else PairStep.Failed(IOException("couldn't save the sign-in")))
                    }
                    "denied" -> emit(PairStep.Denied)
                    else -> emit(PairStep.Expired)
                }
                return@flow
            }
        } catch (e: IOException) {
            emit(PairStep.Failed(e))
        } catch (e: ServerException) {
            emit(PairStep.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun signIn(url: String, name: String, deviceId: String?, token: String?): Boolean = lock.withLock {
        if (deviceId == null || token == null) return@withLock false
        closeStream()
        if (!vault.set(token)) return@withLock false
        queue.clear()
        states.save(SyncState(serverUrl = url, deviceId = deviceId, serverName = name, hlc = clock.last()))
        _status.value = statusOf(states.load())
        true
    }.also { if (it) requestSync() }

    /** Stops syncing. Layouts stay as they are; the server keeps this TV listed until it is removed there. */
    fun signOut() {
        scope.launch {
            lock.withLock<Unit> {
                closeStream()
                engine.signOut()
                vault.set(null)
                _status.value = Status()
            }
        }
    }

    private suspend fun backUpBeforeReplace() {
        val text = storeLock.withLock { Backup.encode(Backup.create(store, Instant.now().toString(), BuildConfig.VERSION_NAME)) }
        val files = BackupFiles(app)
        if (files.save(files.newName("before-sync"), text, alsoToDownloads = false) == BackupFiles.Saved.NOWHERE) {
            // Don't replace this TV's layouts without a copy to go back to.
            throw IOException("couldn't save a backup before taking the server's layouts")
        }
    }

    private fun statusOf(s: SyncState) = Status(
        signedIn = s.signedIn,
        serverName = s.serverName,
        serverUrl = s.serverUrl,
        revoked = s.revoked,
    )

    private fun deviceName(): String =
        android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    companion object {
        private const val DEBOUNCE_MS = 1_500L
        private const val RETRY_MS = 30_000L
        private const val APPS_SENT = "apps_sent"
    }
}
