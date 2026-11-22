package com.spacegoose.banditbox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.apps.IconStyle
import com.spacegoose.banditbox.layout.IconPackRef
import com.spacegoose.banditbox.layout.ResolvedTheme
import com.spacegoose.banditbox.layout.ThemeColorMath
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.layout.Tile
import com.spacegoose.banditbox.layout.TileStyle
import com.spacegoose.banditbox.ui.theme.LocalBanditTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LoadedTile(val bitmap: ImageBitmap, val isBanner: Boolean)

/**
 * One app, drawn as [spec] says: a 16:9 banner (icon and name when the app has none),
 * or a square icon tile. The app's name fades in under the tile while it has focus, and
 * [onFocused] hears about it with the art, for the home screen's featured area.
 */
@Composable
fun AppTile(
    app: AppEntry,
    visuals: AppVisuals,
    spec: Tile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: ((art: ImageBitmap?, isBanner: Boolean) -> Unit)? = null,
) {
    val size = spec.dpSize()
    val isIconStyle = spec.style == TileStyle.ICON
    val density = LocalDensity.current
    val widthPx = with(density) { size.width.roundToPx() }
    val heightPx = with(density) { size.height.roundToPx() }
    val iconPx = with(density) { (if (isIconStyle) size.height * 0.7f else size.height * 0.45f).roundToPx() }

    val theme = LocalBanditTheme.current
    val iconStyle = remember(theme) { theme.iconStyle() }
    var tile by remember(app, spec, density, iconStyle) { mutableStateOf<LoadedTile?>(null) }
    LaunchedEffect(app, spec, density, iconStyle) {
        tile = withContext(Dispatchers.IO) {
            visuals.tile(app, widthPx, heightPx, iconPx, preferBanner = !isIconStyle, style = iconStyle)
                ?.let { LoadedTile(it.bitmap.asImageBitmap(), it.isBanner) }
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Report focus again once the art arrives, so the featured area never sticks on a blank.
    LaunchedEffect(focused, tile) {
        if (focused) onFocused?.invoke(tile?.bitmap, tile?.isBanner ?: false)
    }

    val shape = spec.composeShape()
    FocusLabelled(label = app.label, focused = focused, width = size.width, modifier = modifier) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = CardDefaults.shape(shape = shape),
            scale = BanditCard.scale(),
            border = BanditCard.border(shape),
            glow = BanditCard.glow(),
            colors = BanditCard.colors(),
            interactionSource = interaction,
            modifier = Modifier.size(size),
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
}

/** A folder: its name and how many apps are inside. Opens on click. */
@Composable
fun FolderTile(
    folder: ResolvedItem.FolderItem,
    spec: Tile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused?.invoke() }
    val shape = spec.composeShape()
    val size = spec.dpSize()
    val count = pluralStringResource(R.plurals.folder_count, folder.packages.size, folder.packages.size)
    FocusLabelled(label = count, focused = focused, width = size.width, modifier = modifier) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = CardDefaults.shape(shape = shape),
            scale = BanditCard.scale(),
            border = BanditCard.border(shape),
            glow = BanditCard.glow(),
            colors = BanditCard.colors(),
            interactionSource = interaction,
            modifier = Modifier.size(size),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), Color.Transparent),
                        ),
                    )
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (spec.style == TileStyle.BANNER) {
                    Text(text = count, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * A tile with its name underneath, shown only while it has focus, like the stock TV home
 * screens. The space is always kept, so rows don't jump as focus moves.
 */
@Composable
private fun FocusLabelled(
    label: String,
    focused: Boolean,
    width: Dp,
    modifier: Modifier = Modifier,
    tile: @Composable () -> Unit,
) {
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "tileLabel")
    // Unfocused tiles sit back a little, so the focused one stands out.
    val tileAlpha by animateFloatAsState(if (focused) 1f else 0.86f, tween(200), label = "tileDim")
    Column(
        modifier = modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.graphicsLayer { alpha = tileAlpha }) { tile() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = labelAlpha },
        )
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

/** The theme's icon pack, as the app index draws it. */
private fun ResolvedTheme.iconStyle(): IconStyle = when (val pack = iconPack) {
    IconPackRef.System -> IconStyle.System
    IconPackRef.Uniform -> IconStyle.Plate(ThemeColorMath.mix(surface, text, 0.12f), tint = null)
    IconPackRef.Mono -> IconStyle.Plate(ThemeColorMath.mix(surface, text, 0.12f), tint = accent)
    is IconPackRef.Installed -> IconStyle.Pack(pack.packageName)
}
