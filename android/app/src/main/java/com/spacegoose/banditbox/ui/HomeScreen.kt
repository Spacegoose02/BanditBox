package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.layout.ResolvedAppsRow
import com.spacegoose.banditbox.layout.ResolvedItem

/** Callbacks shared by every place an app or folder tile appears. */
class TileActions(
    val onOpen: (AppEntry) -> Unit,
    val onMenu: (AppEntry) -> Unit,
    val onFolder: (ResolvedItem.FolderItem) -> Unit,
)

/** The main screen: top bar, then the layout's rows. */
@Composable
fun HomeScreen(
    rows: List<ResolvedAppsRow>,
    apps: Map<String, AppEntry>,
    visuals: AppVisuals,
    topBar: @Composable () -> Unit,
    actions: TileActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        topBar()
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_home),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        ) {
            items(rows, key = { it.id }) { row -> AppsRow(row, apps, visuals, actions) }
        }
    }
}

@Composable
private fun AppsRow(row: ResolvedAppsRow, apps: Map<String, AppEntry>, visuals: AppVisuals, actions: TileActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        row.title?.let { Text(text = it, style = MaterialTheme.typography.titleMedium) }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Room for the focused tile to scale up without clipping.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            items(row.items, key = { it.key() }) { item ->
                when (item) {
                    is ResolvedItem.App -> apps[item.packageName]?.let { app ->
                        AppTile(
                            app = app,
                            visuals = visuals,
                            spec = row.tile,
                            onClick = { actions.onOpen(app) },
                            onLongClick = { actions.onMenu(app) },
                        )
                    }
                    is ResolvedItem.FolderItem -> FolderTile(folder = item, spec = row.tile, onClick = { actions.onFolder(item) })
                }
            }
        }
    }
}

private fun ResolvedItem.key(): String = when (this) {
    is ResolvedItem.App -> "app:$packageName"
    is ResolvedItem.FolderItem -> "folder:$id"
}
