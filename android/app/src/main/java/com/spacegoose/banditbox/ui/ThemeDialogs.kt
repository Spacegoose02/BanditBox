package com.spacegoose.banditbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.IconPackInfo
import com.spacegoose.banditbox.layout.IconPackRef
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.ScreensaverType
import com.spacegoose.banditbox.layout.Theme
import com.spacegoose.banditbox.layout.ThemeCatalog
import com.spacegoose.banditbox.layout.ThemeResolver
import com.spacegoose.banditbox.layout.WallpaperRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The steps of Settings > Theme. Every choice applies at once, so the screen behind is the preview. */
sealed interface ThemePage {
    data object Main : ThemePage
    data object Categories : ThemePage
    data class Presets(val categoryId: String) : ThemePage
    data object Wallpaper : ThemePage
    data class Pack(val packId: String) : ThemePage
    data object Pictures : ThemePage
    data object Dim : ThemePage
    data object Accent : ThemePage
    data object Icons : ThemePage
    data object Screensaver : ThemePage
    data object ScreensaverTimeout : ThemePage
    data object ScreensaverFeed : ThemePage
}

/** What the theme dialogs need from the launcher: adding pictures needs the activity. */
class ThemeActions(
    val hasImagePicker: Boolean,
    /** Opens the system picker for an image; the launcher applies it when it comes back. */
    val pickImage: () -> Unit,
    /** Downloads the picture at a link and applies it. */
    val addFromLink: (String) -> Unit,
    /** Hashes of the pictures stored on this TV, newest first. Does a quick file listing. */
    val pictures: () -> List<String>,
    /** Installed icon pack apps. Does IO. */
    val iconPacks: () -> List<IconPackInfo>,
)

private val ACCENTS = listOf(
    "Green" to "#39FF14", "Cyan" to "#00E5FF", "Magenta" to "#FF2BD6", "Red" to "#FF2D55", "Amber" to "#FFB300",
    "Violet" to "#9D7BFF", "Blue" to "#8AB4F8", "Teal" to "#64FFDA", "Gold" to "#E5B54A", "White" to "#F5F5F5",
)

private val TIMEOUTS = listOf(2, 5, 10, 15, 30, 60)

