package com.spacegoose.banditbox.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.spacegoose.banditbox.layout.PackWallpaper
import com.spacegoose.banditbox.layout.WallpaperStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Draws a built-in wallpaper with vector shapes. Nothing is decoded or cached as a bitmap: the
 * draw is recorded once by Compose and replayed, so a pack wallpaper costs almost no RAM.
 * The same wallpaper always draws the same way (the random seed is its ref).
 */
fun DrawScope.drawPackWallpaper(w: PackWallpaper) {
    if (size.minDimension < 1f) return
    val top = Color(w.top)
    val bottom = Color(w.bottom)
    val primary = Color(w.primary)
    val secondary = Color(w.secondary)
    val rnd = Random(w.ref.hashCode())
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
    when (w.style) {
        WallpaperStyle.STARFIELD -> {
            glow(secondary, Offset(size.width * 0.75f, size.height * 1.1f), size.width * 0.7f, 0.25f)
            stars(rnd, primary, secondary, 160)
        }
        WallpaperStyle.NEBULA -> {
            repeat(7) {
                val c = if (it % 2 == 0) primary else secondary
                glow(c, Offset(size.width * rnd.nextFloat(), size.height * (0.2f + 0.6f * rnd.nextFloat())), size.width * (0.18f + 0.2f * rnd.nextFloat()), 0.22f)
            }
            stars(rnd, Color.White, secondary, 90)
        }
        WallpaperStyle.PLANET -> {
            stars(rnd, Color.White, secondary, 110)
            val center = Offset(size.width * 0.82f, size.height * 1.05f)
            val r = size.height * 0.62f
            drawCircle(Brush.radialGradient(listOf(primary, bottom), center = center + Offset(-r * 0.35f, -r * 0.35f), radius = r * 1.3f), r, center)
            drawCircle(secondary, r, center, alpha = 0.55f, style = Stroke(width = r * 0.012f))
            glow(primary, center, r * 1.35f, 0.18f)
        }
        WallpaperStyle.HILLS -> {
            drawCircle(secondary, size.height * 0.12f, Offset(size.width * 0.7f, size.height * 0.42f), alpha = 0.9f)
            glow(secondary, Offset(size.width * 0.7f, size.height * 0.42f), size.height * 0.45f, 0.25f)
            for (layer in 0 until 4) {
                val base = size.height * (0.55f + layer * 0.12f)
                val color = lerp(primary, bottom, 0.25f + layer * 0.2f)
                hill(rnd, base, size.height * 0.08f, color)
            }
        }
        WallpaperStyle.AURORA -> {
            stars(rnd, Color.White, secondary, 70)
            for (band in 0 until 3) {
                val c = if (band == 1) secondary else primary
                val path = Path()
                val y0 = size.height * (0.18f + band * 0.1f)
                path.moveTo(0f, y0)
                var x = 0f
                while (x <= size.width) {
                    x += size.width / 6f
                    path.cubicTo(x - size.width / 9f, y0 - size.height * 0.12f * rnd.nextFloat(), x - size.width / 18f, y0 + size.height * 0.12f * rnd.nextFloat(), x, y0)
                }
                drawPath(path, Brush.verticalGradient(listOf(c.copy(alpha = 0.45f), Color.Transparent), y0 - size.height * 0.1f, y0 + size.height * 0.25f), style = Stroke(width = size.height * 0.16f))
            }
        }
        WallpaperStyle.WAVES -> {
            glow(secondary, Offset(size.width * 0.5f, size.height * 0.45f), size.width * 0.45f, 0.3f)
            for (i in 0 until 6) {
                val y = size.height * (0.5f + i * 0.09f)
                val path = Path()
                path.moveTo(0f, y)
                val step = size.width / 4f
                for (k in 0 until 4) {
                    val x0 = k * step
                    val amp = size.height * (0.02f + 0.03f * rnd.nextFloat())
                    path.cubicTo(x0 + step / 3f, y - amp, x0 + 2 * step / 3f, y + amp, x0 + step, y)
                }
                path.lineTo(size.width, size.height)
                path.lineTo(0f, size.height)
                path.close()
                drawPath(path, lerp(primary, bottom, 0.3f + i * 0.12f), alpha = 0.85f)
            }
        }
        WallpaperStyle.GRID -> {
            val horizon = size.height * 0.58f
            val sunCenter = Offset(size.width * 0.5f, horizon)
            val sunR = size.height * 0.24f
            drawCircle(Brush.verticalGradient(listOf(secondary, primary), sunCenter.y - sunR, sunCenter.y), sunR, sunCenter)
            for (i in 1..5) {
                val y = sunCenter.y - sunR * i * 0.13f
                drawRect(top.copy(alpha = 0.9f), Offset(sunCenter.x - sunR, y), Size(sunR * 2, sunR * 0.025f * i))
            }
            drawRect(bottom, Offset(0f, horizon), Size(size.width, size.height - horizon))
            val lineW = size.height * 0.003f
            for (i in -14..14) {
                val xFar = size.width / 2 + i * size.width * 0.03f
                val xNear = size.width / 2 + i * size.width * 0.16f
                drawLine(primary, Offset(xFar, horizon), Offset(xNear, size.height), lineW, alpha = 0.6f)
            }
            var t = 0.02f
            while (t < 1f) {
                val y = horizon + (size.height - horizon) * t
                drawLine(primary, Offset(0f, y), Offset(size.width, y), lineW, alpha = 0.6f)
                t *= 1.55f
            }
            glow(primary, Offset(size.width / 2, horizon), size.width * 0.5f, 0.15f)
        }
        WallpaperStyle.HEX -> {
            val r = size.height / 14f
            val dx = r * sqrt(3f)
            var row = 0
            var y = 0f
            while (y < size.height + r) {
                var x = if (row % 2 == 0) 0f else dx / 2
                while (x < size.width + dx) {
                    val a = 0.06f + 0.3f * rnd.nextFloat() * rnd.nextFloat()
                    hexagon(Offset(x, y), r * 0.92f, primary, a, filled = rnd.nextFloat() < 0.06f)
                    x += dx
                }
                y += r * 1.5f
                row++
            }
            glow(secondary, Offset(size.width * 0.15f, size.height * 0.9f), size.width * 0.4f, 0.25f)
            vignette(top)
        }
        WallpaperStyle.CIRCUIT -> {
            val cell = size.height / 18f
            val stroke = size.height * 0.0025f
            repeat(46) {
                var p = Offset((rnd.nextInt((size.width / cell).toInt()) * cell), (rnd.nextInt(18) * cell))
                val color = if (rnd.nextFloat() < 0.75f) primary else secondary
                val alpha = 0.18f + 0.4f * rnd.nextFloat()
                drawCircle(color, stroke * 2.5f, p, alpha = alpha)
                repeat(2 + rnd.nextInt(4)) {
                    val len = cell * (1 + rnd.nextInt(4))
                    val next = when (rnd.nextInt(4)) {
                        0 -> p + Offset(len, 0f)
                        1 -> p + Offset(-len, 0f)
                        2 -> p + Offset(len, len)
                        else -> p + Offset(0f, len)
                    }
                    drawLine(color, p, next, stroke, alpha = alpha)
                    p = next
                }
                drawCircle(color, stroke * 3f, p, alpha = alpha, style = Stroke(stroke))
            }
            vignette(top)
        }
        WallpaperStyle.LASERS -> {
            val origins = listOf(Offset(0f, size.height), Offset(size.width, size.height), Offset(size.width * 0.5f, -size.height * 0.1f))
            for ((n, origin) in origins.withIndex()) {
                val c = if (n == 1) secondary else primary
                repeat(5) {
                    val angle = (rnd.nextFloat() * 0.5f + 0.15f) * PI.toFloat() * (if (origin.x > size.width / 2) -1 else 1) +
                        (if (origin.y < 0) PI.toFloat() / 2 else -PI.toFloat() / 2)
                    val end = origin + Offset(cos(angle) * size.width * 1.5f, sin(angle) * size.width * 1.5f)
                    drawLine(c, origin, end, size.height * 0.02f, alpha = 0.08f)
                    drawLine(c, origin, end, size.height * 0.0025f, alpha = 0.75f)
                }
            }
            glow(primary, Offset(size.width / 2, size.height), size.width * 0.5f, 0.2f)
        }
        WallpaperStyle.FIELD -> {
            val stripes = 10
            for (i in 0 until stripes) {
                if (i % 2 == 0) drawRect(secondary.copy(alpha = 0.18f), Offset(i * size.width / stripes, 0f), Size(size.width / stripes, size.height))
            }
            val line = size.height * 0.004f
            val inset = size.height * 0.08f
            val lineStyle = Stroke(line)
            drawRect(primary, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), alpha = 0.45f, style = lineStyle)
            drawLine(primary, Offset(size.width / 2, inset), Offset(size.width / 2, size.height - inset), line, alpha = 0.45f)
            drawCircle(primary, size.height * 0.16f, Offset(size.width / 2, size.height / 2), alpha = 0.45f, style = lineStyle)
            val boxH = size.height * 0.44f
            val boxW = size.width * 0.12f
            drawRect(primary, Offset(inset, (size.height - boxH) / 2), Size(boxW, boxH), alpha = 0.45f, style = lineStyle)
            drawRect(primary, Offset(size.width - inset - boxW, (size.height - boxH) / 2), Size(boxW, boxH), alpha = 0.45f, style = lineStyle)
            vignette(top)
        }
        WallpaperStyle.BUBBLES -> {
            repeat(40) {
                val c = if (rnd.nextBoolean()) primary else secondary
                val r = size.height * (0.015f + 0.09f * rnd.nextFloat() * rnd.nextFloat())
                val center = Offset(size.width * rnd.nextFloat(), size.height * rnd.nextFloat())
                drawCircle(c, r, center, alpha = 0.12f)
                drawCircle(c, r, center, alpha = 0.45f, style = Stroke(size.height * 0.003f))
                drawCircle(Color.White, r * 0.18f, center + Offset(-r * 0.4f, -r * 0.4f), alpha = 0.35f)
            }
        }
        WallpaperStyle.SPOTLIGHT -> {
            val sources = listOf(0.2f, 0.5f, 0.8f)
            for ((i, sx) in sources.withIndex()) {
                val c = if (i == 1) secondary else primary
                val origin = Offset(size.width * sx, -size.height * 0.05f)
                val spread = size.width * (0.12f + 0.06f * rnd.nextFloat())
                val target = size.width * (sx + (rnd.nextFloat() - 0.5f) * 0.3f)
                val path = Path().apply {
                    moveTo(origin.x - size.width * 0.01f, origin.y)
                    lineTo(origin.x + size.width * 0.01f, origin.y)
                    lineTo(target + spread, size.height)
                    lineTo(target - spread, size.height)
                    close()
                }
                drawPath(path, Brush.verticalGradient(listOf(c.copy(alpha = 0.32f), Color.Transparent), 0f, size.height))
            }
            glow(primary, Offset(size.width / 2, size.height * 1.05f), size.width * 0.45f, 0.18f)
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)

private fun DrawScope.glow(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius), radius, center)
}

