package com.spacegoose.banditbox.layout

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps each profile's layout document as one JSON file in [dir]. This is the TV's source
 * of truth until sync lands, and the same files make local backup and restore trivial.
 * Writes are atomic: a crash mid-save leaves the previous file intact.
 */
class LayoutStore(private val dir: File) {

    /** The saved document, or null if there is none or it can't be read. */
    fun load(profileId: String): LayoutDocument? {
        val file = fileFor(profileId)
        if (!file.isFile) return null
        return try {
            LayoutJson.decode(file.readText())
        } catch (_: IllegalArgumentException) {
            // Unreadable or from a newer schema: keep it aside rather than overwrite the user's layout.
            file.renameTo(File(dir, "${file.name}.unreadable-${System.currentTimeMillis()}"))
            null
        }
    }

    fun loadOrCreate(profileId: String, create: () -> LayoutDocument): LayoutDocument =
        load(profileId) ?: create().also(::save)

    fun save(doc: LayoutDocument) {
        dir.mkdirs()
        val target = fileFor(doc.profileId)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(LayoutJson.encode(doc))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete(profileId: String) {
        fileFor(profileId).delete()
    }

    /** Ids of every saved profile. */
    fun profileIds(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
            ?.map { it.name.removeSuffix(SUFFIX) }
            ?.filter { ID.matches(it) }
            ?.sorted()
            .orEmpty()

    private fun fileFor(profileId: String): File {
        require(ID.matches(profileId)) { "Invalid profile id: $profileId" }
        return File(dir, profileId + SUFFIX)
    }

    companion object {
        private const val SUFFIX = ".json"

        /** Same rule as the schema's id, which also keeps ids safe as file names. */
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isValidId(profileId: String): Boolean = ID.matches(profileId)
    }
}
