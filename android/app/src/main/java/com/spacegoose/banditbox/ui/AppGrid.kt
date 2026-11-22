package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    place: Place,
    modifier: Modifier = Modifier,
) {
    // Open with the first app focused.
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) focusWhenReady(firstTile) }
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = GRID_TILE.dpSize().width),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Room for the focused tile to grow and glow without being clipped.
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                AppTile(
                    app = app,
                    visuals = visuals,
                    spec = GRID_TILE,
                    onClick = { actions.onOpen(app) },
                    onLongClick = { actions.onAppMenu(app, place) },
                    modifier = if (index == 0) Modifier.focusRequester(firstTile) else Modifier,
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
        place = Place.AllApps,
        modifier = Modifier.fillMaxSize().padding(start = SCREEN_GUTTER - 12.dp, end = SCREEN_GUTTER - 12.dp, top = 28.dp),
    )
}

/** A folder opened from a row. Back closes it. */
@Composable
fun FolderDialog(
    folderId: String,
    name: String,
    apps: List<AppEntry>,
    visuals: AppVisuals,
    actions: TileActions,
    onDismiss: () -> Unit,
) {
    BanditDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(64.dp)) {
            AppGrid(
                title = name,
                apps = apps,
                visuals = visuals,
                actions = actions,
                place = Place.InFolder(folderId),
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
