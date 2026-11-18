package com.spacegoose.banditbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.menuFor
import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.PinHash
import com.spacegoose.banditbox.layout.Press
import com.spacegoose.banditbox.layout.ProfileSummary
import com.spacegoose.banditbox.layout.Remap
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.layout.Row
import com.spacegoose.banditbox.system.BackupSource
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

    /** Ask for an existing PIN; [onSuccess] runs only when it matches [pinHash]. */
    class PinEntry(val title: String, val pinHash: String, val onSuccess: () -> Unit) : Overlay

    /** Ask for a new PIN twice; [onDone] gets the PIN's hash. */
    class NewPin(val onDone: (String) -> Unit) : Overlay
    data object Settings : Overlay
    data object ProfileSettings : Overlay
    data object Inputs : Overlay
    data object HiddenApps : Overlay

    // Remote button shortcuts, in the order the user goes through them.
    data object Buttons : Overlay
    data class ButtonRule(val rule: Remap) : Overlay
    data object PickKey : Overlay
    data object DetectKey : Overlay
    data class PickPress(val key: String) : Overlay
    data class PickAction(val key: String, val press: Press) : Overlay
    data class PickApp(val key: String, val press: Press) : Overlay
    data class PickInput(val key: String, val press: Press) : Overlay

    data object Backup : Overlay
    class PickBackup(val sources: List<BackupSource>) : Overlay
    class Message(val title: String, val message: String) : Overlay
}

/** Backup and restore, run by the launcher because restoring replaces every profile. */
class BackupActions(
    val hasFilePicker: Boolean,
    val backUpNow: () -> Unit,
    val saveToFile: () -> Unit,
    val showRestore: () -> Unit,
    val chooseFile: () -> Unit,
    val restore: (BackupSource) -> Unit,
)

/** What the profile dialogs can ask the launcher to do. */
class ProfileActions(
    val showPicker: () -> Unit,
    val add: (name: String) -> Unit,
    val deleteCurrent: () -> Unit,
    val setAutoLogin: (Boolean) -> Unit,
)

/** Everything the dialogs can read or do. */
class OverlayContext(
    val doc: LayoutDocument,
    val apps: Map<String, AppEntry>,
    val inputs: List<TvInput>,
    val profiles: List<ProfileSummary>,
    val launchOnBoot: String?,
    val deps: LauncherDeps,
    val show: (Overlay?) -> Unit,
    val edit: ((LayoutDocument) -> LayoutDocument) -> Unit,
    val startMove: (rowId: String, item: String, label: String) -> Unit,
    val openFolder: (String) -> Unit,
    val openEditRows: () -> Unit,
    val openHomeSetup: () -> Unit,
    val setLaunchOnBoot: (String?) -> Unit,
    val profile: ProfileActions,
    val backup: BackupActions,
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
        is Overlay.PinEntry -> PinDialog(
            title = overlay.title,
            onPin = { pin ->
                val ok = PinHash.verify(pin, overlay.pinHash)
                if (ok) {
                    close()
                    overlay.onSuccess()
                }
                ok
            },
            onDismiss = close,
        )
        is Overlay.NewPin -> NewPinDialog(onDone = { hash -> close(); overlay.onDone(hash) }, onDismiss = close)
        Overlay.Settings -> SettingsDialog(ctx)
        Overlay.ProfileSettings -> ProfileSettingsDialog(ctx)
        Overlay.Inputs -> ChoiceDialog(
            title = stringResource(R.string.inputs),
            choices = ctx.inputs.map { input -> Choice(input.label) { close(); ctx.deps.launch(listOf(input.intent())) } },
            onDismiss = close,
        )
        Overlay.HiddenApps -> HiddenAppsDialog(ctx)
        Overlay.Buttons -> ButtonsDialog(ctx)
        is Overlay.ButtonRule -> ButtonRuleDialog(overlay.rule, ctx)
        Overlay.PickKey -> PickKeyDialog(ctx)
        Overlay.DetectKey -> DetectKeyDialog(ctx)
        is Overlay.PickPress -> PickPressDialog(overlay.key, ctx)
        is Overlay.PickAction -> PickActionDialog(overlay.key, overlay.press, ctx)
        is Overlay.PickApp -> PickAppDialog(overlay.key, overlay.press, ctx)
        is Overlay.PickInput -> PickInputDialog(overlay.key, overlay.press, ctx)
        Overlay.Backup -> BackupDialog(ctx)
        is Overlay.PickBackup -> PickBackupDialog(overlay.sources, ctx)
        is Overlay.Message -> ChoiceDialog(
            title = overlay.title,
            choices = listOf(Choice(stringResource(R.string.ok)) { close() }),
            onDismiss = close,
            message = overlay.message,
        )
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
    val isBootApp = ctx.launchOnBoot == pkg
    val bootLabel = stringResource(if (isBootApp) R.string.launch_on_boot_off else R.string.launch_on_boot_on)
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
        add(Choice(bootLabel) { close(); ctx.setLaunchOnBoot(if (isBootApp) null else pkg) })
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
            Choice(stringResource(R.string.profile_settings, ctx.doc.profile.name)) { ctx.show(Overlay.ProfileSettings) },
            Choice(stringResource(R.string.edit_rows)) { close(); ctx.openEditRows() },
            Choice(stringResource(R.string.home_setup_title)) { close(); ctx.openHomeSetup() },
            Choice(stringResource(R.string.buttons)) { ctx.show(Overlay.Buttons) },
            Choice(stringResource(R.string.backup)) { ctx.show(Overlay.Backup) },
            Choice(stringResource(R.string.hidden_apps)) { ctx.show(Overlay.HiddenApps) },
            Choice(stringResource(R.string.android_settings)) { close(); ctx.deps.launch(SystemShortcuts.androidSettings) },
            Choice(stringResource(R.string.network_settings)) { close(); ctx.deps.launch(SystemShortcuts.network) },
            Choice(stringResource(R.string.install_unknown_apps)) { close(); ctx.deps.launch(SystemShortcuts.installUnknownApps) },
        ),
        onDismiss = close,
    )
}

