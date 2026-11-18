package com.spacegoose.banditbox.ui

import android.content.Intent
import android.util.Log
import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.spacegoose.banditbox.BuildConfig
import com.spacegoose.banditbox.DeviceSettings
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.RemapActions
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppIndex
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.layout.Backup
import com.spacegoose.banditbox.layout.BackupFile
import com.spacegoose.banditbox.layout.DefaultLayout
import com.spacegoose.banditbox.layout.InvalidBackupException
import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.LayoutResolver
import com.spacegoose.banditbox.layout.LayoutStore
import com.spacegoose.banditbox.layout.Profile
import com.spacegoose.banditbox.layout.ProfileSummary
import com.spacegoose.banditbox.layout.Remap
import com.spacegoose.banditbox.layout.Profiles
import com.spacegoose.banditbox.layout.ResolvedAppsRow
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.layout.Row
import com.spacegoose.banditbox.layout.Startup
import com.spacegoose.banditbox.layout.summary
import com.spacegoose.banditbox.system.BackupFiles
import com.spacegoose.banditbox.system.BackupSource
import com.spacegoose.banditbox.system.SystemShortcuts
import com.spacegoose.banditbox.system.TvInput
import com.spacegoose.banditbox.system.passthroughInputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What the launcher needs from the activity. */
class LauncherDeps(
    val index: AppIndex,
    val visuals: AppVisuals,
    val store: LayoutStore,
    val settings: DeviceSettings,
    /** True when the TV has just booted: auto sign-in or the profile picker applies. */
    val freshBoot: Boolean,
    /** Start the first intent the device can handle. */
    val launch: (List<Intent>) -> Unit,
    val onAppAction: (AppEntry, AppMenuAction) -> Unit,
    /** The open profile's remote button rules changed; the BanditBox service applies them. */
    val onRemapsChanged: (List<Remap>) -> Unit,
    /** Starts the launcher over, reading every profile from disk again (after a restore). */
    val restart: () -> Unit,
)

/** A remote button shortcut asked for a screen. [id] tells two requests for the same screen apart. */
data class ShowRequest(val screen: String, val id: Int)

private enum class Screen { HOME, ALL_APPS, EDIT_ROWS, PICK_PROFILE, HOME_SETUP }

/** An item being moved along its row with the D-pad. */
private class Moving(val rowId: String, val item: String, val label: String)

/**
 * Root of the launcher UI. [homePresses] goes up each time Home is pressed while the
 * launcher is already open, which returns to the main screen and closes any dialog.
 * [request] is the latest screen a remote button shortcut asked for.
 */
