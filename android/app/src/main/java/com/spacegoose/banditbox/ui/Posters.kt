package com.spacegoose.banditbox.ui

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import com.spacegoose.banditbox.BanditBoxApplication
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * The image loader for posters on Continue watching, channel and widget rows. Built the first time a
 * poster is drawn, so a home screen with no content rows never pays for it. Images are decoded
 * at card size, in RGB 565 where they have no transparency, and the memory cache is capped.
 */
object Posters {
    private const val MEMORY_CACHE_BYTES = 8L * 1024 * 1024
    private const val DISK_CACHE_BYTES = 32L * 1024 * 1024

    @Volatile
    private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: build(context.applicationContext).also { loader = it }
    }

    /** Drops decoded posters; they come back from the disk cache when drawn again. */
    fun trim() {
        loader?.memoryCache?.clear()
    }

    /**
     * Widget pictures stored on the BanditBox server need this TV's token; it is added only
     * to requests for that server's media, never sent anywhere else.
     */
    private fun client(context: Context): OkHttpClient {
        val sync = (context as? BanditBoxApplication)?.sync
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val auth = sync?.mediaAuth(req.url.toString())
                chain.proceed(if (auth == null) req else req.newBuilder().header("Authorization", auth).build())
            }
            .build()
    }

    private fun build(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(MEMORY_CACHE_BYTES).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("posters").toOkioPath())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client(context) })) }
        .allowRgb565(true)
        .build()
}
