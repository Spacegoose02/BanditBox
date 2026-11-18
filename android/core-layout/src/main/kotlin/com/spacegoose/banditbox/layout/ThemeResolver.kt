package com.spacegoose.banditbox.layout

/** What the wallpaper layer draws. */
sealed interface WallpaperRef {
    data object None : WallpaperRef
    data class Pack(val wallpaper: PackWallpaper) : WallpaperRef

    /** A picture stored by content hash; [sha256] is 64 lowercase hex characters. */
    data class Media(val sha256: String) : WallpaperRef {
        val ref: String get() = Theme.MEDIA_PREFIX + sha256
    }
}

/** How app icons are drawn. */
sealed interface IconPackRef {
    /** Each app's own icon. */
    data object System : IconPackRef

    /** Every icon on the same plate in the theme's surface color. */
    data object Uniform : IconPackRef

    /** Like [Uniform], with the icon tinted in the accent color. */
    data object Mono : IconPackRef

    /** An installed icon pack app (ADW/Nova appfilter format). */
    data class Installed(val packageName: String) : IconPackRef

    /** The value written to [Theme.iconPack]. */
    val id: String
        get() = when (this) {
            System -> SYSTEM_ID
            Uniform -> UNIFORM_ID
            Mono -> MONO_ID
            is Installed -> INSTALLED_PREFIX + packageName
        }

    companion object {
        const val SYSTEM_ID = "system"
        const val UNIFORM_ID = "banditbox-uniform"
        const val MONO_ID = "banditbox-mono"
        const val INSTALLED_PREFIX = "pkg:"

        fun parse(raw: String?): IconPackRef = when {
            raw == UNIFORM_ID -> Uniform
            raw == MONO_ID -> Mono
            raw != null && raw.startsWith(INSTALLED_PREFIX) && raw.length > INSTALLED_PREFIX.length ->
                Installed(raw.removePrefix(INSTALLED_PREFIX))
            else -> System
        }
    }
}

enum class ScreensaverType(val id: String) {
    NONE("none"),
    SYSTEM("system"),
    OLED_DRIFT("oled-drift"),
    OLED_STARFIELD("oled-starfield"),
    OLED_BLACK("oled-black"),

    /** Ambient mode: pictures from a widget feed (Immich, Plex), one after another. */
    PHOTOS("photos"),
    ;

    /** True for the black, burn-in safe screensavers. */
    val isOled: Boolean get() = id.startsWith("oled-")

    /** True for the screensavers the launcher draws itself. */
    val drawnByLauncher: Boolean get() = isOled || this == PHOTOS

    companion object {
        fun parse(raw: String?): ScreensaverType = entries.firstOrNull { it.id == raw } ?: SYSTEM
    }
}

data class ScreensaverSpec(
    val type: ScreensaverType,
    val timeoutMin: Int,
    /** [ScreensaverType.PHOTOS]: the widget feed to show. */
    val feed: String? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MIN = 10
    }
}

/** A theme with every value filled in, ready to draw. Colors are ARGB ints. */
data class ResolvedTheme(
    val presetId: String,
    val background: Int,
    val surface: Int,
    val text: Int,
    val accent: Int,
    val wallpaper: WallpaperRef,
    /** 0 to 0.9: how much black to lay over the wallpaper. */
    val wallpaperDim: Float,
    val iconPack: IconPackRef,
    val screensaver: ScreensaverSpec,
)

/** Turns a layout document's [Theme] (which may be partial or missing) into a [ResolvedTheme]. */
object ThemeResolver {
    const val PICTURE_DIM = 0.45f
    const val MAX_DIM = 0.9f

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** The wallpaper [theme] shows: its own, or its preset's when it names none. */
    fun wallpaperOf(theme: Theme?): WallpaperRef {
        val raw = theme?.wallpaper ?: presetOf(theme).wallpaper
        return parseWallpaper(raw) ?: parseWallpaper(presetOf(theme).wallpaper) ?: WallpaperRef.None
    }

    /** Null for a ref this build can't draw, such as a pack wallpaper from a newer catalog. */
    fun parseWallpaper(raw: String?): WallpaperRef? = when {
        raw == null -> null
        raw == Theme.WALLPAPER_NONE -> WallpaperRef.None
        raw.startsWith(Theme.MEDIA_PREFIX) -> raw.removePrefix(Theme.MEDIA_PREFIX).takeIf(SHA256::matches)?.let(WallpaperRef::Media)
        else -> ThemeCatalog.wallpaper(raw)?.let(WallpaperRef::Pack)
    }

