package com.spacegoose.banditbox.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.spacegoose.banditbox.R

/** Height of the featured area at the top of the home screen. */
val HERO_HEIGHT = 176.dp

private val ART_SHAPE = RoundedCornerShape(18.dp)

/**
 * What the featured area shows: the focused app or program. [image] is an app's banner or
 * icon already in memory; [imageUri] is a program's poster, loaded through Coil.
 */
@Immutable
class HeroItem(
    val key: String,
    val title: String,
    /** Small accent line above the title, such as the row's name. */
    val caption: String?,
    /** A line under the title, such as a show's episode. */
    val detail: String? = null,
    val image: ImageBitmap? = null,
    /** False when [image] is an icon, not a 16:9 banner. */
    val imageIsBanner: Boolean = true,
    val imageUri: Any? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is HeroItem && other.key == key && other.image === image && other.imageUri == imageUri && other.title == title
    override fun hashCode(): Int = key.hashCode()
}

/**
 * The featured area: title and caption on the left, the art on the right, and the same art
 * stretched and blurred behind everything as a soft wash. Crossfades as focus moves.
 */
@Composable
fun HeroBanner(item: HeroItem?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        Crossfade(targetState = item, animationSpec = tween(durationMillis = 380), label = "hero") { shown ->
            if (shown != null) HeroContent(shown)
        }
    }
}

@Composable
private fun HeroContent(item: HeroItem) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        // The wash: the art, blurred (Android 12+) and faded out to the left and bottom, so it
        // tints the screen without fighting the text.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.75f)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen, alpha = if (CAN_BLUR) 0.55f else 0.22f)
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.6f to Color.Black), blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            if (item.imageIsBanner) {
                HeroArt(item, Modifier.fillMaxSize().blur(36.dp, BlurredEdgeTreatment.Rectangle))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent))))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.55f)
                .padding(start = SCREEN_GUTTER),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item.caption?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.detail ?: stringResource(R.string.hero_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // The art itself, sharp, as a floating card.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = SCREEN_GUTTER, top = 12.dp, bottom = 12.dp)
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .shadow(elevation = 24.dp, shape = ART_SHAPE, ambientColor = accent, spotColor = accent)
                .clip(ART_SHAPE)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.imageIsBanner) {
                HeroArt(item, Modifier.fillMaxSize())
            } else {
                HeroArt(item, Modifier.fillMaxHeight(0.6f).aspectRatio(1f), ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun HeroArt(item: HeroItem, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    when {
        item.image != null -> Image(bitmap = item.image, contentDescription = null, contentScale = scale, modifier = modifier)
        item.imageUri != null -> AsyncImage(
            model = item.imageUri,
            contentDescription = null,
            imageLoader = Posters.loader(LocalContext.current),
            contentScale = scale,
            modifier = modifier,
        )
    }
}

/** RenderEffect blur needs Android 12. Older TVs get a fainter, unblurred wash. */
private val CAN_BLUR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