@Composable
private fun ProfileSettingsDialog(ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val profile = ctx.doc.profile
    val pin = profile.pin
    val nameTitle = stringResource(R.string.profile_name)
    val currentPinTitle = stringResource(R.string.pin_current)
    val deleteLabel = stringResource(R.string.delete_profile)
    val deleteTitle = stringResource(R.string.delete_profile_confirm, profile.name)
    val newProfileTitle = stringResource(R.string.new_profile_name)
    val setNewPin = { ctx.show(Overlay.NewPin { hash -> ctx.edit { LayoutEdits.setPin(it, hash) } }) }

    val choices = buildList {
        add(Choice(stringResource(R.string.switch_profile)) { close(); ctx.profile.showPicker() })
        add(
            Choice(stringResource(R.string.rename_profile)) {
                ctx.show(Overlay.TextInput(nameTitle, profile.name) { name -> ctx.edit { LayoutEdits.renameProfile(it, name) } })
            },
        )
        if (pin == null) {
            add(Choice(stringResource(R.string.pin_set)) { setNewPin() })
        } else {
            add(Choice(stringResource(R.string.pin_change)) { ctx.show(Overlay.PinEntry(currentPinTitle, pin) { setNewPin() }) })
            add(
                Choice(stringResource(R.string.pin_remove)) {
                    ctx.show(Overlay.PinEntry(currentPinTitle, pin) { ctx.edit { LayoutEdits.setPin(it, null) } })
                },
            )
        }
        add(
            Choice(stringResource(if (profile.autoLogin) R.string.auto_login_on else R.string.auto_login_off)) {
                ctx.profile.setAutoLogin(!profile.autoLogin)
            },
        )
        add(Choice(stringResource(R.string.add_profile)) { ctx.show(Overlay.TextInput(newProfileTitle, "") { ctx.profile.add(it) }) })
        if (ctx.profiles.size > 1) {
            add(Choice(deleteLabel) { ctx.show(Overlay.Confirm(deleteTitle, deleteLabel) { ctx.profile.deleteCurrent() }) })
        }
    }
    ChoiceDialog(title = profile.name, choices = choices, onDismiss = close)
}

/** New PIN, then the same PIN again. A mismatch starts over. */
@Composable
private fun NewPinDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    val enterTitle = stringResource(R.string.pin_new)
    val confirmTitle = stringResource(R.string.pin_confirm)
    PinDialog(
        title = if (first == null) enterTitle else confirmTitle,
        onPin = { pin ->
            val firstPin = first
            when {
                firstPin == null -> {
                    first = pin
                    true
                }
                firstPin == pin -> {
                    onDone(PinHash.hash(pin))
                    true
                }
                else -> {
                    first = null
                    false
                }
            }
        },
        onDismiss = onDismiss,
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

@Composable
private fun BackupDialog(ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val choices = buildList {
        add(Choice(stringResource(R.string.backup_now)) { close(); ctx.backup.backUpNow() })
        if (ctx.backup.hasFilePicker) add(Choice(stringResource(R.string.backup_to_file)) { close(); ctx.backup.saveToFile() })
        add(Choice(stringResource(R.string.backup_restore)) { close(); ctx.backup.showRestore() })
    }
    ChoiceDialog(title = stringResource(R.string.backup), choices = choices, onDismiss = close)
}

/** Backups found on the TV, newest first, plus the file picker where there is one. */
@Composable
private fun PickBackupDialog(sources: List<BackupSource>, ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val choices = buildList {
        sources.forEach { source -> add(Choice(source.name) { close(); ctx.backup.restore(source) }) }
        if (ctx.backup.hasFilePicker) add(Choice(stringResource(R.string.backup_choose_file)) { close(); ctx.backup.chooseFile() })
        if (isEmpty()) add(Choice(stringResource(R.string.ok)) { close() })
    }
    ChoiceDialog(
        title = stringResource(R.string.backup_pick),
        choices = choices,
        onDismiss = { ctx.show(Overlay.Backup) },
        message = if (sources.isEmpty()) stringResource(R.string.backup_none) else null,
    )
}
