package com.spacegoose.banditbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.content.ContentSelect
import com.spacegoose.banditbox.content.TvProgram
import com.spacegoose.banditbox.content.TvSnapshot
import com.spacegoose.banditbox.layout.ContentRow
import com.spacegoose.banditbox.layout.HomeRow
import com.spacegoose.banditbox.layout.ItemRef
import com.spacegoose.banditbox.layout.ResolvedAppsRow
import com.spacegoose.banditbox.layout.ResolvedItem
import com.spacegoose.banditbox.layout.WidgetFeed

/** Where a tile was long-pressed, which decides what its menu offers. */
sealed interface Place {
    data class InRow(val rowId: String) : Place
    data object AllApps : Place
    data class InFolder(val folderId: String) : Place
}

/** Callbacks shared by every place an app or folder tile appears. */
class TileActions(
    val onOpen: (AppEntry) -> Unit,
    val onAppMenu: (AppEntry, Place) -> Unit,
    val onFolderOpen: (ResolvedItem.FolderItem) -> Unit,
    val onFolderMenu: (ResolvedItem.FolderItem, String) -> Unit,
)

/** What Continue watching and channel rows need to draw. */
class ContentState(
    val tv: TvSnapshot,
    /** Which apps' cards the open profile may show. */
    val allowed: (String) -> Boolean,
    /** The row's own title, or a default such as "Continue watching" or the app's name. */
    val titleFor: (ContentRow) -> String,
    val onOpen: (TvProgram) -> Unit,
    /** Ask for permission to read TV listings. */
    val onAllow: () -> Unit,
)

/** A row as drawn: app tiles, a content row's cards, or the card that asks for permission. */
private sealed interface Drawn {
    val key: String

    class Apps(val row: ResolvedAppsRow) : Drawn {
        override val key: String get() = row.id
    }

    class Cards(val row: ContentRow, val programs: List<TvProgram>) : Drawn {
        override val key: String get() = row.id
    }

    class AskPermission(val row: ContentRow) : Drawn {
        override val key: String get() = row.id
    }

    class Widget(val id: String, val title: String, val feed: WidgetFeed) : Drawn {
        override val key: String get() = id
    }
}

/**
 * Content rows with nothing to show are skipped, like empty app rows. Until BanditBox may
 * read TV listings, only the first content row is drawn, as a card asking for permission.
 */
private fun drawnRows(rows: List<HomeRow>, content: ContentState, widgets: WidgetState): List<Drawn> {
    var asked = false
    return rows.mapNotNull { row ->
        when (row) {
            is HomeRow.Apps -> Drawn.Apps(row.row)
            is HomeRow.Content -> when {
                !content.tv.granted -> if (asked) null else Drawn.AskPermission(row.row).also { asked = true }
                else -> ContentSelect.cards(row.row, content.tv, content.allowed)
                    .takeIf { it.isNotEmpty() }
                    ?.let { Drawn.Cards(row.row, it) }
            }
            // A feed the server or the app hasn't sent (yet), or with no cards, is skipped.
            is HomeRow.Widget -> widgets.feed(row.row.feed)
                ?.takeIf { it.cards.isNotEmpty() }
                ?.let { Drawn.Widget(row.id, row.row.title ?: it.name, it) }
        }
    }
}

/** The main screen: top bar, then the layout's rows. */
@Composable
fun HomeScreen(
    rows: List<HomeRow>,
    apps: Map<String, AppEntry>,
    visuals: AppVisuals,
    content: ContentState,
    widgets: WidgetState,
    topBar: @Composable () -> Unit,
    actions: TileActions,
    modifier: Modifier = Modifier,
) {
    val drawn = remember(rows, content.tv, content.allowed, widgets) { drawnRows(rows, content, widgets) }
    // Start with the first tile focused, so the first D-pad press moves instead of only highlighting.
    val firstTile = remember { FocusRequester() }
    val firstAppsRow = drawn.indexOfFirst { it is Drawn.Apps && it.row.firstShown(apps) >= 0 }
    LaunchedEffect(firstAppsRow >= 0) { if (firstAppsRow >= 0) focusWhenReady(firstTile) }
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        topBar()
        if (drawn.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_home),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        ) {
            itemsIndexed(drawn, key = { _, row -> row.key }) { index, row ->
                when (row) {
                    is Drawn.Apps -> AppsRow(row.row, apps, visuals, actions, if (index == firstAppsRow) firstTile else null)
                    is Drawn.Cards -> ContentRowView(content.titleFor(row.row), row.row.size, row.programs, content.onOpen)
                    is Drawn.AskPermission -> ContentPermissionRow(content.titleFor(row.row), content.onAllow)
                    is Drawn.Widget -> WidgetRowView(row.title, row.feed, widgets)
                }
            }
        }
    }
}

@Composable
private fun AppsRow(
    row: ResolvedAppsRow,
    apps: Map<String, AppEntry>,
    visuals: AppVisuals,
    actions: TileActions,
    /** Put on the row's first shown tile, when this row gets focus at start. */
    firstTile: FocusRequester?,
) {
    val first = row.firstShown(apps)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        row.title?.let { Text(text = it, style = MaterialTheme.typography.titleMedium) }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Room for the focused tile to scale up without clipping.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            itemsIndexed(row.items, key = { _, item -> item.key() }) { index, item ->
                val focus = if (firstTile != null && index == first) Modifier.focusRequester(firstTile) else Modifier
                when (item) {
                    is ResolvedItem.App -> apps[item.packageName]?.let { app ->
                        AppTile(
                            app = app,
                            visuals = visuals,
                            spec = row.tile,
                            onClick = { actions.onOpen(app) },
                            onLongClick = { actions.onAppMenu(app, Place.InRow(row.id)) },
                            modifier = focus,
                        )
                    }
                    is ResolvedItem.FolderItem -> FolderTile(
                        folder = item,
                        spec = row.tile,
                        onClick = { actions.onFolderOpen(item) },
                        onLongClick = { actions.onFolderMenu(item, row.id) },
                        modifier = focus,
                    )
                }
            }
        }
    }
}

private fun ResolvedItem.key(): String = rawRef()

/** Index of the first item that is drawn (an app that is gone draws nothing), or -1. */
private fun ResolvedAppsRow.firstShown(apps: Map<String, AppEntry>): Int =
    items.indexOfFirst { it !is ResolvedItem.App || it.packageName in apps }

/** The item as it is written in the layout document: a package name or folder:<id>. */
fun ResolvedItem.rawRef(): String = when (this) {
    is ResolvedItem.App -> packageName
    is ResolvedItem.FolderItem -> ItemRef.folder(id)
}
