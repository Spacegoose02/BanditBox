package com.spacegoose.banditbox.content

import com.spacegoose.banditbox.layout.ContentRow
import com.spacegoose.banditbox.layout.Row
import com.spacegoose.banditbox.layout.WatchNextKind

/**
 * One program another app published to the TV: a Watch Next entry or an item on a preview
 * channel. Plain data with no Android types, so the selection rules below test on the JVM.
 */
data class TvProgram(
    val id: Long,
    val packageName: String,
    val title: String,
    /** Episode name or "S1 E4", when the app gives one. */
    val subtitle: String? = null,
    /** Poster or thumbnail: https, content or android.resource URI. */
    val imageUri: String? = null,
    /** Width / height of [imageUri]. */
    val aspect: Float = ASPECT_16_9,
    /** How far through it the user is, 0 to 1, when the app says. */
    val progress: Float? = null,
    /** What opens it: an intent: URI or a deep link. */
    val intentUri: String? = null,
    /** Watch Next entries only. */
    val kind: WatchNextKind? = null,
    /** Watch Next entries only: when the user last watched it. */
    val lastEngagement: Long = 0,
    /** Preview programs only: the channel it is on. */
    val channelId: Long? = null,
    /** Preview programs only: the app's own ordering, higher first. */
    val weight: Int = 0,
    /** Preview programs only: false when the app has taken it off the channel. */
    val browsable: Boolean = true,
) {
    companion object {
        const val ASPECT_16_9 = 16f / 9f
    }
}

/** A preview channel another app published. */
data class TvChannel(
    val id: Long,
    val packageName: String,
    val displayName: String,
    /** The app's own id for the channel, stable across reinstalls; may be null. */
    val internalProviderId: String? = null,
    val browsable: Boolean = true,
) {
    /** What a channel row stores to find this channel again. */
    val ref: String get() = internalProviderId?.takeIf { it.isNotBlank() } ?: displayName
}

/** Everything read from the TvProvider at one moment. */
data class TvSnapshot(
    /** False until BanditBox may read TV listings (READ_TV_LISTINGS). */
    val granted: Boolean,
    val watchNext: List<TvProgram> = emptyList(),
    val previews: List<TvProgram> = emptyList(),
    val channels: List<TvChannel> = emptyList(),
) {
    companion object {
        val NOT_GRANTED = TvSnapshot(granted = false)
    }
}

/**
 * Picks the cards a content row shows. [allowed] says which apps the open profile may show
 * (installed, not hidden, approved on a kids profile); see LayoutResolver.contentAllowed.
 */
object ContentSelect {

    fun cards(row: ContentRow, tv: TvSnapshot, allowed: (String) -> Boolean): List<TvProgram> = when (row) {
        is Row.WatchNext -> watchNext(row, tv.watchNext, allowed)
        is Row.Channel -> channel(row, tv.channels, tv.previews, allowed)
    }

    /** Most recently watched first. One card per show and app, so a series shows once. */
    fun watchNext(row: Row.WatchNext, programs: List<TvProgram>, allowed: (String) -> Boolean): List<TvProgram> {
        val kinds = row.kinds.toSet()
        val apps = row.apps?.toSet()
        return programs.asSequence()
            .filter { it.kind in kinds && allowed(it.packageName) && (apps == null || it.packageName in apps) }
            .sortedByDescending { it.lastEngagement }
            .distinctBy { it.packageName to it.title.trim().lowercase() }
            .take(row.limit.coerceIn(1, ContentRow.MAX_LIMIT))
            .toList()
    }

    /** The row's channel, matched by the app's channel id or its name. Null picks the app's first channel. */
    fun findChannel(row: Row.Channel, channels: List<TvChannel>): TvChannel? {
        val own = channels.filter { it.packageName == row.packageName }
        val wanted = row.channel ?: return own.minByOrNull { it.id }
        return own.firstOrNull { it.internalProviderId == wanted }
            ?: own.firstOrNull { it.displayName.equals(wanted, ignoreCase = true) }
    }

    /** The channel's programs in the app's order (weight, then the order it published them). */
    fun channel(
        row: Row.Channel,
        channels: List<TvChannel>,
        programs: List<TvProgram>,
        allowed: (String) -> Boolean,
    ): List<TvProgram> {
        if (!allowed(row.packageName)) return emptyList()
        val channel = findChannel(row, channels) ?: return emptyList()
        return programs.asSequence()
            .filter { it.channelId == channel.id && it.browsable }
            .sortedWith(compareByDescending<TvProgram> { it.weight }.thenBy { it.id })
            .take(row.limit.coerceIn(1, ContentRow.MAX_LIMIT))
            .toList()
    }

    /** Channels the user can add as rows: from apps the profile may show, grouped by app. */
    fun pickable(channels: List<TvChannel>, allowed: (String) -> Boolean): List<TvChannel> =
        channels.filter { allowed(it.packageName) && it.displayName.isNotBlank() }
            .sortedWith(compareBy<TvChannel> { it.packageName }.thenBy { it.id })
}
