package com.spacegoose.banditbox.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.content.TvProgram
import com.spacegoose.banditbox.layout.SearchMatch
import com.spacegoose.banditbox.layout.WidgetCard
import com.spacegoose.banditbox.layout.WidgetFeed
import com.spacegoose.banditbox.system.AppSearch

private const val MAX_QUERY = 80
private const val MAX_APPS = 12
private const val MAX_ITEMS = 30

/** Everything the search screen looks through, already limited to what the open profile may see. */
class SearchSources(
    val apps: List<AppEntry>,
    /** Continue watching and channel cards from TV listings. */
    val programs: List<TvProgram>,
    /** Cards from widget feeds. */
    val cards: List<Pair<WidgetCard, WidgetFeed>>,
    /** Apps that take a typed search, offered as "Search in …". */
    val searchIn: List<AppEntry>,
    /** Offer the TV's own search across apps (not on kids profiles). */
    val global: Boolean,
    val appLabel: (String) -> String,
)

/** One result that isn't an app tile. */
private class Hit(val title: String, val detail: String?, val open: () -> Unit)

/**
 * One search across the apps on the TV, the shows in Continue watching and app channels, the
 * cards in widget feeds, and, last, a hand-off to an app's own search with the same words.
 */
@Composable
fun SearchScreen(
    sources: SearchSources,
    visuals: AppVisuals,
    actions: TileActions,
    onProgram: (TvProgram) -> Unit,
    onCard: (WidgetCard, WidgetFeed) -> Unit,
    launch: (List<Intent>) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val voicePrompt = stringResource(R.string.search_hint)
    val hasVoice = remember { AppSearch.hasVoice(context.packageManager) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it.take(MAX_QUERY) }
        }
    }

    val apps = remember(query, sources) { SearchMatch.rank(query, sources.apps, MAX_APPS) { listOf(it.label) } }
    val hits = remember(query, sources) {
        val shows = SearchMatch.rank(query, sources.programs, MAX_ITEMS) { listOf(it.title, it.subtitle) }
            .distinctBy { it.title to it.subtitle }
            .map { p -> Hit(p.title, listOfNotNull(p.subtitle, sources.appLabel(p.packageName)).joinToString(" · ")) { onProgram(p) } }
        val cards = SearchMatch.rank(query, sources.cards, MAX_ITEMS) { (card, _) -> listOf(card.title, card.subtitle) }
            .map { (card, feed) -> Hit(card.title, listOfNotNull(card.subtitle, feed.name).joinToString(" · ")) { onCard(card, feed) } }
        shows + cards
    }
    val words = query.trim()
    val searchInLabel = stringResource(R.string.search_in)
    val everywhereLabel = stringResource(R.string.search_everywhere)
    val handOffs = remember(words, sources, searchInLabel, everywhereLabel) {
        if (words.isEmpty()) {
            emptyList()
        } else {
            val global = if (sources.global) listOf(Hit(everywhereLabel, null) { launch(listOf(AppSearch.global(words))) }) else emptyList()
            global + sources.searchIn.map { app ->
                Hit(searchInLabel.format(app.label), null) { launch(listOf(AppSearch.inApp(app.packageName, words))) }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(MAX_QUERY) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = onSurface),
                cursorBrush = SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(voicePrompt, style = MaterialTheme.typography.headlineSmall, color = onSurface.copy(alpha = 0.5f))
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (hasVoice) {
                Button(onClick = {
                    try {
                        voice.launch(AppSearch.voice(voicePrompt))
                    } catch (_: ActivityNotFoundException) {
                        // Checked above; an app can still go away in between.
                    }
                }) { Text(stringResource(R.string.search_voice)) }
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        ) {
            if (words.isNotEmpty() && apps.isEmpty() && hits.isEmpty()) {
                item { Text(stringResource(R.string.search_nothing, words), style = MaterialTheme.typography.bodyLarge) }
            }
            if (apps.isNotEmpty()) {
                item { Heading(stringResource(R.string.search_apps)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(12.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            AppTile(
                                app = app,
                                visuals = visuals,
                                spec = GRID_TILE,
                                onClick = { actions.onOpen(app) },
                                onLongClick = { actions.onAppMenu(app, Place.AllApps) },
                            )
                        }
                    }
                }
            }
            if (hits.isNotEmpty()) {
                item { Heading(stringResource(R.string.search_shows)) }
                items(hits) { HitItem(it) }
            }
            if (handOffs.isNotEmpty()) {
                item { Heading(stringResource(R.string.search_more)) }
                items(handOffs) { HitItem(it) }
            }
        }
    }
    LaunchedEffect(Unit) { focusWhenReady(field) }
}

@Composable
private fun Heading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HitItem(hit: Hit) {
    ListItem(
        selected = false,
        onClick = hit.open,
        headlineContent = { Text(hit.title) },
        supportingContent = hit.detail?.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
    )
}