private fun DrawScope.stars(rnd: Random, color: Color, accent: Color, count: Int) {
    val unit = size.height / 1080f
    repeat(count) {
        val c = if (rnd.nextFloat() < 0.15f) accent else color
        val r = unit * (0.8f + 2.2f * rnd.nextFloat() * rnd.nextFloat())
        drawCircle(c, r, Offset(size.width * rnd.nextFloat(), size.height * rnd.nextFloat()), alpha = 0.3f + 0.7f * rnd.nextFloat())
    }
}

private fun DrawScope.hill(rnd: Random, base: Float, amp: Float, color: Color) {
    val path = Path()
    path.moveTo(0f, base)
    val step = size.width / 3f
    for (k in 0 until 3) {
        val x0 = k * step
        path.cubicTo(x0 + step / 3f, base - amp * (0.5f + rnd.nextFloat()), x0 + 2 * step / 3f, base + amp * rnd.nextFloat() * 0.5f, x0 + step, base - amp * 0.3f * rnd.nextFloat())
    }
    path.lineTo(size.width, size.height)
    path.lineTo(0f, size.height)
    path.close()
    drawPath(path, color)
}

private fun DrawScope.hexagon(center: Offset, r: Float, color: Color, alpha: Float, filled: Boolean) {
    val path = Path()
    for (i in 0 until 6) {
        val a = PI / 3 * i + PI / 6
        val p = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    if (filled) drawPath(path, color, alpha = alpha * 0.5f)
    drawPath(path, color, alpha = alpha, style = Stroke(size.height * 0.002f))
}

private fun DrawScope.vignette(edge: Color) {
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, edge.copy(alpha = 0.85f)),
            center = Offset(size.width / 2, size.height / 2),
            radius = size.width * 0.75f,
        ),
    )
}