    /**
     * [pictureAccent] is the accent the TV picked from an uploaded picture, when the wallpaper
     * is one; pack wallpapers get theirs from their palette here.
     */
    fun resolve(theme: Theme?, pictureAccent: Int? = null): ResolvedTheme {
        val preset = presetOf(theme)
        val overrides = theme?.colors
        val background = ThemeColorMath.parse(overrides?.background) ?: preset.background
        val surface = ThemeColorMath.parse(overrides?.surface) ?: preset.surface
        val text = ThemeColorMath.parse(overrides?.text) ?: preset.text
        val wallpaper = wallpaperOf(theme)

        val chosen = ThemeColorMath.parse(theme?.accent) ?: preset.accent
        val fromWallpaper = if (theme?.adaptiveAccent == true) {
            when (wallpaper) {
                is WallpaperRef.Pack -> ThemeColorMath.pickAccent(listOf(wallpaper.wallpaper.primary, wallpaper.wallpaper.secondary).toIntArray())
                is WallpaperRef.Media -> pictureAccent
                WallpaperRef.None -> null
            }
        } else {
            null
        }
        val accent = ThemeColorMath.readableOn(fromWallpaper ?: chosen, background)

        val dim = theme?.wallpaperDim?.takeUnless { it.isNaN() }?.coerceIn(0f, MAX_DIM)
            ?: if (wallpaper is WallpaperRef.Media) PICTURE_DIM else 0f

        val saver = theme?.screensaver
        return ResolvedTheme(
            presetId = preset.id,
            background = background,
            surface = surface,
            text = text,
            accent = accent,
            wallpaper = wallpaper,
            wallpaperDim = dim,
            iconPack = IconPackRef.parse(theme?.iconPack),
            screensaver = ScreensaverSpec(
                type = ScreensaverType.parse(saver?.type),
                timeoutMin = (saver?.timeoutMin ?: ScreensaverSpec.DEFAULT_TIMEOUT_MIN).coerceIn(1, 120),
                feed = saver?.feed?.takeIf(WidgetFeeds::isFeedId),
            ),
        )
    }

    private fun presetOf(theme: Theme?): ThemePreset = ThemeCatalog.preset(theme?.id) ?: ThemeCatalog.defaultPreset
}

/** Color helpers that need no Android classes, so they run in JVM tests. Colors are ARGB ints. */
object ThemeColorMath {
    private const val MIN_ACCENT_CONTRAST = 3.0

    /** "#RRGGBB" or "#AARRGGBB" to ARGB, or null when it isn't one. */
    fun parse(raw: String?): Int? {
        if (raw == null || !raw.startsWith("#")) return null
        val hex = raw.substring(1)
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    /** ARGB to "#RRGGBB" (alpha dropped). */
    fun format(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    /** WCAG relative luminance, 0 to 1. */
    fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color shr 16 and 0xFF) + 0.7152 * channel(color shr 8 and 0xFF) + 0.0722 * channel(color and 0xFF)
    }

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [color], lightened step by step until it stands out on [background] (all themes are dark). */
    fun readableOn(color: Int, background: Int): Int {
        var c = color or (0xFF shl 24)
        var steps = 0
        while (contrast(c, background) < MIN_ACCENT_CONTRAST && steps < 20) {
            c = mix(c, 0xFFFFFFFF.toInt(), 0.12f)
            steps++
        }
        return c
    }

    /** [a] moved toward [b] by [t] (0 to 1). */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = a shr shift and 0xFF
            val y = b shr shift and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /**
     * The strongest color among [pixels]: the hue with the most saturated, bright area, made
     * vivid enough to work as an accent. Null when the pixels are mostly grey or dark.
     */
    fun pickAccent(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val buckets = 24
        val weight = DoubleArray(buckets)
        val hueSum = DoubleArray(buckets)
        val satSum = DoubleArray(buckets)
        val valSum = DoubleArray(buckets)
        val hsv = FloatArray(3)
        for (p in pixels) {
            toHsv(p, hsv)
            val s = hsv[1]
            val v = hsv[2]
            if (s < 0.25f || v < 0.2f) continue
            val w = (s * v).toDouble()
            val b = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[b] += w
            hueSum[b] += hsv[0] * w
            satSum[b] += s * w
            valSum[b] += v * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        // Too little color to call it the picture's: a grey or black-and-white image.
        if (weight[best] < pixels.size * 0.02) return null
        val w = weight[best]
        val hue = (hueSum[best] / w).toFloat()
        val sat = (satSum[best] / w).toFloat().coerceIn(0.55f, 1f)
        val value = maxOf((valSum[best] / w).toFloat(), 0.85f)
        return fromHsv(hue, sat, value)
    }

    fun toHsv(color: Int, out: FloatArray) {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        out[0] = if (h < 0f) h + 360f else h
        out[1] = if (max == 0f) 0f else d / max
        out[2] = max
    }

    fun fromHsv(hue: Float, sat: Float, value: Float): Int {
        val c = value * sat
        val x = c * (1 - Math.abs((hue / 60f) % 2f - 1))
        val m = value - c
        val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
