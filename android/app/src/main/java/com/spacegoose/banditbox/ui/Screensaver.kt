package com.spacegoose.banditbox.ui

import android.app.ActivityManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.tv.material3.Text
import com.spacegoose.banditbox.layout.ScreensaverType
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.random.Random

/**
 * An OLED-safe screensaver: black, dim, and nothing stays on the same pixels. The clock jumps
 * to a new place every minute. The star field moves at about 20 frames a second, and on a
 * low-RAM device it falls back to the drifting clock. Any button closes it (the launcher
 * handles the key).
 */
@Composable
fun OledScreensaver(type: ScreensaverType, accent: Color) {
    val context = LocalContext.current
    val lowRam = remember { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
    var area by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { area = it },
    ) {
        when (type) {
            ScreensaverType.OLED_BLACK -> Unit
            ScreensaverType.OLED_STARFIELD -> if (lowRam) DriftingClock(area, accent) else Starfield(accent)
            else -> DriftingClock(area, accent)
        }
    }
}

@Composable
private fun DriftingClock(area: IntSize, accent: Color) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }
    var textSize by remember { mutableStateOf(IntSize.Zero) }
    // A new spot each minute, seeded by the minute so it never sits still for long.
    val spot = remember(now / MINUTE_MS, area, textSize) {
        val rnd = Random(now / MINUTE_MS)
        val freeX = (area.width - textSize.width).coerceAtLeast(1)
        val freeY = (area.height - textSize.height).coerceAtLeast(1)
        IntOffset(rnd.nextInt(freeX), rnd.nextInt(freeY))
    }
    val time = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    Text(
        text = time,
        color = mixGrey(accent).copy(alpha = 0.55f),
        fontSize = 72.sp,
        modifier = Modifier
            .offset { spot }
            .onSizeChanged { textSize = it },
    )
}

/** A slow fly-through: stars come out of the middle and fade in as they near the edges. */
@Composable
private fun Starfield(accent: Color) {
    val stars = remember { Array(STAR_COUNT) { Star.random(Random.Default) } }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        while (true) {
            delay(FRAME_MS)
            val now = withFrameMillis { it }
            val dt = ((now - last).coerceIn(0, 200)) / 1000f
            last = now
            for (i in stars.indices) {
                val s = stars[i]
                s.z -= dt * SPEED
                if (s.z <= 0.05f) stars[i] = Star.random(Random.Default).also { it.z = 1f }
            }
            tick = now
        }
    }
    val starColor = mixGrey(accent)
    Canvas(Modifier.fillMaxSize()) {
        if (tick < 0) return@Canvas // reading tick redraws the stars on every frame
        val cx = size.width / 2
        val cy = size.height / 2
        val unit = size.height / 1080f
        for (s in stars) {
            val x = cx + s.x / s.z * cx
            val y = cy + s.y / s.z * cy
            if (x < 0 || x > size.width || y < 0 || y > size.height) continue
            val near = 1f - s.z
            drawCircle(starColor, radius = unit * (0.6f + 2.4f * near), center = Offset(x, y), alpha = (0.15f + 0.6f * near).coerceIn(0f, 0.75f))
        }
    }
}

private class Star(val x: Float, val y: Float, var z: Float) {
    companion object {
        fun random(rnd: Random) = Star(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, 0.1f + rnd.nextFloat() * 0.9f)
    }
}

/** The accent, toned toward grey, so the screensaver stays dim. */
private fun mixGrey(accent: Color) = Color(
    red = accent.red * 0.5f + 0.25f,
    green = accent.green * 0.5f + 0.25f,
    blue = accent.blue * 0.5f + 0.25f,
)

private const val MINUTE_MS = 60_000L
private const val FRAME_MS = 50L
private const val STAR_COUNT = 120
private const val SPEED = 0.12f

/** True while the launcher is in front: the screensaver clock only runs then. */
@Composable
fun rememberResumed(): State<Boolean> {
    val owner = LocalContext.current as? LifecycleOwner
    val resumed = remember(owner) { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true) }
    DisposableEffect(owner) {
        val lifecycle = owner?.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            resumed.value = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return resumed
}