@Composable
fun ThemeDialog(page: ThemePage, ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val back = { p: ThemePage -> ctx.show(Overlay.Themes(p)) }
    val theme = ctx.doc.theme
    val resolved = ThemeResolver.resolve(theme)
    val edit = ctx.edit
    when (page) {
        ThemePage.Main -> {
            val preset = ThemeCatalog.preset(resolved.presetId)
            ChoiceDialog(
                title = stringResource(R.string.theme),
                choices = listOf(
                    Choice(stringResource(R.string.theme_preset, preset?.name ?: resolved.presetId)) { back(ThemePage.Categories) },
                    Choice(stringResource(R.string.theme_wallpaper, wallpaperLabel(theme))) { back(ThemePage.Wallpaper) },
                    Choice(stringResource(R.string.theme_accent, accentLabel(theme))) { back(ThemePage.Accent) },
                    Choice(stringResource(R.string.theme_icons, iconsLabel(resolved.iconPack))) { back(ThemePage.Icons) },
                    Choice(stringResource(R.string.theme_screensaver, screensaverLabel(resolved.screensaver.type))) { back(ThemePage.Screensaver) },
                ),
                onDismiss = close,
            )
        }
        ThemePage.Categories -> ChoiceDialog(
            title = stringResource(R.string.theme_pick_category),
            choices = ThemeCatalog.categories.map { c -> Choice(c.name) { back(ThemePage.Presets(c.id)) } },
            onDismiss = { back(ThemePage.Main) },
        )
        is ThemePage.Presets -> ChoiceDialog(
            title = ThemeCatalog.categories.firstOrNull { it.id == page.categoryId }?.name.orEmpty(),
            choices = ThemeCatalog.presetsIn(page.categoryId).map { p ->
                Choice(mark(p.name, p.id == resolved.presetId)) { edit { LayoutEdits.applyPreset(it, p.id) } }
            },
            onDismiss = { back(ThemePage.Categories) },
        )
        ThemePage.Wallpaper -> {
            val linkTitle = stringResource(R.string.wallpaper_link)
            val choices = buildList {
                add(Choice(mark(stringResource(R.string.wallpaper_theme_default), theme?.wallpaper == null)) { edit { LayoutEdits.setWallpaper(it, null) } })
                add(Choice(mark(stringResource(R.string.wallpaper_none), theme?.wallpaper == Theme.WALLPAPER_NONE)) { edit { LayoutEdits.setWallpaper(it, Theme.WALLPAPER_NONE) } })
                ThemeCatalog.wallpaperPacks.forEach { pack -> add(Choice(stringResource(R.string.wallpaper_pack, pack.name)) { back(ThemePage.Pack(pack.id)) }) }
                add(Choice(stringResource(R.string.wallpaper_pictures)) { back(ThemePage.Pictures) })
                if (ctx.theme.hasImagePicker) add(Choice(stringResource(R.string.wallpaper_from_file)) { close(); ctx.theme.pickImage() })
                add(Choice(stringResource(R.string.wallpaper_from_link)) { ctx.show(Overlay.TextInput(linkTitle, "https://") { ctx.theme.addFromLink(it) }) })
                add(Choice(stringResource(R.string.wallpaper_dim, (resolved.wallpaperDim * 100).toInt())) { back(ThemePage.Dim) })
            }
            ChoiceDialog(title = stringResource(R.string.wallpaper), choices = choices, onDismiss = { back(ThemePage.Main) })
        }
        is ThemePage.Pack -> {
            val pack = ThemeCatalog.wallpaperPacks.firstOrNull { it.id == page.packId }
            ChoiceDialog(
                title = pack?.name.orEmpty(),
                choices = pack?.wallpapers.orEmpty().map { w ->
                    Choice(mark(w.name, theme?.wallpaper == w.ref)) { edit { LayoutEdits.setWallpaper(it, w.ref) } }
                },
                onDismiss = { back(ThemePage.Wallpaper) },
            )
        }
        ThemePage.Pictures -> {
            val pictures = remember { ctx.theme.pictures() }
            val choices = pictures.mapIndexed { i, sha ->
                val ref = WallpaperRef.Media(sha).ref
                Choice(mark(stringResource(R.string.wallpaper_picture_n, i + 1, sha.take(8)), theme?.wallpaper == ref)) {
                    edit { LayoutEdits.setWallpaper(it, ref) }
                }
            }.ifEmpty { listOf(Choice(stringResource(R.string.ok)) { back(ThemePage.Wallpaper) }) }
            ChoiceDialog(
                title = stringResource(R.string.wallpaper_pictures),
                choices = choices,
                onDismiss = { back(ThemePage.Wallpaper) },
                message = if (pictures.isEmpty()) stringResource(R.string.wallpaper_no_pictures) else null,
            )
        }
        ThemePage.Dim -> ChoiceDialog(
            title = stringResource(R.string.wallpaper_dim_title),
            choices = buildList {
                add(Choice(mark(stringResource(R.string.wallpaper_dim_auto), theme?.wallpaperDim == null)) { edit { LayoutEdits.setWallpaperDim(it, null) } })
                listOf(0, 20, 40, 60, 80).forEach { pct ->
                    add(Choice(mark("$pct%", theme?.wallpaperDim == pct / 100f)) { edit { LayoutEdits.setWallpaperDim(it, pct / 100f) } })
                }
            },
            onDismiss = { back(ThemePage.Wallpaper) },
        )
        ThemePage.Accent -> ChoiceDialog(
            title = stringResource(R.string.accent),
            choices = buildList {
                val custom = theme?.accent
                val adaptive = theme?.adaptiveAccent == true
                add(Choice(mark(stringResource(R.string.accent_theme), custom == null && !adaptive)) { edit { LayoutEdits.setAccent(it, null) } })
                add(Choice(mark(stringResource(R.string.accent_wallpaper), adaptive)) { edit { LayoutEdits.setAdaptiveAccent(it, true) } })
                ACCENTS.forEach { (name, hex) ->
                    add(Choice(mark(name, !adaptive && custom.equals(hex, ignoreCase = true))) { edit { LayoutEdits.setAccent(it, hex) } })
                }
            },
            onDismiss = { back(ThemePage.Main) },
        )
        ThemePage.Icons -> {
            var installed by remember { mutableStateOf(emptyList<IconPackInfo>()) }
            LaunchedEffect(Unit) { installed = withContext(Dispatchers.IO) { ctx.theme.iconPacks() } }
            val current = resolved.iconPack
            val choices = buildList {
                add(Choice(mark(stringResource(R.string.icons_system), current == IconPackRef.System)) { edit { LayoutEdits.setIconPack(it, IconPackRef.System) } })
                add(Choice(mark(stringResource(R.string.icons_uniform), current == IconPackRef.Uniform)) { edit { LayoutEdits.setIconPack(it, IconPackRef.Uniform) } })
                add(Choice(mark(stringResource(R.string.icons_mono), current == IconPackRef.Mono)) { edit { LayoutEdits.setIconPack(it, IconPackRef.Mono) } })
                installed.forEach { pack ->
                    val ref = IconPackRef.Installed(pack.packageName)
                    add(Choice(mark(pack.label, current == ref)) { edit { LayoutEdits.setIconPack(it, ref) } })
                }
            }
            ChoiceDialog(
                title = stringResource(R.string.icons),
                choices = choices,
                onDismiss = { back(ThemePage.Main) },
                message = stringResource(R.string.icons_hint),
            )
        }
        ThemePage.Screensaver -> ChoiceDialog(
            title = stringResource(R.string.screensaver),
            choices = buildList {
                ScreensaverType.entries.forEach { type ->
                    add(
                        Choice(mark(screensaverLabel(type), resolved.screensaver.type == type)) {
                            if (type == ScreensaverType.PHOTOS) {
                                back(ThemePage.ScreensaverFeed)
                            } else {
                                edit { LayoutEdits.setScreensaver(it, type) }
                            }
                        },
                    )
                }
                if (resolved.screensaver.type == ScreensaverType.PHOTOS) {
                    val name = ctx.photoFeeds.firstOrNull { it.id == resolved.screensaver.feed }?.name ?: resolved.screensaver.feed.orEmpty()
                    add(Choice(stringResource(R.string.screensaver_photos_from, name)) { back(ThemePage.ScreensaverFeed) })
                }
                if (resolved.screensaver.type.drawnByLauncher) {
                    add(Choice(stringResource(R.string.screensaver_after, resolved.screensaver.timeoutMin)) { back(ThemePage.ScreensaverTimeout) })
                }
            },
            onDismiss = { back(ThemePage.Main) },
            message = stringResource(R.string.screensaver_hint),
        )
        ThemePage.ScreensaverTimeout -> ChoiceDialog(
            title = stringResource(R.string.screensaver_after_title),
            choices = TIMEOUTS.map { min ->
                Choice(mark(stringResource(R.string.minutes, min), resolved.screensaver.timeoutMin == min)) {
                    edit { LayoutEdits.setScreensaver(it, resolved.screensaver.type, min) }
                    back(ThemePage.Screensaver)
                }
            },
            onDismiss = { back(ThemePage.Screensaver) },
        )
        ThemePage.ScreensaverFeed -> ChoiceDialog(
            title = stringResource(R.string.screensaver_photos_pick),
            choices = ctx.photoFeeds.map { feed ->
                val current = resolved.screensaver.type == ScreensaverType.PHOTOS && resolved.screensaver.feed == feed.id
                Choice(mark(feed.name, current)) {
                    edit { LayoutEdits.setScreensaver(it, ScreensaverType.PHOTOS, feed = feed.id) }
                    back(ThemePage.Screensaver)
                }
            },
            onDismiss = { back(ThemePage.Screensaver) },
            message = stringResource(if (ctx.photoFeeds.isEmpty()) R.string.screensaver_photos_none else R.string.screensaver_photos_hint),
        )
    }
}

