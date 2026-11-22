package com.spacegoose.banditbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.content.TvProgram
import com.spacegoose.banditbox.layout.TileSize

private val CARD_SHAPE = RoundedCornerShape(14.dp)

/** Card height for a content row's size. Width follows each poster's shape. */
private fun TileSize.cardHeight(): Dp = when (this) {
    TileSize.SMALL -> 96.dp
    TileSize.MEDIUM -> 128.dp
    TileSize.LARGE -> 176.dp
}

/** A Continue watching or channel row: a title, then a card per program. */
@Composable
fun ContentRowView(
    title: String,
    size: TileSize,
    programs: List<TvProgram>,
    onOpen: (TvProgram) -> Unit,
    /** Hears which card has focus, for the home screen's featured area. */
    onFocused: (HeroItem) -> Unit = {},
) {
    val loader = Posters.loader(LocalContext.current)
    val height = size.cardHeight()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowHeader(title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            contentPadding = PaddingValues(horizontal = SCREEN_GUTTER, vertical = 14.dp),
        ) {
            items(programs, key = { it.packageName + "/" + it.id }) { program ->
                val width = height * program.aspect.coerceIn(0.6f, 2f)
                Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(
                        onClick = { onOpen(program) },
                        shape = CardDefaults.shape(shape = CARD_SHAPE),
                        scale = BanditCard.scale(),
                        border = BanditCard.border(CARD_SHAPE),
                        glow = BanditCard.glow(),
                        colors = BanditCard.colors(),
                        modifier = Modifier
                            .size(width, height)
                            .onFocusChanged { state ->
                                if (state.hasFocus) {
                                    onFocused(
                                        HeroItem(
                                            key = "program:" + program.packageName + "/" + program.id,
                                            title = program.title,
                                            caption = title,
                                            detail = program.subtitle,
                                            imageUri = program.imageUri,
                                            imageIsBanner = program.imageUri != null,
                                        ),
                                    )
                                }
                            },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (program.imageUri != null) {
                                AsyncImage(
                                    model = program.imageUri,
                                    contentDescription = program.title,
                                    imageLoader = loader,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    text = program.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                                )
                            }
                            program.progress?.let { done ->
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
                                            .fillMaxWidth(done)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = program.subtitle?.let { "${program.title} · $it" } ?: program.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Stands in for a content row's cards until BanditBox may read TV listings. Clicking asks
 * for the permission.
 */
@Composable
fun ContentPermissionRow(title: String, onAllow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowHeader(title)
        Box(Modifier.padding(horizontal = SCREEN_GUTTER, vertical = 14.dp)) {
            Card(
                onClick = onAllow,
                shape = CardDefaults.shape(shape = CARD_SHAPE),
                scale = BanditCard.scale(),
                border = BanditCard.border(CARD_SHAPE),
                glow = BanditCard.glow(),
                colors = BanditCard.colors(),
                modifier = Modifier.size(360.dp, 96.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Text(text = stringResource(R.string.content_allow_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.content_allow_text),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
