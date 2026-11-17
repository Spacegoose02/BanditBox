package com.spacegoose.banditbox.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/** A tile image: the app's TV banner when it has one, otherwise its icon. */
class TileImage(val bitmap: Bitmap, val isBanner: Boolean)

/**
 * Loads banners and icons at the exact size they are drawn, into a cache capped in bytes,
 * so the launcher's RAM stays flat however many apps are installed.
 */
class AppVisuals(context: Context, maxBytes: Int = defaultCacheBytes()) {
    private val pm = context.applicationContext.packageManager

    private val cache = object : LruCache<String, TileImage>(maxBytes) {
        override fun sizeOf(key: String, value: TileImage) = value.bitmap.allocationByteCount
    }

    /**
     * The banner scaled to [widthPx] x [heightPx], or, for apps without one, the icon scaled
     * to [iconPx] square. Does disk IO: call off the main thread. Null if the app is gone.
     */
    fun tile(entry: AppEntry, widthPx: Int, heightPx: Int, iconPx: Int): TileImage? {
        val key = "${entry.packageName}/${entry.activityName}@${widthPx}x$heightPx/$iconPx"
        cache.get(key)?.let { return it }

        val activity = try {
            @Suppress("DEPRECATION") // The ComponentInfoFlags overload is API 33+.
            pm.getActivityInfo(ComponentName(entry.packageName, entry.activityName), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val banner = activity.loadBanner(pm)
        val image = if (banner != null) {
            TileImage(banner.toBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888), isBanner = true)
        } else {
            val icon = activity.loadIcon(pm)
            TileImage(icon.toBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888), isBanner = false)
        }
        cache.put(key, image)
        return image
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
