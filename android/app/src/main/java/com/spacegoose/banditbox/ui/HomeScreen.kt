package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/**
 * Placeholder home screen: every installed app in one grid. The layout renderer
 * (core-layout rows, folders, hidden apps) replaces this in the next step.
 */
@Composable
fun HomeScreen(
    index: AppIndex,
    visuals: AppVisuals,
    onAction: (AppEntry, AppMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appsFlow = remember(index) { index.apps() }
    val apps by appsFlow.collectAsState(initial = emptyList())
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 27.dp)) {
            Text(
                text = stringResource(R.string.all_apps),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TILE_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppTile(
                        app = app,
                        visuals = visuals,
                        onClick = { onAction(app, AppMenuAction.OPEN) },
                        onLongClick = { menuFor = app },
                    )
                }
            }
        }

        menuFor?.let { app ->
            AppMenu(
                app = app,
                onDismiss = { menuFor = null },
                onPick = { action ->
                    menuFor = null
                    onAction(app, action)
                },
            )
        }
    }
}
