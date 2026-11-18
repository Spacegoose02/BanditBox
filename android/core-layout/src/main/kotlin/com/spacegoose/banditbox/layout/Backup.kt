package com.spacegoose.banditbox.layout

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kotlin mirror of schema/backup.schema.json: every profile's layout document in one file. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val backupVersion: Int = VERSION,
    /** ISO 8601, for example 2026-11-18T00:30:00Z. */
    val createdAt: String,
    /** Version of the app that made it, for support only. */
    val app: String? = null,
    val layouts: List<LayoutDocument>,
) {
    companion object {
        const val FORMAT = "banditbox-backup"
        const val VERSION = 1
    }
}

/** Why a file can't be restored, in words the TV can show. */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Local backup and restore of every profile as one JSON file. */
object Backup {
    /** Backups are meant to be read by people too, so they are indented. */
    private val json = Json(from = LayoutJson.json) { prettyPrint = true }

    /** Every readable profile in [store], in id order. */
    fun create(store: LayoutStore, createdAt: String, app: String? = null): BackupFile =
        BackupFile(createdAt = createdAt, app = app, layouts = store.profileIds().mapNotNull(store::load))

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup) + "\n"

    /** Reads and checks a backup. Throws [InvalidBackupException] for anything that can't be restored safely. */
    fun decode(text: String): BackupFile {
        val backup = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw InvalidBackupException("Not a BanditBox backup", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("Not a BanditBox backup", e)
        }
        if (backup.format != BackupFile.FORMAT) throw InvalidBackupException("Not a BanditBox backup")
        if (backup.backupVersion != BackupFile.VERSION) {
            throw InvalidBackupException("Made by a newer BanditBox (backup version ${backup.backupVersion})")
        }
        if (backup.layouts.isEmpty()) throw InvalidBackupException("The backup has no profiles")
        backup.layouts.firstOrNull { it.schema != LayoutDocument.SCHEMA_VERSION }?.let {
            throw InvalidBackupException("Made by a newer BanditBox (layout schema ${it.schema})")
        }
        backup.layouts.firstOrNull { !LayoutStore.isValidId(it.profileId) }?.let {
            throw InvalidBackupException("Invalid profile id: ${it.profileId}")
        }
        val ids = backup.layouts.map { it.profileId }
        if (ids.toSet().size != ids.size) throw InvalidBackupException("The backup has the same profile twice")
        return backup
    }

    /**
     * Replaces every profile in [store] with the backup's. New files are written first, then
     * profiles the backup doesn't have are removed, so a failure part way never leaves no profiles.
     */
    fun restore(store: LayoutStore, backup: BackupFile) {
        val keep = backup.layouts.map { it.profileId }.toSet()
        backup.layouts.forEach(store::save)
        store.profileIds().filterNot { it in keep }.forEach(store::delete)
    }
}
