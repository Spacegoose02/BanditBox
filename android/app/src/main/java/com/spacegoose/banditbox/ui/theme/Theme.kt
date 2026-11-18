package com.spacegoose.banditbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Accent = Color(0xFF39FF14)
val Background = Color(0xFF0E0F13)

private val Colors = darkColorScheme(
    primary = Accent,
    background = Background,
    surface = Background,
)

/**
 * The kids-safe theme: bright, high-contrast and calm (no animation). A kids profile always
 * draws with it, and only the parent PIN reaches the settings that could change that.
 */
val KidsAccent = Color(0xFFFFC83D)
private val KidsColors = darkColorScheme(
    primary = KidsAccent,
    secondary = Color(0xFF4FC3F7),
    background = Color(0xFF14204A),
    surface = Color(0xFF1C2B5E),
    onSurface = Color(0xFFFFFFFF),
    onBackground = Color(0xFFFFFFFF),
)

@Composable
fun BanditBoxTheme(kids: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (kids) KidsColors else Colors, content = content)
}
