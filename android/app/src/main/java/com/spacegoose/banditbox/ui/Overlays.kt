package com.spacegoose.banditbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.menuFor
import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.layout.Row
import com.spacegoose.banditbox.system.SystemShortcuts
import com.spacegoose.banditbox.system.TvInput

/** The one dialog shown over the launcher at a time. */
sealed interface Overlay {
    data class AppMenu(val app: AppEntry, val place: Place) : Overlay
    data class FolderMenu(val folder: ResolvedItem.FolderItem, val rowId: String) : Overlay
    data class PickRow(val app: AppEntry) : Overlay
    data class PickFolder(val app: AppEntry, val fromRowId: String?) : Overlay
    class TextInput(val title: String, val initial: String, val onDone: (String) -> Unit) : Overlay
    class Confirm(val title: String, val confirmLabel: String, val onConfirm: () -> Unit) : Overlay
    data object Settings : Overlay
    data object Inputs : Overlay
    data object HiddenApps : Overlay
}

/** Everything the dialogs can read or do. */
class OverlayContext(
    val doc: LayoutDocument,
    val apps: Map<String, AppEntry>,
    val inputs: List<TvInput>,
    val deps: LauncherDeps,
    val show: (Overlay?) -> Unit,
    val edit: ((LayoutDocument) -> LayoutDocument) -> Unit,
    val startMove: (rowId: String, item: String, label: String) -> Unit,
    val openFolder: (String) -> Unit,
    val openEditRows: () -> Unit,
)

@Composable
fun OverlayHost(overlay: Overlay, ctx: OverlayContext) {
    val close = { ctx.show(null) }
    when (overlay) {
        is Overlay.AppMenu -> AppMenuDialog(overlay, ctx)
        is Overlay.FolderMenu -> FolderMenuDialog(overlay, ctx)
        is Overlay.PickRow -> PickRowDialog(overlay.app, ctx)
        is Overlay.PickFolder -> PickFolderDialog(overlay.app, overlay.fromRowId, ctx)
        is Overlay.TextInput -> TextInputDialog(
            title = overlay.title,
            initial = overlay.initial,
            onDone = { overlay.onDone(it); close() },
            onDismiss = close,
        )
        is Overlay.Confirm -> ChoiceDialog(
            title = overlay.title,
            choices = listOf(
                Choice(overlay.confirmLabel) { overlay.onConfirm(); close() },
                Choice(stringResource(R.string.cancel)) { close() },
            ),
            onDismiss = close,
        )
        Overlay.Settings -> SettingsDialog(ctx)
        Overlay.Inputs -> ChoiceDialog(
            title = stringResource(R.string.inputs),
            choices = ctx.inputs.map { input -> Choice(input.label) { close(); ctx.deps.launch(listOf(input.intent())) } },
            onDismiss = close,
        )
        Overlay.HiddenApps -> HiddenAppsDialog(ctx)
    }
}

@Composable
private fun AppMenuDialog(o: Overlay.AppMenu, ctx: OverlayContext) {
    val app = o.app
    val pkg = app.packageName
    val close = { ctx.show(null) }
    val open = stringResource(R.string.action_open)
    val move = stringResource(R.string.action_move)
    val removeFromRow = stringResource(R.string.remove_from_row)
    val removeFromFolder = stringResource(R.string.remove_from_folder)
    val addToRow = stringResource(R.string.add_to_row)
    val addToFolder = stringResource(R.string.add_to_folder)
    val hide = stringResource(R.string.hide_app)
    val system = menuFor(app).filter { it != AppMenuAction.OPEN }.map { it to stringResource(it.labelRes()) }

    val choices = buildList {
        add(Choice(open) { close(); ctx.deps.onAppAction(app, AppMenuAction.OPEN) })
        when (val place = o.place) {
            is Place.InRow -> {
                add(Choice(move) { close(); ctx.startMove(place.rowId, pkg, app.label) })
                add(Choice(addToFolder) { ctx.show(Overlay.PickFolder(app, place.rowId)) })
                add(Choice(addToRow) { ctx.show(Overlay.PickRow(app)) })
                add(Choice(removeFromRow) { close(); ctx.edit { LayoutEdits.removeFromRow(it, place.rowId, pkg) } })
            }
            Place.AllApps -> {
                add(Choice(addToRow) { ctx.show(Overlay.PickRow(app)) })
                add(Choice(addToFolder) { ctx.show(Overlay.PickFolder(app, null)) })
            }
            is Place.InFolder -> {
                add(Choice(removeFromFolder) { close(); ctx.edit { LayoutEdits.removeFromFolder(it, place.folderId, pkg) } })
            }
        }
        add(Choice(hide) { close(); ctx.edit { LayoutEdits.hide(it, pkg) } })
        system.forEach { (action, label) -> add(Choice(label) { close(); ctx.deps.onAppAction(app, action) }) }
    }
    ChoiceDialog(title = app.label, choices = choices, onDismiss = close)
}

