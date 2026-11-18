package com.spacegoose.banditbox.layout

/** A group of presets the TV and web UI list together, such as Neon. */
data class ThemeCategory(val id: String, val name: String)

/** A ready-made look. Colors are ARGB ints. [wallpaper] is a pack ref or "none". */
data class ThemePreset(
    val id: String,
    val name: String,
    val category: String,
    val background: Int,
    val surface: Int,
    val text: Int,
    val accent: Int,
    val wallpaper: String,
)

/** How a built-in wallpaper is drawn. The TV draws it with vector shapes, so it costs no bitmap memory. */
enum class WallpaperStyle { AURORA, BUBBLES, CIRCUIT, FIELD, GRID, HEX, HILLS, LASERS, NEBULA, PLANET, SPOTLIGHT, STARFIELD, WAVES }

/**
 * A built-in wallpaper. [palette] is always four colors: the background's top and bottom,
 * then the primary and secondary colors of the art (which an adaptive accent picks from).
 */
data class PackWallpaper(
    val packId: String,
    val id: String,
    val name: String,
    val style: WallpaperStyle,
    val palette: List<Int>,
) {
    val ref: String get() = "$PACK_PREFIX$packId/$id"
    val top: Int get() = palette[0]
    val bottom: Int get() = palette[1]
    val primary: Int get() = palette[2]
    val secondary: Int get() = palette[3]

    companion object {
        const val PACK_PREFIX = "pack:"
    }
}

data class WallpaperPack(val id: String, val name: String, val wallpapers: List<PackWallpaper>)

/**
 * Every built-in theme and wallpaper. schema/themes.json is the same list for the server and
 * web UI; ThemeCatalogTest keeps the two equal, so change both together.
 */
object ThemeCatalog {
    const val DEFAULT_PRESET_ID = "nuclear"

    val categories: List<ThemeCategory> = listOf(
        ThemeCategory("nuclear", "Nuclear"),
        ThemeCategory("laser", "Laser"),
        ThemeCategory("dark", "Dark"),
        ThemeCategory("kids-dark", "Kids dark"),
        ThemeCategory("neon", "Neon"),
        ThemeCategory("cinematic", "Cinematic"),
        ThemeCategory("futuristic", "Futuristic"),
    )

