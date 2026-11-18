package com.spacegoose.banditbox.system

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spacegoose.banditbox.layout.LayoutJson
import com.spacegoose.banditbox.layout.WidgetCard
import com.spacegoose.banditbox.layout.WidgetFeed
import com.spacegoose.banditbox.layout.WidgetFeeds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Widget feeds that apps on this TV publish with banditbox-plugin-sdk (android/plugin-sdk):
 * an exported provider with the WIDGET_FEEDS intent filter and the authority
 * "<package>.banditbox". Read only while the launcher is in front.
 */
class PluginFeeds(context: Context) {
    private val app = context.applicationContext
    private val _feeds = MutableStateFlow<Map<String, WidgetFeed>>(emptyMap())

    /** Feeds by id, from every app that publishes some. */
    val feeds: StateFlow<Map<String, WidgetFeed>> = _feeds.asStateFlow()

    private data class Source(val packageName: String, val authority: String)

    /** Apps on the TV that publish feeds. */
    private fun sources(): List<Source> {
        val found = app.packageManager.queryIntentContentProviders(Intent(ACTION), 0)
        return found.mapNotNull { info ->
            val p = info.providerInfo ?: return@mapNotNull null
            val expected = p.packageName + AUTHORITY_SUFFIX
            // The authority must be the app's own, so one app can't speak for another.
            if (p.exported && p.authority?.split(';')?.contains(expected) == true) Source(p.packageName, expected) else null
        }.distinctBy { it.packageName }
    }

    /** Reads one app's feeds; empty if it fails or breaks the rules. */
    private fun read(source: Source): List<WidgetFeed> {
        val client = app.contentResolver.acquireUnstableContentProviderClient(source.authority) ?: return emptyList()
        return try {
            val reply = client.call(METHOD, null, null) ?: return emptyList()
            val text = reply.getString(KEY_FEEDS) ?: return emptyList()
            if (text.length > MAX_REPLY_CHARS) {
                Log.w("BanditBox", "${source.packageName}: widget feeds too big, ignored")
                return emptyList()
            }
            val array = (LayoutJson.json.parseToJsonElement(text) as? JsonObject)?.get("feeds") as? JsonArray ?: return emptyList()
            WidgetFeeds.decodeList(array).take(MAX_FEEDS).mapNotNull { clean(it, source) }
        } catch (e: Exception) {
            // A crashing or misbehaving plugin never takes the home screen with it.
            Log.w("BanditBox", "${source.packageName}: couldn't read widget feeds: ${e.message}")
            emptyList()
        } finally {
            client.close()
        }
    }

    /** Keeps a feed to its own app: its package, and only pictures it may hand out. */
    private fun clean(feed: WidgetFeed, source: Source): WidgetFeed? {
        if (!FEED_ID.matches(feed.id)) return null
        fun image(ref: String?): String? = ref?.takeIf {
            it.startsWith("https://") ||
                it.startsWith("content://${source.authority}/") ||
                it.startsWith("android.resource://${source.packageName}/")
        }
        return feed.copy(
            packageName = source.packageName,
            cards = feed.cards.take(MAX_CARDS).map { c: WidgetCard ->
                c.copy(
                    packageName = source.packageName,
                    image = image(c.image),
                    score = c.score?.let { s -> s.copy(home = s.home.copy(logo = image(s.home.logo)), away = s.away.copy(logo = image(s.away.logo))) },
                )
            },
        )
    }

    /** Reads every app's feeds now. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val all = sources().flatMap(::read)
        _feeds.value = all.associateBy { it.id }
    }

    /**
     * Reads the feeds, then again whenever an app says they changed, until cancelled. Run it
     * while the launcher is in front.
     */
    suspend fun watch(): Nothing = coroutineScope {
        refresh()
        val changed = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                changed.trySend(Unit)
            }
        }
        val resolver = app.contentResolver
        val sources = withContext(Dispatchers.IO) { sources() }
        for (s in sources) {
            runCatching { resolver.registerContentObserver(Uri.parse("content://${s.authority}/"), true, observer) }
        }
        try {
            launch {
                for (unused in changed) {
                    kotlinx.coroutines.delay(DEBOUNCE_MS)
                    refresh()
                }
            }
            awaitCancellation()
        } finally {
            resolver.unregisterContentObserver(observer)
        }
    }

    companion object {
        const val ACTION = "com.spacegoose.banditbox.action.WIDGET_FEEDS"
        private const val METHOD = "getFeeds"
        private const val KEY_FEEDS = "feeds"
        private const val AUTHORITY_SUFFIX = ".banditbox"
        private const val MAX_REPLY_CHARS = 200_000
        private const val MAX_FEEDS = 20
        private const val MAX_CARDS = 50
        private const val DEBOUNCE_MS = 500L
        private val FEED_ID = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")
    }
}
