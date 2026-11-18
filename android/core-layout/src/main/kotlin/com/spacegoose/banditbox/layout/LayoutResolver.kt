package com.spacegoose.banditbox.layout

/** Something a row can show, after hidden and uninstalled apps are removed. */
sealed interface ResolvedItem {
    data class App(val packageName: String) : ResolvedItem
    data class FolderItem(val id: String, val name: String, val packages: List<String>) : ResolvedItem
}

/** One row of the home screen. */
sealed interface HomeRow {
    val id: String

    data class Apps(val row: ResolvedAppsRow) : HomeRow {
        override val id: String get() = row.id
    }

    /** A Watch Next or preview channel row; its cards come from the TV at draw time. */
    data class Content(val row: ContentRow) : HomeRow {
        override val id: String get() = row.id
    }
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

    /**
     * Every row the home screen draws, in the document's order: resolved app rows (empty ones
     * skipped) and content rows, which are filled in from what other apps publish on the TV.
     * Widget rows wait for the plugin host and are left out for now.
     */
    fun homeRows(doc: LayoutDocument, installed: Set<String>): List<HomeRow> {
        val apps = appsRows(doc, installed).associateBy { it.id }
        return doc.rows.mapNotNull { row ->
            when (row) {
                is Row.Apps -> apps[row.id]?.let(HomeRow::Apps)
                is Row.WatchNext -> HomeRow.Content(row)
                is Row.Channel -> HomeRow.Content(row)
                is Row.Widget -> null
            }
        }
    }

    /**
     * Whether a content row may show a card from [pkg]: the app must be installed and not
     * hidden, and a kids profile only shows its approved apps.
     */
    fun contentAllowed(doc: LayoutDocument, installed: Set<String>): (String) -> Boolean {
        val hidden = doc.hiddenApps.toSet()
        val approved = doc.profile.kids?.approvedApps?.toSet()
        return { pkg -> pkg in installed && pkg !in hidden && (approved == null || pkg in approved) }
    }

    /** Apps for the All apps screen: everything installed that isn't hidden. */
    fun allApps(doc: LayoutDocument, installedInOrder: List<String>): List<String> {
        val hidden = doc.hiddenApps.toSet()
        return installedInOrder.filter { it !in hidden }
    }
}
