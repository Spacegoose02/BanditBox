package com.spacegoose.banditbox.ui

import android.content.Intent
import android.util.Log
import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.ui.graphics.Color
import android.os.SystemClock
import androidx.tv.material3.Text
import com.spacegoose.banditbox.BanditBoxApplication
import com.spacegoose.banditbox.BuildConfig
import com.spacegoose.banditbox.DeviceSettings
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.RemapActions
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppIndex
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.content.ContentSelect
import com.spacegoose.banditbox.content.ProgramLaunch
import com.spacegoose.banditbox.content.TvProviderReader
import com.spacegoose.banditbox.content.TvSnapshot
import com.spacegoose.banditbox.guard.Guard
import com.spacegoose.banditbox.guard.GuardStatus
import com.spacegoose.banditbox.layout.Kids
import com.spacegoose.banditbox.layout.KidsRules
import com.spacegoose.banditbox.layout.KidsStatus
import com.spacegoose.banditbox.layout.Backup
import com.spacegoose.banditbox.layout.HomeRow
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
import com.spacegoose.banditbox.layout.ScreensaverType
import com.spacegoose.banditbox.layout.Startup
import com.spacegoose.banditbox.layout.ThemeResolver
import com.spacegoose.banditbox.layout.WallpaperRef
import com.spacegoose.banditbox.system.LoadedPicture
import com.spacegoose.banditbox.system.WallpaperFiles
import com.spacegoose.banditbox.ui.theme.BanditBoxTheme
import com.spacegoose.banditbox.ui.theme.kidsSafe
import com.spacegoose.banditbox.layout.summary
import com.spacegoose.banditbox.sync.DiskCopy
import com.spacegoose.banditbox.sync.WidgetSnapshot
import com.spacegoose.banditbox.layout.WidgetFeeds
import com.spacegoose.banditbox.system.WidgetLaunch
import com.spacegoose.banditbox.system.PluginFeeds
import com.spacegoose.banditbox.system.AppSearch
import com.spacegoose.banditbox.sync.SyncController
import com.spacegoose.banditbox.system.BackupFiles
import com.spacegoose.banditbox.system.BackupSource
import com.spacegoose.banditbox.system.SystemShortcuts
import com.spacegoose.banditbox.system.TvInput
import com.spacegoose.banditbox.system.passthroughInputs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** What the launcher needs from the activity. */
class LauncherDeps(
    val index: AppIndex,
    val visuals: AppVisuals,
    val store: LayoutStore,
    val settings: DeviceSettings,
    /** Watch Next and preview channels other apps publish. */
    val tv: TvProviderReader,
    /** True when the TV has just booted: auto sign-in or the profile picker applies. */
    val freshBoot: Boolean,
    /** Start the first intent the device can handle. */
    val launch: (List<Intent>) -> Unit,
    val onAppAction: (AppEntry, AppMenuAction) -> Unit,
    /** The open profile's remote button rules changed; the BanditBox service applies them. */
    val onRemapsChanged: (List<Remap>) -> Unit,
    /** Starts the launcher over, reading every profile from disk again (after a restore). */
    val restart: () -> Unit,
    /** A kids profile opened (true) or closed: the activity switches to the kids-safe theme. */
    val onKidsMode: (Boolean) -> Unit = {},
    /** Held while reading or writing [store]; sync holds it too. */
    val storeLock: Mutex = Mutex(),
    /** Keeps layouts in step with a BanditBox server; null in previews. */
    val sync: SyncController? = null,
)

/** A remote button shortcut asked for a screen. [id] tells two requests for the same screen apart. */
data class ShowRequest(val screen: String, val id: Int)

private enum class Screen { HOME, ALL_APPS, SEARCH, EDIT_ROWS, PICK_PROFILE, HOME_SETUP }

/** An item being moved along its row with the D-pad. */
private class Moving(val rowId: String, val item: String, val label: String)

