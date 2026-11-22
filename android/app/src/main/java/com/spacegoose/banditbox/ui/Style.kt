package com.spacegoose.banditbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.CardScale
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Space between the screen edge and the content: TV overscan safe, and the rows' start line. */
val SCREEN_GUTTER = 56.dp

/** Space between rows on the home screen. */
val ROW_GAP = 28.dp

/** Space between cards in a row. */
val CARD_GAP = 20.dp

/** How much a focused card grows. Small enough that neighbours never overlap. */
private const val FOCUSED_SCALE = 1.08f

/**
 * The look every card on the home screen shares: it grows a little when focused, gains an
 * accent border and a soft accent glow, and sits on a faint glass surface when it has no art.
 */
object BanditCard {
    @Composable
    fun scale(): CardScale = CardDefaults.scale(focusedScale = FOCUSED_SCALE, pressedScale = 1.02f)

    @Composable
    fun border(shape: Shape): CardBorder = CardDefaults.border(
        border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = shape),
        focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), inset = 0.dp, shape = shape),
        pressedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), inset = 0.dp, shape = shape),
    )

    @Composable
    fun glow(): CardGlow = CardDefaults.glow(
        focusedGlow = Glow(elevationColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), elevation = 18.dp),
    )

    @Composable
    fun colors(): CardColors = CardDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** A row's title, lined up with the first card. */
@Composable
fun RowHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = SCREEN_GUTTER, end = SCREEN_GUTTER),
    )
}
