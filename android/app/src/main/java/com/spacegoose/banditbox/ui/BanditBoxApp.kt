package com.spacegoose.banditbox.ui

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppIndex
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.layout.DefaultLayout
import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.LayoutResolver
import com.spacegoose.banditbox.layout.LayoutStore
import com.spacegoose.banditbox.layout.ResolvedAppsRow
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.layout.Row
import com.spacegoose.banditbox.system.SystemShortcuts
import com.spacegoose.banditbox.system.TvInput
import com.spacegoose.banditbox.system.passthroughInputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the launcher needs from the activity. */
class LauncherDeps(
    val index: AppIndex,
    val visuals: AppVisuals,
    val store: LayoutStore,
    /** Start the first intent the device can handle. */
    val launch: (List<Intent>) -> Unit,
    val onAppAction: (AppEntry, AppMenuAction) -> Unit,
)

private enum class Screen { HOME, ALL_APPS, EDIT_ROWS }

/** An item being moved along its row with the D-pad. */
private class Moving(val rowId: String, val item: String, val label: String)

/**
 * Root of the launcher UI. [homePresses] goes up each time Home is pressed while the
 * launcher is already open, which returns to the main screen and closes any dialog.
 */
@Composable
fun BanditBoxApp(deps: LauncherDeps, homePresses: Int) {
    val context = LocalContext.current

    val appsFlow = remember(deps.index) { deps.index.apps() }
    val apps by appsFlow.collectAsState(initial = null)
    var doc by remember { mutableStateOf<LayoutDocument?>(null) }
    var inputs by remember { mutableStateOf(emptyList<TvInput>()) }

    LaunchedEffect(Unit) {
        doc = withContext(Dispatchers.IO) {
            deps.store.loadOrCreate(DefaultLayout.PROFILE_ID) {
                DefaultLayout.create(tvApps = deps.index.load().filter { it.isLeanback }.map { it.packageName })
            }
        }
    }
    // Every edit is written to disk in order; a burst of edits (holding ◀ in move mode) writes only the latest.
    LaunchedEffect(Unit) {
        snapshotFlow { doc }.filterNotNull().drop(1).conflate().collect { latest ->
            withContext(Dispatchers.IO) {
                try {
                    deps.store.save(latest)
                } catch (e: IOException) {
                    Log.w("BanditBox", "Couldn't save layout", e)
                }
            }
        }
    }
    LaunchedEffect(Unit) { inputs = withContext(Dispatchers.IO) { passthroughInputs(context) } }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<Moving?>(null) }

    val edit: ((LayoutDocument) -> LayoutDocument) -> Unit = { change -> doc?.let { doc = change(it) } }

    LaunchedEffect(homePresses) {
        screen = Screen.HOME
        overlay = null
        openFolderId = null
        moving = null
    }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    BackHandler(enabled = moving != null) { moving = null }

    val currentApps = apps
    val currentDoc = doc
    val byPackage = remember(currentApps) { currentApps.orEmpty().associateBy { it.packageName } }
    val rows = remember(currentDoc, byPackage) {
        if (currentDoc == null) emptyList() else LayoutResolver.appsRows(currentDoc, byPackage.keys)
    }
    val allApps = remember(currentDoc, currentApps) {
        if (currentDoc == null || currentApps == null) {
            emptyList()
        } else {
            LayoutResolver.allApps(currentDoc, currentApps.map { it.packageName }).mapNotNull(byPackage::get)
        }
    }

    val tileActions = remember(deps) {
        TileActions(
            onOpen = { deps.onAppAction(it, AppMenuAction.OPEN) },
            onAppMenu = { app, place -> overlay = Overlay.AppMenu(app, place) },
            onFolderOpen = { openFolderId = it.id },
            onFolderMenu = { folder, rowId -> overlay = Overlay.FolderMenu(folder, rowId) },
        )
    }
    val topBarActions = remember(deps) {
        TopBarActions(
            onAllApps = { screen = Screen.ALL_APPS },
            onInputs = { overlay = Overlay.Inputs },
            onNetwork = { deps.launch(SystemShortcuts.network) },
            onSettings = { overlay = Overlay.Settings },
        )
    }

    fun handleKey(event: KeyEvent): Boolean {
        val m = moving
        if (m != null) {
            return when (event.key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    if (event.type == KeyEventType.KeyDown) {
                        val direction = if (event.key == Key.DirectionLeft) -1 else 1
                        edit { LayoutEdits.moveInRow(it, m.rowId, m.item, direction, visibleIn(rows, m.rowId)) }
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> {
                    if (event.type == KeyEventType.KeyUp) moving = null
                    true
                }
                Key.DirectionUp, Key.DirectionDown -> true
                else -> false
            }
        }
        // Menu toggles All apps, like Projectivy. Remotes without a Menu key use the top bar button.
        if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
            screen = if (screen == Screen.HOME) Screen.ALL_APPS else Screen.HOME
            return true
        }
        return false
    }

    Surface(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(::handleKey)) {
        // Nothing is drawn until the local layout is read, which takes milliseconds and never touches the network.
        if (currentDoc != null) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    rows = rows,
                    apps = byPackage,
                    visuals = deps.visuals,
                    topBar = { TopBar(currentDoc.topBar, hasInputs = inputs.isNotEmpty(), actions = topBarActions) },
                    actions = tileActions,
                )
                Screen.ALL_APPS -> AllAppsScreen(stringResource(R.string.all_apps), allApps, deps.visuals, tileActions)
                Screen.EDIT_ROWS -> RowEditor(currentDoc, edit, show = { overlay = it })
            }

            openFolderId?.let { id ->
                val folder = rows.asSequence().flatMap { it.items }.filterIsInstance<ResolvedItem.FolderItem>()
                    .firstOrNull { it.id == id }
                if (folder == null) {
                    LaunchedEffect(id) { openFolderId = null } // emptied or deleted while open
                } else {
                    FolderDialog(
                        folderId = folder.id,
                        name = folder.name,
                        apps = folder.packages.mapNotNull(byPackage::get),
                        visuals = deps.visuals,
                        actions = tileActions,
                        onDismiss = { openFolderId = null },
                    )
                }
            }

            overlay?.let { current ->
                OverlayHost(
                    overlay = current,
                    ctx = OverlayContext(
                        doc = currentDoc,
                        apps = byPackage,
                        inputs = inputs,
                        deps = deps,
                        show = { overlay = it },
                        edit = edit,
                        startMove = { rowId, item, label ->
                            openFolderId = null
                            screen = Screen.HOME
                            moving = Moving(rowId, item, label)
                        },
                        openFolder = { openFolderId = it },
                        openEditRows = { screen = Screen.EDIT_ROWS },
                    ),
                )
            }

            moving?.let { m ->
                Text(
                    text = stringResource(R.string.moving_hint, m.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun RowEditor(doc: LayoutDocument, edit: ((LayoutDocument) -> LayoutDocument) -> Unit, show: (Overlay?) -> Unit) {
    val resources = LocalContext.current.resources
    val nameTitle = stringResource(R.string.row_name)
    val untitled = stringResource(R.string.row_untitled)
    val deleteLabel = stringResource(R.string.delete)
    RowEditorScreen(
        rows = doc.rows.filterIsInstance<Row.Apps>(),
        actions = RowEditorActions(
            onAddRow = { show(Overlay.TextInput(nameTitle, "") { name -> edit { LayoutEdits.addRow(it, name).first } }) },
            onRename = { row ->
                show(Overlay.TextInput(nameTitle, row.title.orEmpty()) { name -> edit { LayoutEdits.renameRow(it, row.id, name) } })
            },
            onTile = { row, tile -> edit { LayoutEdits.setTile(it, row.id, tile) } },
            onMove = { row, direction -> edit { LayoutEdits.moveRow(it, row.id, direction) } },
            onDelete = { row ->
                val title = resources.getString(R.string.delete_row_confirm, row.title ?: untitled)
                show(Overlay.Confirm(title, deleteLabel) { edit { LayoutEdits.removeRow(it, row.id) } })
            },
        ),
    )
}

/** True for the row items that are on screen now, so a move never "disappears" behind a hidden app. */
private fun visibleIn(rows: List<ResolvedAppsRow>, rowId: String): (String) -> Boolean {
    val visible = rows.firstOrNull { it.id == rowId }?.items?.map { it.rawRef() }?.toSet().orEmpty()
    return { it in visible }
}