/**
 * Root of the launcher UI. [homePresses] goes up each time Home is pressed while the
 * launcher is already open, which returns to the main screen and closes any dialog.
 * [request] is the latest screen a remote button shortcut asked for. [foreground] is true while
 * the launcher is resumed; content rows only watch the TvProvider then.
 */
@Composable
fun BanditBoxApp(deps: LauncherDeps, homePresses: Int, request: ShowRequest?, foreground: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storeLock = deps.storeLock
    val diskCopy = remember { DiskCopy() }

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

    val kidsDevice = remember { (context.applicationContext as BanditBoxApplication).kidsDevice }
    /** Where the open kids profile stands (time left, lock); null on a normal profile. */
    var kidsStatus by remember { mutableStateOf<KidsStatus?>(null) }
    /** Goes up to re-check the kids rules at once, for example after a parent unlock. */
    var kidsTick by remember { mutableIntStateOf(0) }

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
                (deps.store.load(id) ?: deps.store.loadOrCreate(DefaultLayout.PROFILE_ID) { DefaultLayout.create(tvApps()) })
                    .also(diskCopy::loaded)
            }
        }
    }
    // Every edit is written to disk in order; a burst of edits (holding ◀ in move mode) writes only the latest.
    LaunchedEffect(Unit) {
        var savedKids: Kids? = null
        snapshotFlow { doc }.filterNotNull().drop(1).conflate().collect { latest ->
            withContext(Dispatchers.IO) {
                storeLock.withLock {
                    if (latest.profileId in deletedIds) return@withLock
                    // A newer copy of this profile is on its way here; save that one instead.
                    val shown = doc
                    if (shown != null && shown.profileId == latest.profileId && shown !== latest) return@withLock
                    try {
                        diskCopy.save(deps.store, latest)
                    } catch (e: IOException) {
                        Log.w("BanditBox", "Couldn't save layout", e)
                    }
                }
            }
            // The service reads kids rules from disk, so tell it only once they are saved.
            if (latest.profile.kids != savedKids) {
                savedKids = latest.profile.kids
                Guard.kidsChanged()
            }
        }
    }
    LaunchedEffect(Unit) { inputs = withContext(Dispatchers.IO) { passthroughInputs(context) } }
    // A sync changed layouts on disk: show the new copy, keeping any edit not saved yet.
    LaunchedEffect(Unit) {
        deps.sync?.results?.collect { result ->
            val id = profileId
            val moved = id?.let { result.renamed[it] }
            var gone = false
            if (moved == null && id != null && id in result.changed) {
                // On the main thread under the lock, so no edit slips in between reading and showing.
                storeLock.withLock<Unit> {
                    val shown = doc?.takeIf { it.profileId == id } ?: return@withLock
                    val next = diskCopy.reload(deps.store, shown)
                    if (next == null) gone = true else doc = next
                }
            }
            if (id != null && (gone || moved != null)) deletedIds += id
            refreshProfiles()
            when {
                moved != null -> open(moved)
                gone -> when (val start = Profiles.startup(profiles, null, freshBoot = false)) {
                    is Startup.Open -> open(start.profileId)
                    Startup.Pick -> screen = Screen.PICK_PROFILE
                }
            }
            if (result.rejected.isNotEmpty() && overlay == null) {
                overlay = Overlay.Message(context.getString(R.string.sync_settings), context.getString(R.string.sync_rejected, result.rejected.size))
            }
        }
    }

    val edit: ((LayoutDocument) -> LayoutDocument) -> Unit = { change -> doc?.let { doc = change(it) } }

    /** On a kids profile, runs [then] only after the parent PIN. Otherwise runs it straight away. */
    fun parentGate(then: () -> Unit) {
        val pin = doc?.profile?.kids?.parentPin
        if (pin == null) {
            then()
        } else {
            overlay = Overlay.PinEntry(context.getString(R.string.kids_parent_pin), pin, then)
        }
    }

    /** Opening a profile other than the current one asks for its PIN, if it has one. */
    fun pick(profile: ProfileSummary) {
        val hash = profile.pinHash
        val alreadyOpen = profile.id == profileId && screen != Screen.PICK_PROFILE
        // Leaving a kids profile already took the parent PIN; don't ask for the same one twice.
        val parentPin = doc?.takeIf { it.profileId == profileId }?.profile?.kids?.parentPin
        if (hash == null || alreadyOpen || hash == parentPin) {
            open(profile.id)
        } else {
            overlay = Overlay.PinEntry(context.getString(R.string.pin_enter, profile.name), hash) { open(profile.id) }
        }
    }

    val kidsActions = KidsActions(
        parentGate = ::parentGate,
        add = { name, parentPin ->
            scope.launch {
                val created = withContext(Dispatchers.IO) {
                    val installed = deps.index.load().map { it.packageName to it.label }
                    storeLock.withLock { Profiles.createKids(deps.store, name, KidsRules.defaultApproved(installed), parentPin) }
                }
                refreshProfiles()
                open(created.profileId)
            }
        },
        setApproval = { kidsId, pkg, approved ->
            scope.launch {
                withContext(Dispatchers.IO) { storeLock.withLock { Profiles.setKidsApproval(deps.store, kidsId, pkg, approved) } }
                refreshProfiles()
            }
        },
        hasUsageAccess = { kidsDevice.hasUsageAccess() },
        openUsageAccess = { deps.launch(listOf(kidsDevice.usageAccessIntent())) },
        serviceOn = { GuardStatus.isServiceEnabled(context) },
    )

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

    // ---- Theme, wallpaper and screensaver ----
    val wallpaperFiles = remember { WallpaperFiles(context) }
    val wallpaperRef = ThemeResolver.wallpaperOf(doc?.theme)
    var picture by remember { mutableStateOf<LoadedPicture?>(null) }
    LaunchedEffect(wallpaperRef) {
        val media = wallpaperRef as? WallpaperRef.Media
        picture = if (media == null) null else withContext(Dispatchers.IO) { wallpaperFiles.load(media.sha256) }
    }
    // A kids profile always draws with a kids-dark preset; its wallpaper and screensaver still apply.
    val docTheme = doc?.let { if (it.profile.kids != null) kidsSafe(it.theme) else it.theme }
    val theme = remember(docTheme, picture) { ThemeResolver.resolve(docTheme, picture?.accent) }

    /** Puts a picture on the current profile, then forgets old pictures no profile uses. */
    fun addPicture(read: () -> String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(read())
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: SecurityException) {
                    Result.failure(e)
                }
            }
            result.fold(
                onSuccess = { sha ->
                    overlay = null
                    edit { LayoutEdits.setWallpaper(it, WallpaperRef.Media(sha).ref) }
                    withContext(Dispatchers.IO) {
                        val inUse = storeLock.withLock {
                            deps.store.profileIds().mapNotNull { id -> runCatching { deps.store.load(id) }.getOrNull()?.theme?.wallpaper }
                        }.toMutableSet()
                        inUse += WallpaperRef.Media(sha).ref
                        wallpaperFiles.prune(inUse.map { it.removePrefix(com.spacegoose.banditbox.layout.Theme.MEDIA_PREFIX) }.toSet())
                    }
                },
                onFailure = { e ->
                    message(context.getString(R.string.wallpaper), context.getString(R.string.wallpaper_add_failed, e.message ?: e.toString()))
                },
            )
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addPicture { wallpaperFiles.importUri(uri) }
    }
    val hasImagePicker = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .resolveActivity(context.packageManager) != null
    }
    val themeActions = ThemeActions(
        hasImagePicker = hasImagePicker,
        pickImage = {
            try {
                pickImage.launch(arrayOf("image/*"))
            } catch (_: ActivityNotFoundException) {
                message(context.getString(R.string.wallpaper), context.getString(R.string.cannot_open))
            }
        },
        addFromLink = { link ->
            message(context.getString(R.string.wallpaper), context.getString(R.string.wallpaper_adding))
            addPicture { wallpaperFiles.importUrl(link) }
        },
        pictures = { wallpaperFiles.list() },
        iconPacks = { deps.visuals.iconPacks.installed() },
    )

    // The OLED screensaver starts after the profile's timeout with no button press, while the
    // launcher is in front. Opening or changing a dialog counts as a press.
    val resumed by rememberResumed()
    var lastInput by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var screensaverOn by remember { mutableStateOf(false) }
    val saver = theme.screensaver
    LaunchedEffect(saver, lastInput, resumed, overlay, openFolderId, screen) {
        if (!resumed) {
            screensaverOn = false
            return@LaunchedEffect
        }
        if (!saver.type.drawnByLauncher || screensaverOn) return@LaunchedEffect
        delay(saver.timeoutMin * 60_000L)
        overlay = null // nothing may stay on screen behind (or above) it
        openFolderId = null
        moving = null
        screensaverOn = true
    }

    LaunchedEffect(homePresses) {
        screensaverOn = false
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
                parentGate { overlay = Overlay.Settings }
            }
        }
    }
    // The BanditBox service follows the open profile's button rules (on a kids profile, only the safe ones).
    val remaps = doc?.let(KidsRules::allowedRemaps)
    LaunchedEffect(remaps) { remaps?.let(deps.onRemapsChanged) }

    // Kids screen time counts while a kids profile is the open one, and stops when another opens.
    val openDoc = doc?.takeIf { it.profileId == profileId }
    val kidsSession = openDoc?.let { it.profileId to (it.profile.kids != null) }
    LaunchedEffect(kidsSession) {
        val (id, isKids) = kidsSession ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            if (isKids) kidsDevice.startSession(id)
            kidsDevice.endSessionsExcept(if (isKids) id else null)
        }
        Guard.kidsChanged()
    }
    // Re-check the kids rules while the launcher is on screen: the clock moves, and so does bedtime.
    val kidsRules = openDoc?.profile?.kids
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(profileId, kidsRules, kidsTick) {
        val id = profileId
        val kids = kidsRules
        if (id == null || kids == null) {
            kidsStatus = null
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = System.currentTimeMillis()
                val used = withContext(Dispatchers.IO) { kidsDevice.usedTodayMs(id, now) }
                kidsStatus = KidsRules.evaluate(kids, now, ZoneId.systemDefault(), used, kidsDevice.unlockedUntil(id))
                delay(KIDS_REFRESH_MS)
            }
        }
    }
    val isKidsProfile = kidsRules != null
    LaunchedEffect(isKidsProfile) { deps.onKidsMode(isKidsProfile) }
    val kidsLock = kidsStatus?.lock.takeIf { kidsRules != null }
    // A lock closes whatever was open, except the PIN pad that lifts it.
    LaunchedEffect(kidsLock) {
        if (kidsLock != null) {
            if (overlay !is Overlay.PinEntry) overlay = null
            openFolderId = null
            moving = null
            if (screen != Screen.PICK_PROFILE) screen = Screen.HOME
        }
    }

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

    // ---- Continue watching and channel rows ----
    val homeRows = remember(currentDoc, byPackage) {
        if (currentDoc == null) emptyList() else LayoutResolver.homeRows(currentDoc, byPackage.keys)
    }
    val contentAllowed: (String) -> Boolean = remember(currentDoc, byPackage) {
        currentDoc?.let { LayoutResolver.contentAllowed(it, byPackage.keys) } ?: { false }
    }
    val needWatchNext = homeRows.any { it is HomeRow.Content && it.row is Row.WatchNext }
    val needChannels = homeRows.any { it is HomeRow.Content && it.row is Row.Channel }
    var tv by remember { mutableStateOf(TvSnapshot.NOT_GRANTED) }
    var permissionAsked by remember { mutableIntStateOf(0) }
    // Read only while in front and only the tables a row needs; a fresh read follows every change an app makes.
    LaunchedEffect(foreground, needWatchNext, needChannels, permissionAsked) {
        if (!foreground || !(needWatchNext || needChannels)) return@LaunchedEffect
        deps.tv.snapshots(needWatchNext, needChannels).collect { tv = it }
    }
    val askTvPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionAsked++
        if (!granted) {
            message(
                context.getString(R.string.content_allow_title),
                context.getString(R.string.content_denied, context.packageName),
            )
        }
    }
    fun allowTvListings() {
        try {
            askTvPermission.launch(TvProviderReader.PERMISSION)
        } catch (_: ActivityNotFoundException) {
            message(context.getString(R.string.content_allow_title), context.getString(R.string.content_denied, context.packageName))
        }
    }
    val continueWatching = stringResource(R.string.continue_watching)
    val contentState = remember(tv, contentAllowed, byPackage, continueWatching) {
        ContentState(
            tv = tv,
            allowed = contentAllowed,
            titleFor = { row ->
                row.title ?: when (row) {
                    is Row.WatchNext -> continueWatching
                    is Row.Channel -> byPackage[row.packageName]?.label ?: row.channel ?: row.packageName
                }
            },
            onOpen = { program -> deps.launch(ProgramLaunch.intents(program, context.packageManager)) },
            onAllow = ::allowTvListings,
        )
    }

    // ---- Widget rows and top bar widgets ----
    val noWidgets = remember { MutableStateFlow(WidgetSnapshot.EMPTY) }
    val serverWidgets by (deps.sync?.widgets ?: noWidgets).collectAsState()
    // Apps on the TV that publish feeds (banditbox-plugin-sdk): read while in front, and again
    // when the installed apps change.
    val sleepMinutes = rememberSleepMinutes()
    val plugins = remember { PluginFeeds(context) }
    val pluginWidgets by plugins.feeds.collectAsState()
    LaunchedEffect(foreground, byPackage.keys) { if (foreground) plugins.watch() }
    val widgetKids = currentDoc?.profile?.kids != null
    val widgetState = remember(serverWidgets, pluginWidgets, widgetKids, contentAllowed) {
        WidgetState(
            // The server's feeds win over an app's feed with the same id.
            feeds = pluginWidgets + serverWidgets.feeds,
            kids = widgetKids,
            allowed = contentAllowed,
            image = { ref ->
                when {
                    ref == null -> null
                    ref.startsWith("http") || ref.startsWith("content://") || ref.startsWith("android.resource://") -> ref
                    else -> WidgetFeeds.mediaSha(ref)?.let { deps.sync?.mediaUrl(it) }
                }
            },
            onOpen = { card, feed -> deps.launch(WidgetLaunch.intents(card, feed, context.packageManager)) },
            onMenu = { card, feed ->
                overlay = Overlay.Choices(
                    title = card.title,
                    choices = card.actions.map { a ->
                        Choice(a.label) {
                            deps.launch(WidgetLaunch.intents(a.link, card.packageName ?: feed.packageName, context.packageManager))
                        }
                    },
                )
            },
        )
    }

    /** Lists the channels apps have published, to add one as a row. */
    fun pickChannel() {
        if (!deps.tv.hasPermission()) {
            allowTvListings()
            return
        }
        scope.launch {
            val channels = withContext(Dispatchers.IO) { deps.tv.readChannels() }
            val pickable = ContentSelect.pickable(channels, contentAllowed)
            if (pickable.isEmpty()) {
                message(context.getString(R.string.add_channel_row), context.getString(R.string.no_channels))
                return@launch
            }
            val perApp = pickable.groupingBy { it.packageName }.eachCount()
            overlay = Overlay.Choices(
                title = context.getString(R.string.add_channel_row),
                choices = pickable.map { channel ->
                    val app = byPackage[channel.packageName]?.label ?: channel.packageName
                    val label = if (perApp[channel.packageName] == 1) app else "$app: ${channel.displayName}"
                    Choice(label) { edit { LayoutEdits.addChannelRow(it, label, channel.packageName, channel.ref).first } }
                },
            )
        }
    }

    val tileActions = remember(deps) {
        TileActions(
            onOpen = { deps.onAppAction(it, AppMenuAction.OPEN) },
            onAppMenu = { app, place -> overlay = Overlay.AppMenu(app, place) },
            onFolderOpen = { openFolderId = it.id },
            onFolderMenu = { folder, rowId ->
                // A kids profile can open folders but not change them.
                if (doc?.profile?.kids != null) openFolderId = folder.id else overlay = Overlay.FolderMenu(folder, rowId)
            },
        )
    }
    val topBarActions = remember(deps) {
        TopBarActions(
            onSearch = { screen = Screen.SEARCH },
            onAllApps = { screen = Screen.ALL_APPS },
            onInputs = { overlay = Overlay.Inputs },
            onNetwork = { deps.launch(SystemShortcuts.network) },
            onSettings = { parentGate { overlay = Overlay.Settings } },
            onProfile = { parentGate { screen = Screen.PICK_PROFILE } },
        )
    }

    // ---- Search ----
    val searchable = remember(screen == Screen.SEARCH, currentApps) {
        if (screen == Screen.SEARCH) AppSearch.searchablePackages(context.packageManager) else emptySet()
    }
    val hasGlobalSearch = remember { AppSearch.hasGlobal(context.packageManager) }
    val searchSources = remember(allApps, tv, contentAllowed, widgetState, searchable, currentDoc?.profile?.kids) {
        val kids = currentDoc?.profile?.kids != null
        SearchSources(
            apps = allApps,
            programs = (tv.watchNext + tv.previews.filter { it.browsable }).filter { contentAllowed(it.packageName) },
            cards = widgetState.feeds.keys.mapNotNull(widgetState::feed).flatMap { feed -> feed.cards.map { it to feed } },
            searchIn = allApps.filter { it.packageName in searchable && it.packageName != context.packageName },
            global = hasGlobalSearch && !kids,
            appLabel = { pkg -> byPackage[pkg]?.label ?: pkg },
        )
    }

    fun handleKey(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (screensaverOn) {
            // The press that wakes the screen does nothing else.
            if (event.type == KeyEventType.KeyUp) screensaverOn = false
            return true
        }
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
        // The remote's Search key, where it reaches apps, opens BanditBox's search.
        if (event.key == Key.Search && event.type == KeyEventType.KeyUp && profileId != null) {
            screen = Screen.SEARCH
            return true
        }
        // Menu toggles All apps, like Projectivy. Remotes without a Menu key use the top bar button.
        if (event.key == Key.Menu && event.type == KeyEventType.KeyUp && profileId != null) {
            screen = if (screen == Screen.HOME) Screen.ALL_APPS else Screen.HOME
            return true
        }
        return false
    }

    BanditBoxTheme(theme) {
    Box(Modifier.fillMaxSize()) {
    WallpaperLayer(theme, picture)
    Surface(
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent(::handleKey),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        val lock = kidsLock
        if (screen == Screen.PICK_PROFILE) {
            ProfilePickerScreen(
                profiles = profilesView,
                currentId = profileId ?: deps.settings.currentProfileId,
                onPick = ::pick,
                onAdd = { overlay = Overlay.TextInput(context.getString(R.string.new_profile_name), "") { profileActions.add(it) } },
            )
        } else if (currentDoc != null && lock != null) {
            KidsLockScreen(
                lock = lock,
                endsAtMs = kidsStatus?.lockEndsAtMs,
                onUnlock = {
                    parentGate {
                        val id = profileId
                        val kids = currentDoc.profile.kids
                        if (id != null && kids != null) kidsDevice.unlock(id, kids)
                        kidsTick++
                        Guard.kidsChanged()
                    }
                },
                onSwitchProfile = { parentGate { screen = Screen.PICK_PROFILE } },
            )
        } else if (currentDoc != null) {
            // Nothing is drawn until the local layout is read, which takes milliseconds and never touches the network.
            when (screen) {
                Screen.HOME, Screen.PICK_PROFILE -> HomeScreen(
                    rows = homeRows,
                    apps = byPackage,
                    visuals = deps.visuals,
                    content = contentState,
                    widgets = widgetState,
                    topBar = {
                        TopBar(
                            items = currentDoc.topBar,
                            profileName = currentDoc.profile.name,
                            hasInputs = inputs.isNotEmpty(),
                            actions = topBarActions,
                            kidsMode = currentDoc.profile.kids != null,
                            note = listOfNotNull(
                                kidsStatus?.minutesLeft?.let { stringResource(R.string.kids_time_left, duration(context, it)) },
                                sleepMinutes?.let { stringResource(R.string.sleep_note, duration(context, it)) },
                            ).joinToString("  ").ifEmpty { null },
                            feed = widgetState::feed,
                        )
                    },
                    actions = tileActions,
                )
                Screen.ALL_APPS -> AllAppsScreen(stringResource(R.string.all_apps), allApps, deps.visuals, tileActions)
                Screen.SEARCH -> SearchScreen(
                    sources = searchSources,
                    visuals = deps.visuals,
                    actions = tileActions,
                    onProgram = contentState.onOpen,
                    onCard = widgetState.onOpen,
                    launch = deps.launch,
                )
                Screen.EDIT_ROWS -> RowEditor(
                    doc = currentDoc,
                    edit = edit,
                    show = { overlay = it },
                    rowLabel = { row ->
                        when (row) {
                            is Row.Apps -> null
                            is Row.WatchNext -> continueWatching
                            is Row.Channel -> contentState.titleFor(row)
                            is Row.Widget -> row.feed
                        }
                    },
                    onAddChannel = ::pickChannel,
                )
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
                        photoFeeds = widgetState.feeds.keys.sorted().mapNotNull(widgetState::feed)
                            .filter { feed -> feed.cards.any { it.image != null } },
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
                        theme = themeActions,
                        kids = kidsActions,
                    ),
                )
            }
        }
    }
    if (screensaverOn) {
        if (saver.type == ScreensaverType.PHOTOS) {
            PhotoScreensaver(saver.feed?.let(widgetState::feed)?.cards.orEmpty(), widgetState.image, MaterialTheme.colorScheme.primary)
        } else {
            OledScreensaver(saver.type, MaterialTheme.colorScheme.primary)
        }
    }
    }
    }
}

