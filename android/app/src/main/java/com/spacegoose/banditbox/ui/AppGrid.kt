package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppVisuals

/** A titled grid of app tiles: the All apps screen, and the inside of a folder. */
@Composable
fun AppGrid(
    title: String,
    apps: List<AppEntry>,
    visuals: AppVisuals,
    actions: TileActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = GRID_TILE.dpSize().width),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                AppTile(
                    app = app,
                    visuals = visuals,
                    spec = GRID_TILE,
                    onClick = { actions.onOpen(app) },
                    onLongClick = { actions.onMenu(app) },
                )
            }
        }
    }
}

@Composable
fun AllAppsScreen(title: String, apps: List<AppEntry>, visuals: AppVisuals, actions: TileActions) {
    AppGrid(
        title = title,
        apps = apps,
        visuals = visuals,
        actions = actions,
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
    )
}

/** A folder opened from a row. Back closes it. */
@Composable
fun FolderDialog(name: String, apps: List<AppEntry>, visuals: AppVisuals, actions: TileActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(64.dp)) {
            AppGrid(title = name, apps = apps, visuals = visuals, actions = actions, modifier = Modifier.padding(24.dp))
        }
    }
}
