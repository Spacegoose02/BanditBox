package com.spacegoose.banditbox.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.spacegoose.banditbox.layout.ThemeColorMath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A wallpaper picture ready to draw, and the accent picked from it (null for a grey picture). */
class LoadedPicture(val bitmap: Bitmap, val accent: Int?)

/**
 * Wallpaper pictures the user added on this TV. Each is stored once, by the SHA-256 of its
 * bytes, as media:sha256-<hex>: the same address the server uses, so a later sync can upload
 * it without renaming anything. Pictures are shrunk to 1920x1080 when added and drawn at
 * 1280x720, so one wallpaper holds under 4 MB of RAM. Everything here does IO: call it off the
 * main thread.
 */
class WallpaperFiles(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "media")

    /** Adds a picture from the file picker. Returns its hash. */
    fun importUri(uri: Uri): String =
        importFrom { appContext.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $uri") }

    /** Downloads a picture. Only https and http links; Android blocks plain http by default. */
    fun importUrl(link: String): String {
        val url = try {
            URL(link.trim())
        } catch (e: java.net.MalformedURLException) {
            throw IOException("That isn't a web address", e)
        }
        if (url.protocol != "https" && url.protocol != "http") throw IOException("Only https:// links work")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("The server answered ${conn.responseCode}")
            if (conn.contentLengthLong > MAX_DOWNLOAD) throw IOException("The picture is over 25 MB")
            return conn.inputStream.use { input -> importFrom { CappedStream(input, MAX_DOWNLOAD) } }
        } finally {
            conn.disconnect()
        }
    }

    /** Hashes of the stored pictures, newest first. */
    fun list(): List<String> =
        dir.listFiles().orEmpty()
            .filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
            .sortedByDescending { it.lastModified() }
            .map { it.name.removePrefix(PREFIX).removeSuffix(SUFFIX) }

    fun exists(sha256: String): Boolean = fileOf(sha256).isFile

    /** The picture sized for drawing, or null when it's missing or unreadable. */
    fun load(sha256: String): LoadedPicture? {
        val file = fileOf(sha256)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, DRAW_W, DRAW_H)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val bitmap = fitWithin(decoded, DRAW_W, DRAW_H)
        return LoadedPicture(bitmap, accentOf(bitmap))
    }

    /**
     * Deletes pictures no profile uses, keeping the [keepRecent] newest so a recent choice can
     * be picked again.
     */
    fun prune(inUse: Set<String>, keepRecent: Int = 8) {
        list().drop(keepRecent).filter { it !in inUse }.forEach { fileOf(it).delete() }
    }

    fun delete(sha256: String) {
        fileOf(sha256).delete()
    }

    private fun importFrom(open: () -> InputStream): String {
        // Read once into memory (capped), so a stream that can't be reopened (a download) works.
        val bytes = open().use { CappedStream(it, MAX_DOWNLOAD).readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That isn't a picture Android can read")
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) throw IOException("The picture is too large")
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, STORE_W, STORE_H) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("That isn't a picture Android can read")
        val scaled = fitWithin(decoded, STORE_W, STORE_H)
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        scaled.recycle()
        val sha = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
        val target = fileOf(sha)
        if (!target.isFile) {
            dir.mkdirs()
            val tmp = File(dir, "$sha.tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Couldn't save the picture")
            }
        } else {
            target.setLastModified(System.currentTimeMillis())
        }
        return sha
    }

    private fun fileOf(sha256: String): File {
        require(SHA.matches(sha256)) { "Not a SHA-256: $sha256" }
        return File(dir, PREFIX + sha256 + SUFFIX)
    }

    /** Stops reading after [max] bytes, so a huge download can't fill the RAM. */
    private class CappedStream(private val inner: InputStream, private val max: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int {
            val b = inner.read()
            if (b >= 0 && ++count > max) throw IOException("The picture is over 25 MB")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > max) throw IOException("The picture is over 25 MB")
            }
            return n
        }

        override fun close() = inner.close()
    }

    companion object {
        private const val PREFIX = "sha256-"
        private const val SUFFIX = ".jpg"
        private const val STORE_W = 1920
        private const val STORE_H = 1080
        private const val DRAW_W = 1280
        private const val DRAW_H = 720
        private const val MAX_DOWNLOAD = 25L * 1024 * 1024
        private const val MAX_PIXELS = 100_000_000L
        private val SHA = Regex("^[0-9a-f]{64}$")

        /** The largest power-of-two shrink that still covers [w] x [h]. */
        private fun sampleSize(width: Int, height: Int, w: Int, h: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= w && height / (sample * 2) >= h) sample *= 2
            return sample
        }

        /** Shrinks to cover [w] x [h] at most, keeping the aspect ratio. */
        private fun fitWithin(bitmap: Bitmap, w: Int, h: Int): Bitmap {
            val scale = minOf(1f, maxOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height))
            if (scale >= 1f) return bitmap
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }

        private fun accentOf(bitmap: Bitmap): Int? {
            val small = Bitmap.createScaledBitmap(bitmap, 48, 27, true)
            val pixels = IntArray(48 * 27)
            small.getPixels(pixels, 0, 48, 0, 0, 48, 27)
            if (small !== bitmap) small.recycle()
            return ThemeColorMath.pickAccent(pixels)
        }
    }
}