    val presets: List<ThemePreset> = listOf(
        ThemePreset("nuclear", "Nuclear", "nuclear", background = argb(0xFF0E0F13), surface = argb(0xFF1A1D24), text = argb(0xFFE8F5E9), accent = argb(0xFF39FF14), wallpaper = "pack:future/hex-green"),
        ThemePreset("nuclear-meltdown", "Meltdown", "nuclear", background = argb(0xFF120D05), surface = argb(0xFF231A0C), text = argb(0xFFFFF3E0), accent = argb(0xFFFFB300), wallpaper = "pack:future/hex-amber"),
        ThemePreset("laser", "Laser", "laser", background = argb(0xFF0B0508), surface = argb(0xFF1C0E14), text = argb(0xFFFDECEF), accent = argb(0xFFFF2D55), wallpaper = "pack:future/lasers-red"),
        ThemePreset("laser-violet", "Laser violet", "laser", background = argb(0xFF0A0714), surface = argb(0xFF171129), text = argb(0xFFEFE9FF), accent = argb(0xFF9D7BFF), wallpaper = "pack:future/lasers-violet"),
        ThemePreset("dark", "Dark", "dark", background = argb(0xFF0F1115), surface = argb(0xFF1B1E24), text = argb(0xFFE6E8EC), accent = argb(0xFF8AB4F8), wallpaper = "none"),
        ThemePreset("dark-oled", "OLED black", "dark", background = argb(0xFF000000), surface = argb(0xFF121212), text = argb(0xFFD0D0D0), accent = argb(0xFFBDBDBD), wallpaper = "none"),
        ThemePreset("kids-dark", "Night light", "kids-dark", background = argb(0xFF0D1330), surface = argb(0xFF1A2250), text = argb(0xFFFFF8E1), accent = argb(0xFFFFD54F), wallpaper = "pack:kids/night-sky"),
        ThemePreset("kids-dark-jelly", "Jelly", "kids-dark", background = argb(0xFF1A0F2E), surface = argb(0xFF2A1A47), text = argb(0xFFFFF0F7), accent = argb(0xFFFF80BF), wallpaper = "pack:kids/bubbles"),
        ThemePreset("neon", "Neon", "neon", background = argb(0xFF0A0612), surface = argb(0xFF170E26), text = argb(0xFFF5E9FF), accent = argb(0xFFFF2BD6), wallpaper = "pack:future/synthwave"),
        ThemePreset("neon-tokyo", "Neon Tokyo", "neon", background = argb(0xFF050B12), surface = argb(0xFF0E1A26), text = argb(0xFFE0FBFF), accent = argb(0xFF00E5FF), wallpaper = "pack:future/grid-cyan"),
        ThemePreset("cinematic", "Cinematic", "cinematic", background = argb(0xFF0C0A08), surface = argb(0xFF1C1814), text = argb(0xFFF3EDE4), accent = argb(0xFFE5B54A), wallpaper = "pack:cinema/spotlight"),
        ThemePreset("cinematic-noir", "Noir", "cinematic", background = argb(0xFF080808), surface = argb(0xFF161616), text = argb(0xFFE0E0E0), accent = argb(0xFFCFCFCF), wallpaper = "pack:cinema/noir"),
        ThemePreset("futuristic", "Futuristic", "futuristic", background = argb(0xFF04100F), surface = argb(0xFF0B1F1D), text = argb(0xFFE0FFF9), accent = argb(0xFF64FFDA), wallpaper = "pack:future/circuit"),
        ThemePreset("futuristic-mars", "Mars base", "futuristic", background = argb(0xFF140806), surface = argb(0xFF26120D), text = argb(0xFFFFE9E2), accent = argb(0xFFFF6E40), wallpaper = "pack:space/red-planet"),
    )

