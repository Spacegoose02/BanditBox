package com.spacegoose.banditbox.content

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.spacegoose.banditbox.layout.WatchNextKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Reads the Watch Next list and preview channels from the system TvProvider.
 *
 * Without the privileged ACCESS_ALL_EPG_DATA permission (which only system apps get), the
 * provider returns the rows other apps marked searchable, which is the default, and refuses
 * any selection. So every query reads the whole table with a small projection and filters
 * in code. On Google TV some apps publish through Google's Engage SDK instead, which no
 * other app can read; their rows simply don't appear.
 */
class TvProviderReader(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * A fresh snapshot now, then again whenever an app changes what it publishes. Only the
     * tables asked for are read. Collect it only while the launcher is in front.
     */
    fun snapshots(watchNext: Boolean, channels: Boolean): Flow<TvSnapshot> = changes(watchNext, channels)
        .conflate()
        .map { read(watchNext, channels) }
        .flowOn(Dispatchers.IO)

    /** Reads once. Does IO: call off the main thread. */
    fun read(watchNext: Boolean, channels: Boolean): TvSnapshot {
        if (!hasPermission()) return TvSnapshot.NOT_GRANTED
        return TvSnapshot(
            granted = true,
            watchNext = if (watchNext) readWatchNext() else emptyList(),
            previews = if (channels) readPreviews() else emptyList(),
            channels = if (channels) readChannels() else emptyList(),
        )
    }

    private fun changes(watchNext: Boolean, channels: Boolean): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val uris = buildList {
            if (watchNext) add(TvContract.WatchNextPrograms.CONTENT_URI)
            if (channels) {
                add(TvContract.PreviewPrograms.CONTENT_URI)
                add(TvContract.Channels.CONTENT_URI)
            }
        }
        for (uri in uris) {
            try {
                resolver.registerContentObserver(uri, true, observer)
            } catch (e: SecurityException) {
                Log.w(TAG, "Can't watch $uri", e)
            }
        }
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    fun readChannels(): List<TvChannel> = query(TvContract.Channels.CONTENT_URI, CHANNEL_COLUMNS) { c ->
        if (c.string(Col.TYPE) != TvContract.Channels.TYPE_PREVIEW) return@query null
        TvChannel(
            id = c.long(Col.ID) ?: return@query null,
            packageName = c.string(Col.PACKAGE_NAME) ?: return@query null,
            displayName = c.string(Col.DISPLAY_NAME).orEmpty(),
            internalProviderId = c.string(Col.INTERNAL_PROVIDER_ID),
            browsable = c.int(Col.BROWSABLE) != 0,
        )
    }

    private fun readWatchNext(): List<TvProgram> =
        query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS) { c ->
            val kind = when (c.int(Col.WATCH_NEXT_TYPE)) {
                // TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_*
                0 -> WatchNextKind.CONTINUE
                1 -> WatchNextKind.NEXT
                2 -> WatchNextKind.NEW
                3 -> WatchNextKind.WATCHLIST
                else -> return@query null
            }
            program(c)?.copy(
                kind = kind,
                lastEngagement = c.long(Col.LAST_ENGAGEMENT) ?: 0,
            )
        }

    private fun readPreviews(): List<TvProgram> =
        query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS) { c ->
            program(c)?.copy(
                channelId = c.long(Col.CHANNEL_ID),
                weight = c.int(Col.WEIGHT) ?: 0,
                browsable = c.int(Col.BROWSABLE) != 0,
            )
        }

    /** The columns Watch Next and preview programs share. */
    private fun program(c: Cursor): TvProgram? {
        val title = c.string(Col.TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val poster = c.string(Col.POSTER_URI)
        val thumb = c.string(Col.THUMBNAIL_URI)
        val aspectCode = if (poster != null) {
            c.int(Col.POSTER_ASPECT)
        } else {
            c.int(Col.THUMBNAIL_ASPECT)
        }
        val duration = c.long(Col.DURATION) ?: 0
        val position = c.long(Col.POSITION) ?: 0
        return TvProgram(
            id = c.long(Col.ID) ?: return null,
            packageName = c.string(Col.PACKAGE_NAME) ?: return null,
            title = title,
            subtitle = subtitle(c),
            imageUri = poster ?: thumb,
            aspect = aspectOf(aspectCode),
            progress = if (duration > 0 && position > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null,
            intentUri = c.string(Col.INTENT_URI),
        )
    }

    private fun subtitle(c: Cursor): String? {
        val episodeTitle = c.string(Col.EPISODE_TITLE)?.takeIf { it.isNotBlank() }
        val season = c.string(Col.SEASON_NUMBER)?.takeIf { it.isNotBlank() }
        val episode = c.string(Col.EPISODE_NUMBER)?.takeIf { it.isNotBlank() }
        val number = when {
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            else -> null
        }
        return listOfNotNull(number, episodeTitle).joinToString(" · ").ifEmpty { null }
    }

    /** Reads every row; a provider that refuses or is missing gives an empty list, never a crash. */
    private fun <T> query(uri: Uri, columns: Array<String>, row: (Cursor) -> T?): List<T> = try {
        resolver.query(uri, columns, null, null, null)?.use { c ->
            val out = ArrayList<T>(c.count.coerceAtMost(MAX_ROWS))
            while (c.moveToNext() && out.size < MAX_ROWS) row(c)?.let(out::add)
            out
        }.orEmpty()
    } catch (e: SecurityException) {
        Log.w(TAG, "TvProvider refused $uri", e)
        emptyList()
    } catch (e: IllegalArgumentException) {
        // A column this TV's provider doesn't have, or no TvProvider at all.
        Log.w(TAG, "Couldn't read $uri", e)
        emptyList()
    }

    /**
     * TvContract's column names. They are spelled out because most are declared on interfaces
     * the SDK hides, and the strings are part of the provider's stable contract.
     */
    private object Col {
        const val ID = "_id"
        const val PACKAGE_NAME = "package_name"
        const val TYPE = "type"
        const val DISPLAY_NAME = "display_name"
        const val INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val BROWSABLE = "browsable"
        const val TITLE = "title"
        const val EPISODE_TITLE = "episode_title"
        const val SEASON_NUMBER = "season_display_number"
        const val EPISODE_NUMBER = "episode_display_number"
        const val POSTER_URI = "poster_art_uri"
        const val POSTER_ASPECT = "poster_art_aspect_ratio"
        const val THUMBNAIL_URI = "thumbnail_uri"
        const val THUMBNAIL_ASPECT = "thumbnail_aspect_ratio"
        const val DURATION = "duration_millis"
        const val POSITION = "last_playback_position_millis"
        const val INTENT_URI = "intent_uri"
        const val WATCH_NEXT_TYPE = "watch_next_type"
        const val LAST_ENGAGEMENT = "last_engagement_time_utc_millis"
        const val CHANNEL_ID = "channel_id"
        const val WEIGHT = "weight"
    }

    companion object {
        /**
         * Lets an app read other apps' TV listings. A runtime (dangerous) permission, but the
         * SDK marks the constant as a system API, so it is spelled out here.
         */
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"

        private const val TAG = "BanditBox"

        /** A safety cap on one table; a TV with every streaming app installed has a few hundred. */
        private const val MAX_ROWS = 1000

        private val PROGRAM_COLUMNS = arrayOf(
            Col.ID,
            Col.PACKAGE_NAME,
            Col.TITLE,
            Col.EPISODE_TITLE,
            Col.SEASON_NUMBER,
            Col.EPISODE_NUMBER,
            Col.POSTER_URI,
            Col.POSTER_ASPECT,
            Col.THUMBNAIL_URI,
            Col.THUMBNAIL_ASPECT,
            Col.DURATION,
            Col.POSITION,
            Col.INTENT_URI,
        )
        private val WATCH_NEXT_COLUMNS = PROGRAM_COLUMNS + arrayOf(
            Col.WATCH_NEXT_TYPE,
            Col.LAST_ENGAGEMENT,
        )
        private val PREVIEW_COLUMNS = PROGRAM_COLUMNS + arrayOf(
            Col.CHANNEL_ID,
            Col.WEIGHT,
            Col.BROWSABLE,
        )
        private val CHANNEL_COLUMNS = arrayOf(
            Col.ID,
            Col.PACKAGE_NAME,
            Col.TYPE,
            Col.DISPLAY_NAME,
            Col.INTERNAL_PROVIDER_ID,
            Col.BROWSABLE,
        )

        /** TvContract's aspect ratio codes (ASPECT_RATIO_*) as width / height. */
        private fun aspectOf(code: Int?): Float = when (code) {
            1 -> 3f / 2f
            2 -> 4f / 3f
            3 -> 1f
            4 -> 2f / 3f
            5 -> 1f / 1.441f
            else -> TvProgram.ASPECT_16_9
        }

        private fun Cursor.index(column: String): Int = getColumnIndex(column)

        private fun Cursor.string(column: String): String? = index(column).let { if (it < 0 || isNull(it)) null else getString(it) }

        private fun Cursor.long(column: String): Long? = index(column).let { if (it < 0 || isNull(it)) null else getLong(it) }

        private fun Cursor.int(column: String): Int? = index(column).let { if (it < 0 || isNull(it)) null else getInt(it) }
    }
}