@Composable
fun BanditBoxApp(deps: LauncherDeps, homePresses: Int, request: ShowRequest?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storeLock = remember { Mutex() }

    val appsFlow = remember(deps.index) { deps.index.apps() }
    val apps by appsFlow.collectAsState(initial = null)
    var inputs by remember { mutableStateOf(emptyList<TvInput>()) }

    var profiles by remember { mutableStateOf(emptyList<ProfileSummary>()) }
    /** The profile whose layout is loaded and shown; null until one is chosen. */
    var profileId by remember { mutableStateOf<String?>(null) }
    var doc by remember { mutableStateOf<LayoutDocument?>(null) }
    val deletedIds = remember { ConcurrentHashMap.newKeySet<String>() }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<Moving?>(null) }
    var launchOnBoot by remember { mutableStateOf(deps.settings.launchOnBoot) }

    suspend fun refreshProfiles() {
        profiles = withContext(Dispatchers.IO) { storeLock.withLock { Profiles.summaries(deps.store) } }
    }

    fun tvApps() = deps.index.load().filter { it.isLeanback }.map { it.packageName }

    fun open(id: String) {
        profileId = id
        deps.settings.currentProfileId = id
        overlay = null
        openFolderId = null
        moving = null
        screen = Screen.HOME
    }

    // Startup: make sure a profile exists, then auto sign-in, the picker, or the last profile.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            storeLock.withLock {
                if (deps.store.profileIds().isEmpty()) {
                    deps.store.loadOrCreate(DefaultLayout.PROFILE_ID) { DefaultLayout.create(tvApps()) }
                }
            }
        }
        refreshProfiles()
        when (val start = Profiles.startup(profiles, deps.settings.currentProfileId, deps.freshBoot)) {
            is Startup.Open -> open(start.profileId)
            Startup.Pick -> screen = Screen.PICK_PROFILE
        }
    }
    // Load the chosen profile's layout from disk. Never waits on the network.
    LaunchedEffect(profileId) {
        val id = profileId ?: return@LaunchedEffect
        doc = null
        doc = withContext(Dispatchers.IO) {
            storeLock.withLock {
                deps.store.load(id) ?: deps.store.loadOrCreate(DefaultLayout.PROFILE_ID) { DefaultLayout.create(tvApps()) }
            }
        }
    }
    // Every edit is written to disk in order; a burst of edits (holding ◀ in move mode) writes only the latest.
    LaunchedEffect(Unit) {
        snapshotFlow { doc }.filterNotNull().drop(1).conflate().collect { latest ->
            withContext(Dispatchers.IO) {
                storeLock.withLock {
                    if (latest.profileId in deletedIds) return@withLock
                    try {
                        deps.store.save(latest)
                    } catch (e: IOException) {
                        Log.w("BanditBox", "Couldn't save layout", e)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { inputs = withContext(Dispatchers.IO) { passthroughInputs(context) } }

    val edit: ((LayoutDocument) -> LayoutDocument) -> Unit = { change -> doc?.let { doc = change(it) } }

    /** Opening a profile other than the current one asks for its PIN, if it has one. */
    fun pick(profile: ProfileSummary) {
        val hash = profile.pinHash
        val alreadyOpen = profile.id == profileId && screen != Screen.PICK_PROFILE
        if (hash == null || alreadyOpen) {
            open(profile.id)
        } else {
            overlay = Overlay.PinEntry(context.getString(R.string.pin_enter, profile.name), hash) { open(profile.id) }
        }
    }

    val profileActions = ProfileActions(
        showPicker = { screen = Screen.PICK_PROFILE },
        add = { name ->
            scope.launch {
                val created = withContext(Dispatchers.IO) { storeLock.withLock { Profiles.create(deps.store, name, tvApps()) } }
                refreshProfiles()
                open(created.profileId)
            }
        },
        deleteCurrent = {
            val gone = profileId
            if (gone != null && profiles.size > 1) {
                deletedIds += gone
                profileId = null
                doc = null
                screen = Screen.PICK_PROFILE
                scope.launch {
                    withContext(Dispatchers.IO) { storeLock.withLock { deps.store.delete(gone) } }
                    refreshProfiles()
                }
            }
        },
        setAutoLogin = { on ->
            edit { LayoutEdits.setAutoLogin(it, on) }
            val id = profileId
            if (on && id != null) {
                scope.launch {
                    withContext(Dispatchers.IO) { storeLock.withLock { Profiles.clearAutoLoginExcept(deps.store, id) } }
                    refreshProfiles()
                }
            }
        },
    )

    // ---- Backup and restore ----
    val backupFiles = remember { BackupFiles(context) }
    var pickedFileName by remember { mutableStateOf("") }

    fun message(title: String, text: String) {
        overlay = Overlay.Message(title, text)
    }

    /** Every profile as one backup, including an edit that hasn't reached the disk yet. Call on Dispatchers.IO. */
    suspend fun backupText(): String = storeLock.withLock {
        doc?.let { if (it.profileId !in deletedIds) deps.store.save(it) }
        Backup.encode(Backup.create(deps.store, Instant.now().toString(), BuildConfig.VERSION_NAME))
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFiles.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    backupFiles.writeTo(uri, backupText())
                    true
                } catch (e: IOException) {
                    Log.w("BanditBox", "Couldn't write backup", e)
                    false
                }
            }
            if (ok) {
                message(
                    context.getString(R.string.backup_done_title),
                    context.getString(R.string.backup_done, pickedFileName, context.getString(R.string.backup_place_file)),
                )
            } else {
                message(context.getString(R.string.backup), context.getString(R.string.backup_failed))
            }
        }
    }

    fun restoreNow(backup: BackupFile) {
        scope.launch {
            // null: restored. Otherwise the reason, and whether profiles on disk already changed.
            val failure: Pair<String, Boolean>? = withContext(Dispatchers.IO) {
                storeLock.withLock {
                    val savedCopy = try {
                        val before = Backup.encode(Backup.create(deps.store, Instant.now().toString(), BuildConfig.VERSION_NAME))
                        backupFiles.save(backupFiles.newName("before-restore"), before, alsoToDownloads = false) != BackupFiles.Saved.NOWHERE
                    } catch (e: IOException) {
                        Log.w("BanditBox", "Couldn't save a copy before restoring", e)
                        false
                    }
                    if (!savedCopy) return@withLock context.getString(R.string.backup_failed) to false
                    // No save from before the restore may land after it.
                    deletedIds += deps.store.profileIds()
                    deletedIds += backup.layouts.map { it.profileId }
                    try {
                        Backup.restore(deps.store, backup)
                        null
                    } catch (e: IOException) {
                        Log.w("BanditBox", "Restore failed", e)
                        (e.message ?: e.toString()) to true
                    }
                }
            }
            if (failure == null || failure.second) {
                deps.restart() // start over from what is on disk now
            } else {
                message(context.getString(R.string.backup), context.getString(R.string.backup_restore_failed, failure.first))
            }
        }
    }

    fun askToRestore(source: BackupSource) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(Backup.decode(source.read()))
                } catch (e: InvalidBackupException) {
                    Result.failure<BackupFile>(e)
                } catch (e: IOException) {
                    Result.failure<BackupFile>(e)
                }
            }
            result.fold(
                onSuccess = { backup ->
                    val title = context.getString(R.string.backup_restore_confirm, backup.layouts.size, backup.createdAt.take(10))
                    overlay = Overlay.Confirm(title, context.getString(R.string.backup_restore_action)) { restoreNow(backup) }
                },
                onFailure = { e ->
                    message(context.getString(R.string.backup), context.getString(R.string.backup_restore_failed, e.message ?: e.toString()))
                },
            )
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askToRestore(BackupSource(uri.lastPathSegment.orEmpty()) { backupFiles.readFrom(uri) })
    }

    val backupActions = BackupActions(
        hasFilePicker = backupFiles.hasFilePicker,
        backUpNow = {
            scope.launch {
                val name = backupFiles.newName()
                val saved = withContext(Dispatchers.IO) {
                    try {
                        backupFiles.save(name, backupText())
                    } catch (e: IOException) {
                        Log.w("BanditBox", "Couldn't make backup", e)
                        BackupFiles.Saved.NOWHERE
                    }
                }
                val place = when (saved) {
                    BackupFiles.Saved.DOWNLOADS_AND_APP, BackupFiles.Saved.DOWNLOADS_ONLY -> R.string.backup_place_downloads
                    BackupFiles.Saved.APP_ONLY -> R.string.backup_place_app
                    BackupFiles.Saved.NOWHERE -> null
                }
                if (place == null) {
                    message(context.getString(R.string.backup), context.getString(R.string.backup_failed))
                } else {
                    message(context.getString(R.string.backup_done_title), context.getString(R.string.backup_done, name, context.getString(place)))
                }
            }
        },
        saveToFile = {
            pickedFileName = backupFiles.newName()
            try {
                saveFile.launch(pickedFileName)
            } catch (_: ActivityNotFoundException) {
                message(context.getString(R.string.backup), context.getString(R.string.backup_failed))
            }
        },
        showRestore = {
            scope.launch {
                val sources = withContext(Dispatchers.IO) { backupFiles.list() }
                overlay = Overlay.PickBackup(sources)
            }
        },
        chooseFile = {
            try {
                openFile.launch(arrayOf(BackupFiles.MIME, "text/plain", "application/octet-stream"))
            } catch (_: ActivityNotFoundException) {
                message(context.getString(R.string.backup), context.getString(R.string.cannot_open))
            }
        },
        restore = ::askToRestore,
    )

    LaunchedEffect(homePresses) {
        // Home never skips the picker when no profile has been opened yet (boot with several profiles).
        screen = if (profileId == null && profiles.size > 1) Screen.PICK_PROFILE else Screen.HOME
        overlay = null
        openFolderId = null
        moving = null
    }
    // A shortcut's screen opens once a profile is open (after the picker, at boot).
    var handledRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(request, profileId) {
        val show = request ?: return@LaunchedEffect
        if (profileId == null || show.id <= handledRequest) return@LaunchedEffect
        handledRequest = show.id
        overlay = null
        openFolderId = null
        moving = null
        when (show.screen) {
            RemapActions.SHOW_ALL_APPS -> screen = Screen.ALL_APPS
            RemapActions.SHOW_SETTINGS -> {
                screen = Screen.HOME
                overlay = Overlay.Settings
            }
        }
    }
    // The BanditBox service follows the open profile's button rules.
    val remaps = doc?.remap
    LaunchedEffect(remaps) { remaps?.let(deps.onRemapsChanged) }

    BackHandler(enabled = screen != Screen.HOME && profileId != null) { screen = Screen.HOME }
    BackHandler(enabled = moving != null) { moving = null }

    val currentApps = apps
    val currentDoc = doc
    // The picker and settings show the open profile's live name and PIN state.
    val profilesView = remember(profiles, currentDoc) {
        profiles.map { if (currentDoc != null && it.id == currentDoc.profileId) currentDoc.summary() else it }
    }
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
            onProfile = { screen = Screen.PICK_PROFILE },
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
        if (event.key == Key.Menu && event.type == KeyEventType.KeyUp && profileId != null) {
            screen = if (screen == Screen.HOME) Screen.ALL_APPS else Screen.HOME
            return true
        }
        return false
    }

    Surface(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(::handleKey)) {
        if (screen == Screen.PICK_PROFILE) {
            ProfilePickerScreen(
                profiles = profilesView,
                currentId = profileId ?: deps.settings.currentProfileId,
                onPick = ::pick,
                onAdd = { overlay = Overlay.TextInput(context.getString(R.string.new_profile_name), "") { profileActions.add(it) } },
            )
        } else if (currentDoc != null) {
            // Nothing is drawn until the local layout is read, which takes milliseconds and never touches the network.
            when (screen) {
                Screen.HOME, Screen.PICK_PROFILE -> HomeScreen(
                    rows = rows,
                    apps = byPackage,
                    visuals = deps.visuals,
                    topBar = {
                        TopBar(
                            items = currentDoc.topBar,
                            profileName = currentDoc.profile.name,
                            hasInputs = inputs.isNotEmpty(),
                            actions = topBarActions,
                        )
                    },
                    actions = tileActions,
                )
                Screen.ALL_APPS -> AllAppsScreen(stringResource(R.string.all_apps), allApps, deps.visuals, tileActions)
                Screen.EDIT_ROWS -> RowEditor(currentDoc, edit, show = { overlay = it })
                Screen.HOME_SETUP -> HomeSetupScreen(launch = deps.launch)
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

        // Dialogs. Most need the open profile's layout; PIN entry and naming also work from the picker.
        overlay?.let { current ->
            val needsDoc = current !is Overlay.PinEntry && current !is Overlay.TextInput
            val shownDoc = currentDoc
            if (shownDoc != null || !needsDoc) {
                OverlayHost(
                    overlay = current,
                    ctx = OverlayContext(
                        doc = shownDoc ?: LayoutDocument(profileId = "p_none", profile = Profile("")),
                        apps = byPackage,
                        inputs = inputs,
                        profiles = profilesView,
                        launchOnBoot = launchOnBoot,
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
                        openHomeSetup = { screen = Screen.HOME_SETUP },
                        setLaunchOnBoot = { pkg ->
                            launchOnBoot = pkg
                            deps.settings.launchOnBoot = pkg
                        },
                        profile = profileActions,
                        backup = backupActions,
                    ),
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
