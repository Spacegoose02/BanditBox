package com.spacegoose.banditbox.system

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A backup the user can restore: a file BanditBox saved, or one picked with the system file picker. */
class BackupSource(val name: String, val read: () -> String)

/**
 * Where backups live on the TV. Each backup is written twice:
 * - to BanditBox's own folder (Android/data/<package>/files/backups), which always works and is
 *   reachable with `adb pull` / `adb push`;
 * - to Downloads/BanditBox, where file managers and "send files" apps can find it.
 * The system file picker is offered on top when the TV has one (many Google TV devices don't).
 */
class BackupFiles(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** BanditBox's own backup folder; files pushed here with adb show up in the restore list. */
    val ownDir: File? get() = appContext.getExternalFilesDir(BACKUPS)

    /** True when the TV has a file picker for "Save to a file" and "Choose a file". */
    val hasFilePicker: Boolean by lazy {
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(MIME)
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val pm = appContext.packageManager
        create.resolveActivity(pm) != null && open.resolveActivity(pm) != null
    }

    fun newName(prefix: String = "banditbox-backup"): String =
        "$prefix-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json"

    /** Where the backup ended up, or [Saved.NOWHERE] when both places failed. */
    enum class Saved { DOWNLOADS_AND_APP, APP_ONLY, DOWNLOADS_ONLY, NOWHERE }

    /** Saves [text] as [name] in both places. Call off the main thread. */
    fun save(name: String, text: String, alsoToDownloads: Boolean = true): Saved {
        val own = try {
            val dir = ownDir ?: throw IOException("No app storage")
            dir.mkdirs()
            File(dir, name).writeText(text)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't save $name to app storage", e)
            false
        }
        val downloads = alsoToDownloads && saveToDownloads(name, text)
        return when {
            own && downloads -> Saved.DOWNLOADS_AND_APP
            own -> Saved.APP_ONLY
            downloads -> Saved.DOWNLOADS_ONLY
            else -> Saved.NOWHERE
        }
    }

    private fun saveToDownloads(name: String, text: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOADS_PATH)
        }
        return try {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return false
            true
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't save $name to Downloads", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "No Downloads collection on this device", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to save to Downloads", e)
            false
        }
    }

    /** Writes [text] to a file the user picked. */
    fun writeTo(uri: Uri, text: String) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("Can't write $uri")
    }

    fun readFrom(uri: Uri): String = resolver.openInputStream(uri)?.use(::readCapped)
        ?: throw IOException("Can't read $uri")

    /**
     * Backups BanditBox can find on its own, newest first: its own folder, then the ones it saved
     * to Downloads (Android only shows an app the Downloads files it made itself).
     */
    fun list(): List<BackupSource> {
        val own = ownDir?.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { file -> BackupSource(file.name) { file.inputStream().use(::readCapped) } }
        val ownNames = own.map { it.name }.toSet()
        return own + downloads().filter { it.name !in ownNames }
    }

    private fun downloads(): List<BackupSource> = try {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(DOWNLOADS_PATH),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString())
                    add(BackupSource(cursor.getString(1)) { readFrom(uri) })
                }
            }
        }.orEmpty()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Couldn't list backups in Downloads", e)
        emptyList()
    }

    /** Reads at most [MAX_BYTES], so picking a film by mistake can't run the launcher out of memory. */
    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("Too big to be a BanditBox backup")
        }
        return out.toByteArray().decodeToString()
    }

    companion object {
        const val MIME = "application/json"
        private const val TAG = "BanditBox"
        private const val BACKUPS = "backups"
        private const val DOWNLOADS_PATH = "Download/BanditBox/"

        /** Real backups are a few KB per profile; anything far bigger is the wrong file. */
        private const val MAX_BYTES = 5 * 1024 * 1024
    }
}
