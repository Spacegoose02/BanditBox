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

@Composable
fun BanditBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
