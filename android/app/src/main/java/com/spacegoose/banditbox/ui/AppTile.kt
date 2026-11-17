package com.spacegoose.banditbox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppVisuals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val TILE_WIDTH = 160.dp
private val TILE_HEIGHT = 90.dp
private val ICON_SIZE = 48.dp

private class LoadedTile(val bitmap: ImageBitmap, val isBanner: Boolean)

/** A 16:9 app tile: the TV banner, or the icon and name when the app has no banner. */
@Composable
fun AppTile(
    app: AppEntry,
    visuals: AppVisuals,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { TILE_WIDTH.roundToPx() }
    val heightPx = with(density) { TILE_HEIGHT.roundToPx() }
    val iconPx = with(density) { ICON_SIZE.roundToPx() }

    var tile by remember(app, widthPx) { mutableStateOf<LoadedTile?>(null) }
    LaunchedEffect(app, widthPx) {
        tile = withContext(Dispatchers.IO) {
            visuals.tile(app, widthPx, heightPx, iconPx)?.let { LoadedTile(it.bitmap.asImageBitmap(), it.isBanner) }
        }
    }

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    ) {
        val loaded = tile
        if (loaded != null && loaded.isBanner) {
            Image(
                bitmap = loaded.bitmap,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Box(modifier = Modifier.size(ICON_SIZE)) {
                    if (loaded != null) {
                        Image(bitmap = loaded.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
