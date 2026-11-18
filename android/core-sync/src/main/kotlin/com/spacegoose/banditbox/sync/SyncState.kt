package com.spacegoose.banditbox.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What sync remembers between runs, kept in one small JSON file. The device token is not
 * here: the app keeps it in encrypted storage.
 */
@Serializable
data class SyncState(
    /** Server base URL, like http://192.168.80.10:8686. Null when not signed in. */
    val serverUrl: String? = null,
    val deviceId: String? = null,
    val serverName: String? = null,
    /** False until the first sync after pairing has run (seed or download). */
    val initialized: Boolean = false,
    /** The server version each synced profile was last at. A profile is synced if it's here. */
    val versions: Map<String, Long> = emptyMap(),
    /** The last clock value used, so clocks keep increasing across restarts. */
    val hlc: String? = null,
    /** True when the server refused the token: keep the layouts, show "not synced". */
    val revoked: Boolean = false,
) {
    val signedIn: Boolean get() = serverUrl != null && deviceId != null

    class Store(private val file: File) {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(): SyncState = try {
            if (file.isFile) json.decodeFromString(serializer(), file.readText()) else SyncState()
        } catch (_: Exception) {
            SyncState()
        }

        fun save(state: SyncState) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), state))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