/** How often the launcher re-checks a kids profile's time and bedtime while it is on screen. */
private const val KIDS_REFRESH_MS = 30_000L

@Composable
private fun RowEditor(
    doc: LayoutDocument,
    edit: ((LayoutDocument) -> LayoutDocument) -> Unit,
    show: (Overlay?) -> Unit,
    rowLabel: (Row) -> String?,
    onAddChannel: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val nameTitle = stringResource(R.string.row_name)
    val untitled = stringResource(R.string.row_untitled)
    val deleteLabel = stringResource(R.string.delete)
    val continueWatching = stringResource(R.string.continue_watching)
    RowEditorScreen(
        rows = doc.rows,
        rowLabel = rowLabel,
        actions = RowEditorActions(
            onAddRow = { show(Overlay.TextInput(nameTitle, "") { name -> edit { LayoutEdits.addRow(it, name).first } }) },
            onAddWatchNext = { edit { LayoutEdits.addWatchNextRow(it, continueWatching).first } },
            onAddChannel = onAddChannel,
            onRename = { row ->
                show(Overlay.TextInput(nameTitle, row.title.orEmpty()) { name -> edit { LayoutEdits.renameRow(it, row.id, name) } })
            },
            onTile = { row, tile -> edit { LayoutEdits.setTile(it, row.id, tile) } },
            onContentSize = { row, size -> edit { LayoutEdits.setContentSize(it, row.id, size) } },
            onKinds = { row, kinds -> edit { LayoutEdits.setWatchNextKinds(it, row.id, kinds) } },
            onMove = { row, direction -> edit { LayoutEdits.moveRow(it, row.id, direction) } },
            onDelete = { row ->
                val title = resources.getString(R.string.delete_row_confirm, row.title ?: rowLabel(row) ?: untitled)
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
