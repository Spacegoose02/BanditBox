package com.spacegoose.banditbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.spacegoose.banditbox.layout.ResolvedTheme
import com.spacegoose.banditbox.layout.WallpaperRef
import com.spacegoose.banditbox.system.LoadedPicture
import com.spacegoose.banditbox.ui.theme.drawPackWallpaper

/**
 * The theme's background: plain color, a pack wallpaper drawn as vector art, or an uploaded
 * picture ([picture], loaded by the caller; null while loading or when it's missing), with the
 * theme's dimming and a soft vignette on top.
 */
@Composable
fun WallpaperLayer(theme: ResolvedTheme, picture: LoadedPicture?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(theme.background))) {
        when (val w = theme.wallpaper) {
            WallpaperRef.None -> Unit
            is WallpaperRef.Pack -> Canvas(Modifier.fillMaxSize()) { drawPackWallpaper(w.wallpaper) }
            is WallpaperRef.Media -> if (picture != null) {
                val image = remember(picture) { picture.bitmap.asImageBitmap() }
                Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (theme.wallpaperDim > 0f && theme.wallpaper != WallpaperRef.None) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = theme.wallpaperDim)))
        }
        val accent = Color(theme.accent)
        val base = Color(theme.background)
        // Depth: a faint accent light from the top left, and the bottom and left edges sinking
        // into the background, so the clock, titles and rows always read over any wallpaper.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.1f, 0f),
                        radius = size.width * 0.6f,
                    ),
                )
                drawRect(Brush.horizontalGradient(0f to base.copy(alpha = 0.55f), 0.45f to Color.Transparent))
                drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to base.copy(alpha = 0.85f)))
            },
        )
    }
}
