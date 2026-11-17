package com.spacegoose.banditbox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.layout.Tile
import com.spacegoose.banditbox.layout.TileStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LoadedTile(val bitmap: ImageBitmap, val isBanner: Boolean)

/**
 * One app, drawn as [spec] says: a 16:9 banner (icon and name when the app has none),
 * or a square icon tile.
 */
@Composable
fun AppTile(
    app: AppEntry,
    visuals: AppVisuals,
    spec: Tile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = spec.dpSize()
    val isIconStyle = spec.style == TileStyle.ICON
    val density = LocalDensity.current
    val widthPx = with(density) { size.width.roundToPx() }
    val heightPx = with(density) { size.height.roundToPx() }
    val iconPx = with(density) { (if (isIconStyle) size.height * 0.7f else size.height * 0.45f).roundToPx() }

    var tile by remember(app, spec, density) { mutableStateOf<LoadedTile?>(null) }
    LaunchedEffect(app, spec, density) {
        tile = withContext(Dispatchers.IO) {
            visuals.tile(app, widthPx, heightPx, iconPx, preferBanner = !isIconStyle)
                ?.let { LoadedTile(it.bitmap.asImageBitmap(), it.isBanner) }
        }
    }

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CardDefaults.shape(shape = spec.composeShape()),
        modifier = modifier.size(size),
    ) {
        val loaded = tile
        when {
            loaded != null && loaded.isBanner -> Image(
                bitmap = loaded.bitmap,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            isIconStyle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loaded != null) {
                    Image(bitmap = loaded.bitmap, contentDescription = app.label, modifier = Modifier.fillMaxSize(0.7f))
                }
            }
            else -> IconAndLabel(label = app.label, icon = loaded?.bitmap, iconSize = with(density) { iconPx.toDp() })
        }
    }
}

/** A folder: its name and how many apps are inside. Opens on click. */
@Composable
fun FolderTile(
    folder: ResolvedItem.FolderItem,
    spec: Tile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = spec.composeShape()),
        modifier = modifier.size(spec.dpSize()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (spec.style == TileStyle.BANNER) {
                Text(
                    text = pluralStringResource(R.plurals.folder_count, folder.packages.size, folder.packages.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun IconAndLabel(label: String, icon: ImageBitmap?, iconSize: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(modifier = Modifier.size(iconSize)) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
