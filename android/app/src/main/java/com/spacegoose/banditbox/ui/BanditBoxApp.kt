package com.spacegoose.banditbox.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Surface
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppIndex
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.layout.DefaultLayout
import com.spacegoose.banditbox.layout.LayoutDocument
import com.spacegoose.banditbox.layout.LayoutResolver
import com.spacegoose.banditbox.layout.LayoutStore
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.system.SystemShortcuts
import com.spacegoose.banditbox.system.TvInput
import com.spacegoose.banditbox.system.passthroughInputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the launcher needs from the activity. */
class LauncherDeps(
    val index: AppIndex,
    val visuals: AppVisuals,
    val store: LayoutStore,
    /** Start the first intent the device can handle. */
    val launch: (List<Intent>) -> Unit,
    val onAppAction: (AppEntry, AppMenuAction) -> Unit,
)

private enum class Screen { HOME, ALL_APPS }

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
    LaunchedEffect(Unit) { inputs = withContext(Dispatchers.IO) { passthroughInputs(context) } }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var openFolder by remember { mutableStateOf<ResolvedItem.FolderItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showInputs by remember { mutableStateOf(false) }

    LaunchedEffect(homePresses) {
        screen = Screen.HOME
        menuFor = null
        openFolder = null
        showSettings = false
        showInputs = false
    }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

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
            onMenu = { menuFor = it },
            onFolder = { openFolder = it },
        )
    }
    val topBarActions = remember(deps) {
        TopBarActions(
            onAllApps = { screen = Screen.ALL_APPS },
            onInputs = { showInputs = true },
            onNetwork = { deps.launch(SystemShortcuts.network) },
            onSettings = { showSettings = true },
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Menu toggles All apps, like Projectivy. Remotes without a Menu key use the top bar button.
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    screen = if (screen == Screen.HOME) Screen.ALL_APPS else Screen.HOME
                    true
                } else {
                    false
                }
            },
    ) {
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
            }
        }

        openFolder?.let { folder ->
            FolderDialog(
                name = folder.name,
                apps = folder.packages.mapNotNull(byPackage::get),
                visuals = deps.visuals,
                actions = tileActions,
                onDismiss = { openFolder = null },
            )
        }
        menuFor?.let { app ->
            AppMenu(
                app = app,
                onDismiss = { menuFor = null },
                onPick = { action ->
                    menuFor = null
                    deps.onAppAction(app, action)
                },
            )
        }
        if (showSettings) {
            ChoiceDialog(
                title = stringResource(R.string.settings),
                choices = listOf(
                    Choice(stringResource(R.string.android_settings)) { showSettings = false; deps.launch(SystemShortcuts.androidSettings) },
                    Choice(stringResource(R.string.network_settings)) { showSettings = false; deps.launch(SystemShortcuts.network) },
                    Choice(stringResource(R.string.install_unknown_apps)) { showSettings = false; deps.launch(SystemShortcuts.installUnknownApps) },
                ),
                onDismiss = { showSettings = false },
            )
        }
        if (showInputs) {
            ChoiceDialog(
                title = stringResource(R.string.inputs),
                choices = inputs.map { input -> Choice(input.label) { showInputs = false; deps.launch(listOf(input.intent())) } },
                onDismiss = { showInputs = false },
            )
        }
    }
}