    val wallpaperPacks: List<WallpaperPack> = listOf(
        WallpaperPack(
            "space", "Space",
            listOf(
                PackWallpaper("space", "starfield", "Starfield", WallpaperStyle.STARFIELD, palette(0xFF02030A, 0xFF0A0F24, 0xFFFFFFFF, 0xFF8FB8FF)),
                PackWallpaper("space", "nebula", "Nebula", WallpaperStyle.NEBULA, palette(0xFF05030F, 0xFF120826, 0xFFB455FF, 0xFF3FA9F5)),
                PackWallpaper("space", "red-planet", "Red planet", WallpaperStyle.PLANET, palette(0xFF07030A, 0xFF1A0A08, 0xFFE2552E, 0xFFFFB38A)),
            ),
        ),
        WallpaperPack(
            "nature", "Nature",
            listOf(
                PackWallpaper("nature", "hills", "Hills at dusk", WallpaperStyle.HILLS, palette(0xFF1B2240, 0xFF3E2F5B, 0xFF2E7D5B, 0xFFF4A259)),
                PackWallpaper("nature", "aurora", "Aurora", WallpaperStyle.AURORA, palette(0xFF020814, 0xFF06142A, 0xFF3DFFB0, 0xFF7A5CFF)),
            ),
        ),
        WallpaperPack(
            "ocean", "Ocean",
            listOf(
                PackWallpaper("ocean", "deep", "Deep blue", WallpaperStyle.WAVES, palette(0xFF020B18, 0xFF04213D, 0xFF1E88E5, 0xFF4DD0E1)),
                PackWallpaper("ocean", "sunset", "Ocean sunset", WallpaperStyle.WAVES, palette(0xFF2B0F2E, 0xFF0B1E3A, 0xFFFF7A45, 0xFFFFC15E)),
            ),
        ),
        WallpaperPack(
            "future", "Future",
            listOf(
                PackWallpaper("future", "synthwave", "Synthwave", WallpaperStyle.GRID, palette(0xFF0A0418, 0xFF2A0838, 0xFFFF2BD6, 0xFFFFB000)),
                PackWallpaper("future", "grid-cyan", "Cyan grid", WallpaperStyle.GRID, palette(0xFF02070D, 0xFF06182A, 0xFF00E5FF, 0xFF2979FF)),
                PackWallpaper("future", "hex-green", "Green hex", WallpaperStyle.HEX, palette(0xFF050807, 0xFF0C1410, 0xFF39FF14, 0xFF1B5E20)),
                PackWallpaper("future", "hex-amber", "Amber hex", WallpaperStyle.HEX, palette(0xFF0A0703, 0xFF1A1206, 0xFFFFB300, 0xFFFF6F00)),
                PackWallpaper("future", "circuit", "Circuit", WallpaperStyle.CIRCUIT, palette(0xFF020C0B, 0xFF06201D, 0xFF64FFDA, 0xFF00897B)),
                PackWallpaper("future", "lasers-red", "Red lasers", WallpaperStyle.LASERS, palette(0xFF060205, 0xFF140509, 0xFFFF2D55, 0xFFFF8A80)),
                PackWallpaper("future", "lasers-violet", "Violet lasers", WallpaperStyle.LASERS, palette(0xFF05030C, 0xFF110A24, 0xFF9D7BFF, 0xFF40C4FF)),
            ),
        ),
        WallpaperPack(
            "sports", "Sports",
            listOf(
                PackWallpaper("sports", "pitch", "Pitch", WallpaperStyle.FIELD, palette(0xFF06210F, 0xFF0B3A1B, 0xFFE8F5E9, 0xFF43A047)),
                PackWallpaper("sports", "arena", "Arena lights", WallpaperStyle.SPOTLIGHT, palette(0xFF03060F, 0xFF0A1630, 0xFF82B1FF, 0xFFFFFFFF)),
            ),
        ),
        WallpaperPack(
            "kids", "Kids",
            listOf(
                PackWallpaper("kids", "night-sky", "Night sky", WallpaperStyle.STARFIELD, palette(0xFF0D1330, 0xFF1C2A66, 0xFFFFF59D, 0xFFFFD54F)),
                PackWallpaper("kids", "bubbles", "Bubbles", WallpaperStyle.BUBBLES, palette(0xFF1A0F2E, 0xFF2D1B55, 0xFFFF80BF, 0xFF80D8FF)),
            ),
        ),
        WallpaperPack(
            "cinema", "Cinema",
            listOf(
                PackWallpaper("cinema", "spotlight", "Spotlight", WallpaperStyle.SPOTLIGHT, palette(0xFF0C0A08, 0xFF1C140A, 0xFFE5B54A, 0xFFFFF3D6)),
                PackWallpaper("cinema", "noir", "Noir", WallpaperStyle.SPOTLIGHT, palette(0xFF050505, 0xFF151515, 0xFFCFCFCF, 0xFFFFFFFF)),
            ),
        ),
    )

    private val presetsById = presets.associateBy { it.id }
    private val wallpapersByRef = wallpaperPacks.flatMap { it.wallpapers }.associateBy { it.ref }

    val defaultPreset: ThemePreset get() = presetsById.getValue(DEFAULT_PRESET_ID)

    fun preset(id: String?): ThemePreset? = id?.let(presetsById::get)

    fun presetsIn(categoryId: String): List<ThemePreset> = presets.filter { it.category == categoryId }

    /** A pack wallpaper by its ref, pack:<pack>/<wallpaper>. */
    fun wallpaper(ref: String?): PackWallpaper? = ref?.let(wallpapersByRef::get)

    private fun argb(value: Long): Int = value.toInt()

    private fun palette(vararg colors: Long): List<Int> = colors.map { it.toInt() }
}
