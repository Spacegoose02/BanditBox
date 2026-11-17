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

@Serializable
data class Kids(
    val approvedApps: List<String> = emptyList(),
    val dailyLimitMin: Int? = null,
    val bedtime: Bedtime? = null,
)

/** Times are "HH:MM", local to the TV. */
@Serializable
data class Bedtime(val start: String, val end: String)

@Serializable
data class Theme(
    val id: String? = null,
    /** "#RRGGBB" or "#AARRGGBB". */
    val accent: String? = null,
    /** "media:sha256-<hex>" or null. */
    val wallpaper: String? = null,
    val iconPack: String? = null,
    val screensaver: Screensaver? = null,
)

@Serializable
data class Screensaver(val type: String)

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

    @Serializable
    @SerialName("watchnext")
    data class WatchNext(
        override val id: String,
        override val title: String? = null,
    ) : Row
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
    }
}
