package com.spacegoose.banditbox.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin mirror of schema/layout.schema.json (v1). The schema is the source of truth:
 * change it first, then this file, and keep LayoutDocumentTest passing against /schema/examples.
 */
@Serializable
data class LayoutDocument(
    val schema: Int = SCHEMA_VERSION,
    val profileId: String,
    val version: Long = 0,
    val profile: Profile,
    val theme: Theme? = null,
    val rows: List<Row> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val hiddenApps: List<String> = emptyList(),
    val topBar: List<String> = emptyList(),
    val remap: List<Remap> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class Profile(
    val name: String,
    /** Hash of the PIN, never the PIN itself. Null means no lock. */
    val pin: String? = null,
    val autoLogin: Boolean = false,
    val kids: Kids? = null,
)

/** Present only on a kids profile. See [KidsRules] for how the limits are applied. */
@Serializable
data class Kids(
    /** Hash of the parent PIN (same format as [Profile.pin]). Null leaves the parent options open. */
    val parentPin: String? = null,
    val approvedApps: List<String> = emptyList(),
    /** Minutes of app time a day. Null means no limit. */
    val dailyLimitMin: Int? = null,
    /** Minutes of warning before the limit or bedtime; 0 turns the warning off. */
    val warnMin: Int = DEFAULT_WARN_MIN,
    val bedtime: Bedtime? = null,
    /** How long the parent PIN lifts a bedtime or time-up lock. */
    val unlockMin: Int = DEFAULT_UNLOCK_MIN,
) {
    companion object {
        const val DEFAULT_WARN_MIN = 5
        const val DEFAULT_UNLOCK_MIN = 30
    }
}

/** Times are "HH:MM", local to the TV. */
@Serializable
data class Bedtime(val start: String, val end: String)

/** How a profile looks. Missing fields take the preset's value; [ThemeResolver] works out the result. */
@Serializable
data class Theme(
    /** A [ThemeCatalog] preset id, or a custom theme's id. */
    val id: String? = null,
    /** "#RRGGBB" or "#AARRGGBB": replaces the preset's accent. */
    val accent: String? = null,
    /** Take the accent from the wallpaper. */
    val adaptiveAccent: Boolean = false,
    val colors: ThemeColors? = null,
    /** "media:sha256-<hex>", "pack:<pack>/<wallpaper>", "none", or null for the preset's wallpaper. */
    val wallpaper: String? = null,
    /** 0 to 0.9, or null for the default (0 for pack wallpapers, 0.45 for pictures). */
    val wallpaperDim: Float? = null,
    /** "system", "banditbox-uniform", "banditbox-mono", "pkg:<package>", or null for system. */
    val iconPack: String? = null,
    val screensaver: Screensaver? = null,
) {
    companion object {
        const val WALLPAPER_NONE = "none"
        const val MEDIA_PREFIX = "media:sha256-"
    }
}

/** Overrides for the preset's colors, each "#RRGGBB" or "#AARRGGBB". */
@Serializable
data class ThemeColors(
    val background: String? = null,
    val surface: String? = null,
    val text: String? = null,
)

/** [type] is "none", "system", "oled-drift", "oled-starfield", "oled-black" or "photos" (with [feed]). */
@Serializable
data class Screensaver(val type: String, val timeoutMin: Int? = null, val feed: String? = null)

@Serializable
sealed interface Row {
    val id: String
    val title: String?

    @Serializable
    @SerialName("apps")
    data class Apps(
        override val id: String,
        override val title: String? = null,
        val tile: Tile = Tile(),
        /** Package names or "folder:<id>". See [ItemRef]. */
        val items: List<String> = emptyList(),
    ) : Row

    @Serializable
    @SerialName("widget")
    data class Widget(
        override val id: String,
        override val title: String? = null,
        /** "<plugin>.<feed>", for example "bingebandit.continue-watching". */
        val feed: String,
    ) : Row

    /** Continue watching: the Android TV Watch Next list other apps publish. */
    @Serializable
    @SerialName("watchnext")
    data class WatchNext(
        override val id: String,
        override val title: String? = null,
        val kinds: List<WatchNextKind> = DEFAULT_KINDS,
        /** Only entries from these apps; null means every app. */
        val apps: List<String>? = null,
        override val limit: Int = ContentRow.DEFAULT_LIMIT,
        override val size: TileSize = TileSize.MEDIUM,
    ) : Row, ContentRow {
        companion object {
            val DEFAULT_KINDS = listOf(WatchNextKind.CONTINUE, WatchNextKind.NEXT)
        }
    }

    /** One app's preview channel. [channel] is its name or internal provider id; null means the app's first channel. */
    @Serializable
    @SerialName("channel")
    data class Channel(
        override val id: String,
        override val title: String? = null,
        @SerialName("package") val packageName: String,
        val channel: String? = null,
        override val limit: Int = ContentRow.DEFAULT_LIMIT,
        override val size: TileSize = TileSize.MEDIUM,
    ) : Row, ContentRow
}

/** A row of cards that other apps publish (Watch Next, preview channels), not a row of app tiles. */
sealed interface ContentRow {
    val id: String
    val title: String?
    val limit: Int
    val size: TileSize

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

@Serializable
enum class WatchNextKind {
    /** Started and not finished. */
    @SerialName("continue") CONTINUE,

    /** The next episode of something being watched. */
    @SerialName("next") NEXT,

    /** A new episode or season of something watched before. */
    @SerialName("new") NEW,

    /** Saved for later. */
    @SerialName("watchlist") WATCHLIST,
}

@Serializable
data class Tile(
    val size: TileSize = TileSize.MEDIUM,
    val style: TileStyle = TileStyle.BANNER,
    val shape: TileShape = TileShape.ROUNDED,
)

@Serializable
enum class TileSize {
    @SerialName("small") SMALL,
    @SerialName("medium") MEDIUM,
    @SerialName("large") LARGE,
}

@Serializable
enum class TileStyle {
    @SerialName("banner") BANNER,
    @SerialName("icon") ICON,
}

@Serializable
enum class TileShape {
    @SerialName("rounded") ROUNDED,
    @SerialName("square") SQUARE,
    @SerialName("circle") CIRCLE,
}

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val items: List<String> = emptyList(),
)

@Serializable
data class Remap(
    /** Key name without the KEYCODE_ prefix, for example "HOME". */
    val key: String,
    val press: Press,
    val action: Action,
)

@Serializable
enum class Press {
    @SerialName("short") SHORT,
    @SerialName("long") LONG,
    @SerialName("double") DOUBLE,
}

@Serializable
sealed interface Action {
    @Serializable @SerialName("openApp")
    data class OpenApp(@SerialName("package") val packageName: String) : Action

    @Serializable @SerialName("openInput")
    data class OpenInput(val input: String) : Action

    @Serializable @SerialName("home")
    data object Home : Action

    @Serializable @SerialName("allApps")
    data object AllApps : Action

    @Serializable @SerialName("settings")
    data object Settings : Action

    @Serializable @SerialName("launcherSettings")
    data object LauncherSettings : Action

    /** Starts the sleep timer, or moves it to the next step: 15, 30, 45, 60, 90, 120 minutes, then off. */
    @Serializable @SerialName("sleepTimer")
    data object SleepTimer : Action

    @Serializable @SerialName("none")
    data object None : Action
}

/** A row item: either an app or a folder. */
sealed interface ItemRef {
    data class App(val packageName: String) : ItemRef
    data class FolderRef(val folderId: String) : ItemRef

    companion object {
        private const val FOLDER_PREFIX = "folder:"

        fun parse(raw: String): ItemRef =
            if (raw.startsWith(FOLDER_PREFIX)) FolderRef(raw.removePrefix(FOLDER_PREFIX)) else App(raw)

        /** The raw row item that points at a folder. */
        fun folder(folderId: String): String = FOLDER_PREFIX + folderId
    }
}
