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
import com.spacegoose.banditbox.layout.WatchNextKind

/** What the row editor can ask for. */
class RowEditorActions(
    val onAddRow: () -> Unit,
    val onAddWatchNext: () -> Unit,
    val onAddChannel: () -> Unit,
    val onRename: (LayoutRow) -> Unit,
    val onTile: (LayoutRow.Apps, Tile) -> Unit,
    val onContentSize: (LayoutRow, TileSize) -> Unit,
    val onKinds: (LayoutRow.WatchNext, List<WatchNextKind>) -> Unit,
    val onMove: (LayoutRow, Int) -> Unit,
    val onDelete: (LayoutRow) -> Unit,
)

/**
 * One line per row: name, its settings (each press cycles), order, delete. [rowLabel] names
 * what a non-app row shows, such as "Continue watching" or the channel's app.
 */
@Composable
fun RowEditorScreen(rows: List<LayoutRow>, rowLabel: (LayoutRow) -> String?, actions: RowEditorActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.edit_rows), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onAddRow) { Text(stringResource(R.string.add_row)) }
            Button(onClick = actions.onAddWatchNext) { Text(stringResource(R.string.add_watch_next_row)) }
            Button(onClick = actions.onAddChannel) { Text(stringResource(R.string.add_channel_row)) }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(rows, key = { it.id }) { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.width(180.dp)) {
                        Text(
                            text = row.title ?: rowLabel(row) ?: stringResource(R.string.row_untitled),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row !is LayoutRow.Apps) {
                            Text(
                                text = stringResource(row.kindLabel()),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Button(onClick = { actions.onRename(row) }) { Text(stringResource(R.string.rename)) }
                    when (row) {
                        is LayoutRow.Apps -> {
                            Button(onClick = { actions.onTile(row, row.tile.copy(size = row.tile.size.next())) }) {
                                Text(stringResource(row.tile.size.label()))
                            }
                            Button(onClick = { actions.onTile(row, row.tile.copy(style = row.tile.style.next())) }) {
                                Text(stringResource(row.tile.style.label()))
                            }
                            Button(onClick = { actions.onTile(row, row.tile.copy(shape = row.tile.shape.next())) }) {
                                Text(stringResource(row.tile.shape.label()))
                            }
                        }
                        is LayoutRow.WatchNext -> {
                            Button(onClick = { actions.onContentSize(row, row.size.next()) }) { Text(stringResource(row.size.label())) }
                            val preset = KindPreset.of(row.kinds)
                            Button(onClick = { actions.onKinds(row, preset.next().kinds) }) { Text(stringResource(preset.label)) }
                        }
                        is LayoutRow.Channel -> {
                            Button(onClick = { actions.onContentSize(row, row.size.next()) }) { Text(stringResource(row.size.label())) }
                        }
                        is LayoutRow.Widget -> Unit
                    }
                    Button(onClick = { actions.onMove(row, -1) }) { Text(stringResource(R.string.move_up)) }
                    Button(onClick = { actions.onMove(row, +1) }) { Text(stringResource(R.string.move_down)) }
                    Button(onClick = { actions.onDelete(row) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

/** What a Continue watching row includes; each press of its button moves to the next. */
private enum class KindPreset(val kinds: List<WatchNextKind>, @StringRes val label: Int) {
    IN_PROGRESS(listOf(WatchNextKind.CONTINUE, WatchNextKind.NEXT), R.string.kinds_in_progress),
    WITH_NEW(listOf(WatchNextKind.CONTINUE, WatchNextKind.NEXT, WatchNextKind.NEW), R.string.kinds_with_new),
    EVERYTHING(WatchNextKind.entries.toList(), R.string.kinds_everything),
    ;

    companion object {
        /** The preset with exactly these kinds; a custom mix (set in the web UI later) counts as the first. */
        fun of(kinds: List<WatchNextKind>): KindPreset = entries.firstOrNull { it.kinds.toSet() == kinds.toSet() } ?: IN_PROGRESS
    }
}

@StringRes
private fun LayoutRow.kindLabel(): Int = when (this) {
    is LayoutRow.Apps -> R.string.row_kind_apps
    is LayoutRow.WatchNext -> R.string.row_kind_watch_next
    is LayoutRow.Channel -> R.string.row_kind_channel
    is LayoutRow.Widget -> R.string.row_kind_widget
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
