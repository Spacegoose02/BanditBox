package com.spacegoose.banditbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.spacegoose.banditbox.layout.WidgetCard
import com.spacegoose.banditbox.layout.WidgetFeed
import com.spacegoose.banditbox.layout.WidgetFeeds
import com.spacegoose.banditbox.layout.WidgetTeam
import com.spacegoose.banditbox.layout.WidgetWeather
import kotlin.math.roundToInt

private val CARD_SHAPE = RoundedCornerShape(12.dp)
private val CARD_HEIGHT = 128.dp
private val INFO_CARD_HEIGHT = 112.dp

/** What widget rows and top bar widgets need: the feeds, who may see them, and what a card does. */
class WidgetState(
    /** Every feed this TV has, by id: from the server's connectors and from apps on the TV. */
    val feeds: Map<String, WidgetFeed>,
    val kids: Boolean,
    /** Which apps' cards the open profile may show. */
    val allowed: (String) -> Boolean,
    /** Turns a card's image into something the image loader can fetch, or null. */
    val image: (String?) -> String?,
    val onOpen: (WidgetCard, WidgetFeed) -> Unit,
    /** Long-press: the card's own actions. */
    val onMenu: (WidgetCard, WidgetFeed) -> Unit,
) {
    /** The feed [id] as the open profile may see it, or null. */
    fun feed(id: String): WidgetFeed? = WidgetFeeds.visible(feeds, id, kids, allowed)

    companion object {
        val NONE = WidgetState(emptyMap(), false, { false }, { null }, { _, _ -> }, { _, _ -> })
    }
}

/** A widget row: a title, then the feed's cards drawn with its template. */
@Composable
fun WidgetRowView(title: String, feed: WidgetFeed, state: WidgetState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            // Card ids key the row, so a feed that repeats one must not crash it.
            items(feed.cards.distinctBy { it.id }, key = { it.id }) { card ->
                val open = { state.onOpen(card, feed) }
                val menu = if (card.actions.isEmpty()) null else ({ state.onMenu(card, feed) })
                when (feed.template) {
                    WidgetFeed.SCOREBOARD -> ScoreCard(card, state, open, menu)
                    WidgetFeed.WEATHER -> InfoCard(open, menu) { WeatherBody(card) }
                    WidgetFeed.STAT -> InfoCard(open, menu) { StatBody(card) }
                    else -> PosterCard(card, state, open, menu)
                }
            }
        }
    }
}

@Composable
private fun PosterCard(card: WidgetCard, state: WidgetState, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val loader = Posters.loader(LocalContext.current)
    val image = state.image(card.image)
    val aspect = if (image == null) 1.78f else (card.imageAspect ?: 1.78f).coerceIn(0.6f, 2f)
    val width = CARD_HEIGHT * aspect
    Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = CardDefaults.shape(shape = CARD_SHAPE),
            modifier = Modifier.size(width, CARD_HEIGHT),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = card.title,
                        imageLoader = loader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    )
                }
                card.badge?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
                card.progress?.let { done ->
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(done.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
        Text(
            text = card.subtitle?.let { "${card.title} · $it" } ?: card.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .background(Color(0xFFD93025), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A fixed-size card for stats, weather and scores. */
@Composable
private fun InfoCard(onClick: () -> Unit, onLongClick: (() -> Unit)?, width: Int = 280, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CardDefaults.shape(shape = CARD_SHAPE),
        modifier = Modifier.size(width.dp, INFO_CARD_HEIGHT),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
private fun ScoreCard(card: WidgetCard, state: WidgetState, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val score = card.score
    InfoCard(onClick, onLongClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (score == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.badge?.let { Badge(it) }
                    Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                card.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            } else {
                TeamLine(score.away, state, bold = score.state != "pre" && score.away.winner)
                TeamLine(score.home, state, bold = score.state != "pre" && score.home.winner)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.badge?.let { Badge(it) }
                    Text(
                        text = listOfNotNull(score.detail, score.broadcast).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamLine(team: WidgetTeam, state: WidgetState, bold: Boolean) {
    val loader = Posters.loader(LocalContext.current)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val logo = state.image(team.logo)
        if (logo != null) {
            AsyncImage(model = logo, contentDescription = null, imageLoader = loader, modifier = Modifier.size(22.dp))
        } else {
            Box(Modifier.size(22.dp).background(team.color?.let(::parseColor) ?: Color.Gray, RoundedCornerShape(11.dp)))
        }
        Text(
            text = (team.rank?.let { "#$it " } ?: "") + (team.abbr ?: team.name),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = team.score ?: team.record.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun parseColor(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

@Composable
private fun WeatherBody(card: WidgetCard) {
    val w = card.weather
    if (w == null) {
        StatBody(card)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(weatherEmoji(w.icon, w.isDay), style = MaterialTheme.typography.displaySmall)
        Column {
            Text(temp(w.temp), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = listOfNotNull(w.label, highLow(w)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = w.alert ?: card.subtitle ?: card.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (w.alert != null) Color(0xFFFFB300) else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatBody(card: WidgetCard) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        card.stat?.let { s ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.value, style = MaterialTheme.typography.headlineMedium)
                s.unit?.let { Text(" $it", style = MaterialTheme.typography.titleSmall) }
                when (s.trend) {
                    "up" -> Text(" ▲", style = MaterialTheme.typography.titleSmall)
                    "down" -> Text(" ▼", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        card.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A feed in the top bar, next to the clock: the weather now, or a stat. Not focusable. */
@Composable
fun TopBarFeed(feed: WidgetFeed) {
    val card = feed.cards.firstOrNull() ?: return
    val w = card.weather
    if (w != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(weatherEmoji(w.icon, w.isDay), style = MaterialTheme.typography.headlineSmall)
            Column {
                Text(temp(w.temp), style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = w.alert ?: listOfNotNull(w.label, highLow(w)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (w.alert != null) Color(0xFFFFB300) else Color.Unspecified,
                    maxLines = 1,
                )
            }
        }
    } else if (card.stat != null) {
        Column {
            Text(card.stat!!.value + (card.stat!!.unit?.let { " $it" } ?: ""), style = MaterialTheme.typography.headlineMedium)
            Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

private fun temp(t: Double) = "${t.roundToInt()}°"

private fun highLow(w: WidgetWeather): String? {
    val h = w.high ?: return null
    val l = w.low ?: return null
    return "${h.roundToInt()}° / ${l.roundToInt()}°"
}

/** Weather icons as emoji: no picture files, and they scale with the text. */
fun weatherEmoji(icon: String, isDay: Boolean): String = when (icon) {
    "clear" -> if (isDay) "☀️" else "🌙"
    "partly-cloudy" -> if (isDay) "⛅" else "☁️"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "drizzle" -> "🌦️"
    "rain" -> "🌧️"
    "sleet" -> "🌨️"
    "snow" -> "❄️"
    "thunder" -> "⛈️"
    else -> "🌡️"
}
