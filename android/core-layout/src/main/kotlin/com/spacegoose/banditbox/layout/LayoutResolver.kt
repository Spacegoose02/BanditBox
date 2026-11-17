package com.spacegoose.banditbox.layout

/** Something a row can show, after hidden and uninstalled apps are removed. */
sealed interface ResolvedItem {
    data class App(val packageName: String) : ResolvedItem
    data class FolderItem(val id: String, val name: String, val packages: List<String>) : ResolvedItem
}

data class ResolvedAppsRow(
    val id: String,
    val title: String?,
    val tile: Tile,
    val items: List<ResolvedItem>,
)

/**
 * Turns a layout document into what the home screen draws, given the apps installed now.
 * Uninstalled and hidden apps drop out, duplicates collapse, folders with nothing left in
 * them disappear, and empty rows are skipped. The document itself is never changed, so an
 * app that comes back (reinstalled, or unhidden) reappears where it was.
 */
object LayoutResolver {
    fun appsRows(doc: LayoutDocument, installed: Set<String>): List<ResolvedAppsRow> {
        val hidden = doc.hiddenApps.toSet()
        fun visible(pkg: String) = pkg in installed && pkg !in hidden
        val folders = doc.folders.associateBy { it.id }

        return doc.rows.filterIsInstance<Row.Apps>().mapNotNull { row ->
            val items = row.items.distinct().mapNotNull { raw ->
                when (val ref = ItemRef.parse(raw)) {
                    is ItemRef.App -> ref.packageName.takeIf(::visible)?.let(ResolvedItem::App)
                    is ItemRef.FolderRef -> folders[ref.folderId]?.let { folder ->
                        val apps = folder.items.distinct().filter(::visible)
                        if (apps.isEmpty()) null else ResolvedItem.FolderItem(folder.id, folder.name, apps)
                    }
                }
            }
            if (items.isEmpty()) null else ResolvedAppsRow(row.id, row.title, row.tile, items)
        }
    }

    /** Apps for the All apps screen: everything installed that isn't hidden. */
    fun allApps(doc: LayoutDocument, installedInOrder: List<String>): List<String> {
        val hidden = doc.hiddenApps.toSet()
        return installedInOrder.filter { it !in hidden }
    }
}
