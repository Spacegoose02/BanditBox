package com.spacegoose.banditbox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.layout.Row as LayoutRow
import com.spacegoose.banditbox.layout.Tile
import com.spacegoose.banditbox.layout.TileShape
import com.spacegoose.banditbox.layout.TileSize
import com.spacegoose.banditbox.layout.TileStyle

/** What the row editor can ask for. */
class RowEditorActions(
    val onAddRow: () -> Unit,
    val onRename: (LayoutRow.Apps) -> Unit,
    val onTile: (LayoutRow.Apps, Tile) -> Unit,
    val onMove: (LayoutRow.Apps, Int) -> Unit,
    val onDelete: (LayoutRow.Apps) -> Unit,
)

/** One line per row: name, tile size / style / shape (each press cycles), order, delete. */
@Composable
fun RowEditorScreen(rows: List<LayoutRow.Apps>, actions: RowEditorActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.edit_rows), style = MaterialTheme.typography.headlineSmall)
        Button(onClick = actions.onAddRow) { Text(stringResource(R.string.add_row)) }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(rows, key = { it.id }) { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = row.title ?: stringResource(R.string.row_untitled),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(180.dp),
                    )
                    Button(onClick = { actions.onRename(row) }) { Text(stringResource(R.string.rename)) }
                    Button(onClick = { actions.onTile(row, row.tile.copy(size = row.tile.size.next())) }) {
                        Text(stringResource(row.tile.size.label()))
                    }
                    Button(onClick = { actions.onTile(row, row.tile.copy(style = row.tile.style.next())) }) {
                        Text(stringResource(row.tile.style.label()))
                    }
                    Button(onClick = { actions.onTile(row, row.tile.copy(shape = row.tile.shape.next())) }) {
                        Text(stringResource(row.tile.shape.label()))
                    }
                    Button(onClick = { actions.onMove(row, -1) }) { Text(stringResource(R.string.move_up)) }
                    Button(onClick = { actions.onMove(row, +1) }) { Text(stringResource(R.string.move_down)) }
                    Button(onClick = { actions.onDelete(row) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

private inline fun <reified E : Enum<E>> E.next(): E {
    val all = enumValues<E>()
    return all[(ordinal + 1) % all.size]
}

@StringRes
private fun TileSize.label() = when (this) {
    TileSize.SMALL -> R.string.size_small
    TileSize.MEDIUM -> R.string.size_medium
    TileSize.LARGE -> R.string.size_large
}

@StringRes
private fun TileStyle.label() = when (this) {
    TileStyle.BANNER -> R.string.style_banner
    TileStyle.ICON -> R.string.style_icon
}

@StringRes
private fun TileShape.label() = when (this) {
    TileShape.ROUNDED -> R.string.shape_rounded
    TileShape.SQUARE -> R.string.shape_square
    TileShape.CIRCLE -> R.string.shape_circle
}