@Composable
private fun FolderMenuDialog(o: Overlay.FolderMenu, ctx: OverlayContext) {
    val folder = o.folder
    val close = { ctx.show(null) }
    val renameTitle = stringResource(R.string.folder_name)
    val deleteTitle = stringResource(R.string.delete_folder_confirm, folder.name)
    val deleteLabel = stringResource(R.string.delete_folder)
    ChoiceDialog(
        title = folder.name,
        choices = listOf(
            Choice(stringResource(R.string.action_open)) { close(); ctx.openFolder(folder.id) },
            Choice(stringResource(R.string.action_move)) { close(); ctx.startMove(o.rowId, folder.rawRef(), folder.name) },
            Choice(stringResource(R.string.rename)) {
                ctx.show(Overlay.TextInput(renameTitle, folder.name) { name -> ctx.edit { LayoutEdits.renameFolder(it, folder.id, name) } })
            },
            Choice(stringResource(R.string.remove_from_row)) {
                close()
                ctx.edit { LayoutEdits.removeFromRow(it, o.rowId, folder.rawRef()) }
            },
            Choice(deleteLabel) {
                ctx.show(Overlay.Confirm(deleteTitle, deleteLabel) { ctx.edit { LayoutEdits.deleteFolder(it, folder.id) } })
            },
        ),
        onDismiss = close,
    )
}

@Composable
private fun PickRowDialog(app: AppEntry, ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val untitled = stringResource(R.string.row_untitled)
    val newRowTitle = stringResource(R.string.row_name)
    val rows = ctx.doc.rows.filterIsInstance<Row.Apps>()
    val choices = rows.map { row ->
        Choice(row.title ?: untitled) { close(); ctx.edit { LayoutEdits.addToRow(it, row.id, app.packageName) } }
    } + Choice(stringResource(R.string.new_row)) {
        ctx.show(
            Overlay.TextInput(newRowTitle, "") { name ->
                ctx.edit { doc ->
                    val (withRow, rowId) = LayoutEdits.addRow(doc, name)
                    LayoutEdits.addToRow(withRow, rowId, app.packageName)
                }
            },
        )
    }
    ChoiceDialog(title = stringResource(R.string.add_to_row), choices = choices, onDismiss = close)
}

@Composable
private fun PickFolderDialog(app: AppEntry, fromRowId: String?, ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val newFolderTitle = stringResource(R.string.folder_name)
    // A folder made from All apps goes in the first row, so it is visible on the home screen.
    val targetRow = fromRowId ?: ctx.doc.rows.firstOrNull { it is Row.Apps }?.id
    val choices = ctx.doc.folders.map { folder ->
        Choice(folder.name) { close(); ctx.edit { LayoutEdits.addToFolder(it, folder.id, app.packageName, fromRowId) } }
    } + Choice(stringResource(R.string.new_folder)) {
        ctx.show(
            Overlay.TextInput(newFolderTitle, "") { name ->
                ctx.edit { LayoutEdits.createFolder(it, name, app.packageName, targetRow).first }
            },
        )
    }
    ChoiceDialog(title = stringResource(R.string.add_to_folder), choices = choices, onDismiss = close)
}

@Composable
private fun SettingsDialog(ctx: OverlayContext) {
    val close = { ctx.show(null) }
    ChoiceDialog(
        title = stringResource(R.string.settings),
        choices = listOf(
            Choice(stringResource(R.string.edit_rows)) { close(); ctx.openEditRows() },
            Choice(stringResource(R.string.hidden_apps)) { ctx.show(Overlay.HiddenApps) },
            Choice(stringResource(R.string.android_settings)) { close(); ctx.deps.launch(SystemShortcuts.androidSettings) },
            Choice(stringResource(R.string.network_settings)) { close(); ctx.deps.launch(SystemShortcuts.network) },
            Choice(stringResource(R.string.install_unknown_apps)) { close(); ctx.deps.launch(SystemShortcuts.installUnknownApps) },
        ),
        onDismiss = close,
    )
}

/** Hidden apps, each one tap from coming back. */
@Composable
private fun HiddenAppsDialog(ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val hidden = ctx.doc.hiddenApps
    val choices = if (hidden.isEmpty()) {
        listOf(Choice(stringResource(R.string.no_hidden_apps)) { close() })
    } else {
        hidden.map { pkg ->
            Choice(ctx.apps[pkg]?.label ?: pkg) { ctx.edit { LayoutEdits.unhide(it, pkg) } }
        }
    }
    ChoiceDialog(title = stringResource(R.string.hidden_apps_title), choices = choices, onDismiss = close)
}
