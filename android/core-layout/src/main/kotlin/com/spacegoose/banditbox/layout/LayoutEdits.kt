package com.spacegoose.banditbox.layout

/**
 * Every change the TV can make to a layout document. Each function returns a new document
 * with [LayoutDocument.version] bumped, or the same instance when nothing changed. These
 * map one-to-one onto sync ops later, so keep them small and explicit.
 */
object LayoutEdits {

    // ---- Items in rows ----

    fun addToRow(doc: LayoutDocument, rowId: String, item: String): LayoutDocument =
        doc.editAppsRow(rowId) { row -> if (item in row.items) row else row.copy(items = row.items + item) }

    fun removeFromRow(doc: LayoutDocument, rowId: String, item: String): LayoutDocument =
        doc.editAppsRow(rowId) { row -> row.copy(items = row.items.filter { it != item }) }

    /**
     * Moves [item] one place left (-1) or right (+1) among the items that are actually on
     * screen, stepping over hidden or uninstalled entries so every press visibly moves it.
     */
    fun moveInRow(
        doc: LayoutDocument,
        rowId: String,
        item: String,
        direction: Int,
        isVisible: (String) -> Boolean,
    ): LayoutDocument = doc.editAppsRow(rowId) { row ->
        val from = row.items.indexOf(item)
        if (from < 0) return@editAppsRow row
        val step = if (direction < 0) -1 else 1
        var to = from + step
        while (to in row.items.indices && !isVisible(row.items[to])) to += step
        if (to !in row.items.indices) return@editAppsRow row
        row.copy(items = row.items.toMutableList().apply { removeAt(from); add(to, item) })
    }

    // ---- Hidden apps ----

    fun hide(doc: LayoutDocument, pkg: String): LayoutDocument =
        if (pkg in doc.hiddenApps) doc else doc.copy(hiddenApps = doc.hiddenApps + pkg).bumped()

    fun unhide(doc: LayoutDocument, pkg: String): LayoutDocument =
        if (pkg !in doc.hiddenApps) doc else doc.copy(hiddenApps = doc.hiddenApps - pkg).bumped()

    // ---- Folders ----

    /**
     * Creates a folder holding [pkg]. If [rowId] is given the folder goes into that row:
     * in the app's place when the app was there, otherwise at the end.
     * Returns the new document and the folder's id.
     */
    fun createFolder(doc: LayoutDocument, name: String, pkg: String?, rowId: String?): Pair<LayoutDocument, String> {
        val id = nextId("f", doc.folders.map { it.id })
        val ref = ItemRef.folder(id)
        val folder = Folder(id = id, name = name.trim().ifEmpty { "Folder" }, items = listOfNotNull(pkg))
        var next = doc.copy(folders = doc.folders + folder)
        if (rowId != null) {
            next = next.editAppsRow(rowId, bump = false) { row ->
                val at = if (pkg == null) -1 else row.items.indexOf(pkg)
                if (at >= 0) {
                    row.copy(items = row.items.toMutableList().apply { set(at, ref) }.distinct())
                } else {
                    row.copy(items = row.items + ref)
                }
            }
        }
        return next.bumped() to id
    }

    /** Puts [pkg] in a folder, taking it out of [fromRowId] when it came from that row. */
    fun addToFolder(doc: LayoutDocument, folderId: String, pkg: String, fromRowId: String? = null): LayoutDocument {
        val folder = doc.folders.firstOrNull { it.id == folderId } ?: return doc
        if (pkg in folder.items) return doc
        var next = doc.copy(folders = doc.folders.map { if (it.id == folderId) it.copy(items = it.items + pkg) else it })
        if (fromRowId != null) {
            next = next.editAppsRow(fromRowId, bump = false) { row -> row.copy(items = row.items.filter { it != pkg }) }
        }
        return next.bumped()
    }

    fun removeFromFolder(doc: LayoutDocument, folderId: String, pkg: String): LayoutDocument =
        doc.editFolder(folderId) { it.copy(items = it.items.filter { item -> item != pkg }) }

    fun renameFolder(doc: LayoutDocument, folderId: String, name: String): LayoutDocument {
        val clean = name.trim()
        return if (clean.isEmpty()) doc else doc.editFolder(folderId) { it.copy(name = clean) }
    }

    /** Deletes the folder and every row's link to it. The apps stay installed and in All apps. */
    fun deleteFolder(doc: LayoutDocument, folderId: String): LayoutDocument {
        if (doc.folders.none { it.id == folderId }) return doc
        val ref = ItemRef.folder(folderId)
        return doc.copy(
            folders = doc.folders.filter { it.id != folderId },
            rows = doc.rows.map { if (it is Row.Apps) it.copy(items = it.items.filter { item -> item != ref }) else it },
        ).bumped()
    }

    // ---- Rows ----

    /** Adds an empty apps row at the end. Returns the new document and the row's id. */
    fun addRow(doc: LayoutDocument, title: String): Pair<LayoutDocument, String> {
        val id = nextId("r", doc.rows.map { it.id })
        val row = Row.Apps(id = id, title = title.trim().ifEmpty { null })
        return doc.copy(rows = doc.rows + row).bumped() to id
    }

    fun removeRow(doc: LayoutDocument, rowId: String): LayoutDocument =
        if (doc.rows.none { it.id == rowId }) doc else doc.copy(rows = doc.rows.filter { it.id != rowId }).bumped()

    fun renameRow(doc: LayoutDocument, rowId: String, title: String): LayoutDocument =
        doc.editAppsRow(rowId) { it.copy(title = title.trim().ifEmpty { null }) }

    fun moveRow(doc: LayoutDocument, rowId: String, direction: Int): LayoutDocument {
        val from = doc.rows.indexOfFirst { it.id == rowId }
        val to = from + if (direction < 0) -1 else 1
        if (from < 0 || to !in doc.rows.indices) return doc
        val rows = doc.rows.toMutableList().apply { add(to, removeAt(from)) }
        return doc.copy(rows = rows).bumped()
    }

    fun setTile(doc: LayoutDocument, rowId: String, tile: Tile): LayoutDocument =
        doc.editAppsRow(rowId) { it.copy(tile = tile) }

    // ---- Helpers ----

    private fun LayoutDocument.bumped() = copy(version = version + 1)

    private inline fun LayoutDocument.editAppsRow(
        rowId: String,
        bump: Boolean = true,
        change: (Row.Apps) -> Row.Apps,
    ): LayoutDocument {
        var changed = false
        val newRows = rows.map { row ->
            if (row is Row.Apps && row.id == rowId) {
                change(row).also { if (it != row) changed = true }
            } else {
                row
            }
        }
        if (!changed) return this
        val next = copy(rows = newRows)
        return if (bump) next.bumped() else next
    }

    private inline fun LayoutDocument.editFolder(folderId: String, change: (Folder) -> Folder): LayoutDocument {
        var changed = false
        val newFolders = folders.map { f ->
            if (f.id == folderId) change(f).also { if (it != f) changed = true } else f
        }
        return if (changed) copy(folders = newFolders).bumped() else this
    }

    /** The first "<prefix><n>" not already taken. */
    private fun nextId(prefix: String, taken: List<String>): String {
        val used = taken.toSet()
        var n = 1
        while ("$prefix$n" in used) n++
        return "$prefix$n"
    }
}
