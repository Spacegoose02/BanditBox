package com.spacegoose.banditbox.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/** A tile image: the app's TV banner when it has one, otherwise its icon. */
class TileImage(val bitmap: Bitmap, val isBanner: Boolean)

/** How icons are drawn: the theme's icon pack. Banners are never changed. */
sealed interface IconStyle {
    /** Part of the cache key: two styles that draw differently must differ here. */
    val key: String

    /** Each app's own icon. */
    data object System : IconStyle {
        override val key = "system"
    }

    /** Every icon on the same rounded plate; [tint] (ARGB) recolors the icon itself when set. */
    data class Plate(val plate: Int, val tint: Int?) : IconStyle {
        override val key = "plate:$plate:$tint"
    }

    /** An installed icon pack; apps it doesn't cover keep their own icon. */
    data class Pack(val packageName: String) : IconStyle {
        override val key = "pack:$packageName"
    }
}

/**
 * Loads banners and icons at the exact size they are drawn, into a cache capped in bytes,
 * so the launcher's RAM stays flat however many apps are installed.
 */
class AppVisuals(context: Context, maxBytes: Int = defaultCacheBytes()) {
    private val pm = context.applicationContext.packageManager
    val iconPacks = IconPacks(context)

    private val cache = object : LruCache<String, TileImage>(maxBytes) {
        override fun sizeOf(key: String, value: TileImage) = value.bitmap.allocationByteCount
    }

    /**
     * The banner scaled to [widthPx] x [heightPx], or the icon scaled to [iconPx] square when
     * the app has no banner or [preferBanner] is false. Does disk IO: call off the main thread.
     * Null if the app is gone.
     */
    fun tile(
        entry: AppEntry,
        widthPx: Int,
        heightPx: Int,
        iconPx: Int,
        preferBanner: Boolean = true,
        style: IconStyle = IconStyle.System,
    ): TileImage? {
        val key = "${entry.packageName}/${entry.activityName}@${widthPx}x$heightPx/$iconPx/$preferBanner/${style.key}"
        cache.get(key)?.let { return it }

        val activity = try {
            @Suppress("DEPRECATION") // The ComponentInfoFlags overload is API 33+.
            pm.getActivityInfo(ComponentName(entry.packageName, entry.activityName), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val banner = if (preferBanner) activity.loadBanner(pm) else null
        val image = if (banner != null) {
            TileImage(banner.toBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888), isBanner = true)
        } else {
            TileImage(icon(ComponentName(entry.packageName, entry.activityName), { activity.loadIcon(pm) }, iconPx, style), isBanner = false)
        }
        cache.put(key, image)
        return image
    }

    private fun icon(component: ComponentName, own: () -> Drawable, px: Int, style: IconStyle): Bitmap = when (style) {
        IconStyle.System -> own().toBitmap(px, px, Bitmap.Config.ARGB_8888)
        is IconStyle.Pack -> (iconPacks.icon(style.packageName, component) ?: own()).toBitmap(px, px, Bitmap.Config.ARGB_8888)
        is IconStyle.Plate -> plated(own(), px, style)
    }

    /**
     * The icon on a rounded plate in the theme's color. An adaptive icon's own background is
     * dropped and its foreground kept, which is what makes every app look alike.
     */
    private fun plated(drawable: Drawable, px: Int, style: IconStyle.Plate): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.plate }
        val radius = px * 0.24f
        canvas.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), radius, radius, paint)
        val adaptive = drawable as? AdaptiveIconDrawable
        val art = (adaptive?.foreground ?: drawable).mutate()
        if (adaptive != null) {
            // The foreground's visible part is the middle 72 of its 108 units.
            art.setBounds(0, 0, px, px)
        } else {
            val inset = (px * 0.18f).toInt()
            art.setBounds(inset, inset, px - inset, px - inset)
        }
        style.tint?.let { art.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
        art.draw(canvas)
        art.colorFilter = null
        return out
    }

    /** Drop images when Android asks for memory back. */
    fun trim(keepFraction: Float) {
        if (keepFraction <= 0f) cache.evictAll() else cache.trimToSize((cache.maxSize() * keepFraction).toInt())
    }

    companion object {
        private const val CAP_BYTES = 8 * 1024 * 1024

        /** 8 MB, or an eighth of the heap on very small devices. */
        fun defaultCacheBytes(): Int = minOf(CAP_BYTES, (Runtime.getRuntime().maxMemory() / 8).toInt())
    }
}