/** A tick in front of the current choice. */
private fun mark(label: String, current: Boolean) = if (current) "✓ $label" else label

@Composable
private fun wallpaperLabel(theme: Theme?): String {
    val raw = theme?.wallpaper ?: return stringResource(R.string.wallpaper_theme_default)
    return when (val ref = ThemeResolver.parseWallpaper(raw)) {
        WallpaperRef.None -> stringResource(R.string.wallpaper_none)
        is WallpaperRef.Pack -> ref.wallpaper.name
        is WallpaperRef.Media -> stringResource(R.string.wallpaper_picture)
        null -> stringResource(R.string.wallpaper_theme_default)
    }
}

@Composable
private fun accentLabel(theme: Theme?): String {
    val custom = theme?.accent
    return when {
        theme?.adaptiveAccent == true -> stringResource(R.string.accent_wallpaper)
        custom != null -> ACCENTS.firstOrNull { it.second.equals(custom, ignoreCase = true) }?.first ?: custom
        else -> stringResource(R.string.accent_theme)
    }
}

@Composable
private fun iconsLabel(pack: IconPackRef): String = when (pack) {
    IconPackRef.System -> stringResource(R.string.icons_system)
    IconPackRef.Uniform -> stringResource(R.string.icons_uniform)
    IconPackRef.Mono -> stringResource(R.string.icons_mono)
    is IconPackRef.Installed -> pack.packageName
}

@Composable
private fun screensaverLabel(type: ScreensaverType): String = stringResource(
    when (type) {
        ScreensaverType.NONE -> R.string.screensaver_none
        ScreensaverType.SYSTEM -> R.string.screensaver_system
        ScreensaverType.OLED_DRIFT -> R.string.screensaver_drift
        ScreensaverType.OLED_STARFIELD -> R.string.screensaver_starfield
        ScreensaverType.OLED_BLACK -> R.string.screensaver_black
        ScreensaverType.PHOTOS -> R.string.screensaver_photos
    },
)
