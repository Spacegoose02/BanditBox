package com.spacegoose.banditbox.ui

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.spacegoose.banditbox.layout.ScreensaverType
import com.spacegoose.banditbox.layout.WidgetCard
import kotlinx.coroutines.delay
import java.util.Date

private const val PHOTO_MS = 20_000L

/**
 * Ambient mode: the pictures of a photo feed (Immich, Plex), one after another with a slow zoom
 * and a crossfade, and the time with where and when the photo was taken. The corner the text
 * sits in changes with every photo. With no pictures yet it falls back to the drifting clock.
 */
@Composable
fun PhotoScreensaver(cards: List<WidgetCard>, image: (String?) -> String?, accent: Color) {
    val photos = remember(cards) { cards.filter { image(it.image) != null }.shuffled() }
    if (photos.isEmpty()) return OledScreensaver(ScreensaverType.OLED_DRIFT, accent)
    val context = LocalContext.current
    val loader = remember { Posters.loader(context) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(photos) {
        while (true) {
            // Fetch the next picture while this one shows.
            val next = photos[(index + 1) % photos.size]
            loader.enqueue(ImageRequest.Builder(context).data(image(next.image)).build())
            delay(PHOTO_MS)
            index = (index + 1) % photos.size
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = index, animationSpec = tween(1_500), label = "photo") { i ->
            val card = photos[i % photos.size]
            val zoom = remember(i) { Animatable(1f) }
            LaunchedEffect(i) { zoom.animateTo(1.06f, tween(PHOTO_MS.toInt() + 1_500, easing = LinearEasing)) }
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = image(card.image),
                    contentDescription = card.title,
                    imageLoader = loader,
                    // Fill the screen with landscape photos; show portrait ones whole.
                    contentScale = if ((card.imageAspect ?: 1.78f) >= 1.2f) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().scale(zoom.value),
                )
                Caption(card, corner = i % 2)
            }
        }
    }
}

@Composable
private fun Caption(card: WidgetCard, corner: Int) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    val time = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    val align = if (corner == 0) Alignment.BottomStart else Alignment.BottomEnd
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))),
    ) {
        Column(
            modifier = Modifier.align(align).padding(horizontal = 56.dp, vertical = 40.dp),
            horizontalAlignment = if (corner == 0) Alignment.Start else Alignment.End,
        ) {
            Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 56.sp)
            val about = listOfNotNull(card.title.takeIf { it.isNotBlank() }, card.subtitle).joinToString(" · ")
            if (about.isNotEmpty()) Text(about, color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp)
        }
    }
}
