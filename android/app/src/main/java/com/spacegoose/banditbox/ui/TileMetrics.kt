package com.spacegoose.banditbox.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.spacegoose.banditbox.layout.Tile
import com.spacegoose.banditbox.layout.TileShape
import com.spacegoose.banditbox.layout.TileSize
import com.spacegoose.banditbox.layout.TileStyle

/** On-screen size of a tile. Banners are 16:9, icon tiles are square. */
fun Tile.dpSize(): DpSize = when (style) {
    TileStyle.BANNER -> {
        val width = when (size) {
            TileSize.SMALL -> 128.dp
            TileSize.MEDIUM -> 168.dp
            TileSize.LARGE -> 224.dp
        }
        DpSize(width, width * 9f / 16f)
    }
    TileStyle.ICON -> {
        val side = when (size) {
            TileSize.SMALL -> 64.dp
            TileSize.MEDIUM -> 88.dp
            TileSize.LARGE -> 112.dp
        }
        DpSize(side, side)
    }
}

fun Tile.composeShape(): Shape = when (shape) {
    TileShape.ROUNDED -> RoundedCornerShape(12.dp)
    TileShape.SQUARE -> RectangleShape
    TileShape.CIRCLE -> CircleShape
}

/** Tiles on the All apps screen and inside folders. */
val GRID_TILE = Tile(size = TileSize.MEDIUM, style = TileStyle.BANNER, shape = TileShape.ROUNDED)
