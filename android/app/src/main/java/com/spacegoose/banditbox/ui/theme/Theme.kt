package com.spacegoose.banditbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.spacegoose.banditbox.layout.ResolvedTheme
import com.spacegoose.banditbox.layout.Theme
import com.spacegoose.banditbox.layout.ThemeCatalog
import com.spacegoose.banditbox.layout.ThemeColorMath
import com.spacegoose.banditbox.layout.ThemeResolver

/** The profile's theme with every value filled in, for drawing outside MaterialTheme's colors. */
val LocalBanditTheme = staticCompositionLocalOf { ThemeResolver.resolve(null) }

/** Black or white, whichever reads better on [background]. */
private fun onColor(background: Int): Color =
    if (ThemeColorMath.contrast(background, 0xFF000000.toInt()) >= ThemeColorMath.contrast(background, 0xFFFFFFFF.toInt())) Color.Black else Color.White

/** Material colors for a BanditBox theme. All themes are dark, so focus and text stay legible on a TV. */
@Composable
fun BanditBoxTheme(theme: ResolvedTheme = ThemeResolver.resolve(null), content: @Composable () -> Unit) {
    val colors = remember(theme.background, theme.surface, theme.text, theme.accent) {
        val surface = Color(theme.surface)
        val text = Color(theme.text)
        darkColorScheme(
            primary = Color(theme.accent),
            onPrimary = onColor(theme.accent),
            secondary = Color(theme.accent),
            onSecondary = onColor(theme.accent),
            border = Color(theme.accent),
            background = Color(theme.background),
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = Color(ThemeColorMath.mix(theme.surface, theme.text, 0.08f)),
            onSurfaceVariant = text.copy(alpha = 0.8f),
            inverseSurface = text,
            inverseOnSurface = surface,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalBanditTheme provides theme) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/**
 * The theme for a whole profile kind: a kids profile always draws with a kids-dark preset
 * (bright, high-contrast and calm), and only the parent PIN reaches the settings that could
 * change that.
 */
@Composable
fun BanditBoxTheme(kids: Boolean, content: @Composable () -> Unit) {
    BanditBoxTheme(ThemeResolver.resolve(if (kids) Theme(id = KIDS_PRESET_ID) else null), content)
}

const val KIDS_PRESET_ID = com.spacegoose.banditbox.layout.LayoutEdits.KIDS_THEME_ID
const val KIDS_CATEGORY_ID = "kids-dark"

/** [theme] for a kids profile: kept when it is a kids-dark preset, otherwise switched to one. */
fun kidsSafe(theme: Theme?): Theme {
    val base = theme ?: Theme()
    return if (ThemeCatalog.preset(base.id)?.category == KIDS_CATEGORY_ID) base else base.copy(id = KIDS_PRESET_ID, accent = null, colors = null)
}
