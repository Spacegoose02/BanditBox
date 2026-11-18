package com.spacegoose.banditbox.layout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeCatalogTest {
    @Serializable
    private data class CatalogFile(
        val version: Int,
        val defaultTheme: String,
        val categories: List<CategoryJson>,
        val presets: List<PresetJson>,
        val wallpaperPacks: List<PackJson>,
    )

    @Serializable private data class CategoryJson(val id: String, val name: String)

    @Serializable private data class ColorsJson(val background: String, val surface: String, val text: String)

    @Serializable
    private data class PresetJson(val id: String, val name: String, val category: String, val colors: ColorsJson, val accent: String, val wallpaper: String)

    @Serializable private data class WallpaperJson(val id: String, val name: String, val style: String, val palette: List<String>)

    @Serializable private data class PackJson(val id: String, val name: String, val wallpapers: List<WallpaperJson>)

    private val file: CatalogFile = run {
        val dir = File(requireNotNull(System.getProperty("banditbox.schemaDir")))
        Json.decodeFromString(CatalogFile.serializer(), File(dir, "themes.json").readText())
    }

    private fun color(raw: String) = requireNotNull(ThemeColorMath.parse(raw)) { raw }

    @Test
    fun matchesSchemaThemesJson() {
        assertEquals(ThemeCatalog.DEFAULT_PRESET_ID, file.defaultTheme)
        assertEquals(file.categories.map { ThemeCategory(it.id, it.name) }, ThemeCatalog.categories)
        assertEquals(
            file.presets.map {
                ThemePreset(it.id, it.name, it.category, color(it.colors.background), color(it.colors.surface), color(it.colors.text), color(it.accent), it.wallpaper)
            },
            ThemeCatalog.presets,
        )
        assertEquals(
            file.wallpaperPacks.map { pack ->
                WallpaperPack(
                    pack.id,
                    pack.name,
                    pack.wallpapers.map { PackWallpaper(pack.id, it.id, it.name, WallpaperStyle.valueOf(it.style.uppercase()), it.palette.map(::color)) },
                )
            },
            ThemeCatalog.wallpaperPacks,
        )
    }

    @Test
    fun everyCategoryHasPresetsAndEveryPresetWallpaperExists() {
        val asked = listOf("nuclear", "laser", "dark", "kids-dark", "neon", "cinematic", "futuristic")
        assertEquals(asked, ThemeCatalog.categories.map { it.id })
        asked.forEach { assertTrue(it, ThemeCatalog.presetsIn(it).isNotEmpty()) }
        ThemeCatalog.presets.forEach { preset ->
            assertTrue(preset.id, preset.category in asked)
            assertNotNull(preset.id, ThemeResolver.parseWallpaper(preset.wallpaper))
        }
        assertEquals(ThemeCatalog.presets.size, ThemeCatalog.presets.map { it.id }.toSet().size)
    }

    @Test
    fun everyPresetAccentIsReadable() {
        ThemeCatalog.presets.forEach { p ->
            assertTrue(p.id, ThemeColorMath.contrast(p.accent, p.background) >= 3.0)
            assertTrue(p.id, ThemeColorMath.contrast(p.text, p.surface) >= 7.0)
        }
    }
}

class ThemeResolverTest {
    @Test
    fun noThemeIsTheDefaultPreset() {
        val t = ThemeResolver.resolve(null)
        val preset = ThemeCatalog.defaultPreset
        assertEquals(preset.id, t.presetId)
        assertEquals(preset.background, t.background)
        assertEquals(preset.accent, t.accent)
        assertEquals(ThemeResolver.parseWallpaper(preset.wallpaper), t.wallpaper)
        assertEquals(IconPackRef.System, t.iconPack)
        assertEquals(ScreensaverType.SYSTEM, t.screensaver.type)
        assertEquals(0f, t.wallpaperDim)
    }

    @Test
    fun unknownPresetFallsBackButKeepsOverrides() {
        val t = ThemeResolver.resolve(Theme(id = "from-the-future", accent = "#FF0000", colors = ThemeColors(background = "#000000")))
        assertEquals(ThemeCatalog.DEFAULT_PRESET_ID, t.presetId)
        assertEquals(0xFF000000.toInt(), t.background)
        assertEquals(0xFFFF0000.toInt(), t.accent)
    }

    @Test
    fun wallpaperRefs() {
        assertEquals(WallpaperRef.None, ThemeResolver.resolve(Theme(id = "neon", wallpaper = "none")).wallpaper)
        val ocean = ThemeResolver.resolve(Theme(id = "neon", wallpaper = "pack:ocean/deep")).wallpaper as WallpaperRef.Pack
        assertEquals("deep", ocean.wallpaper.id)
        val sha = "a".repeat(64)
        val pic = ThemeResolver.resolve(Theme(wallpaper = "media:sha256-$sha"))
        assertEquals(WallpaperRef.Media(sha), pic.wallpaper)
        assertEquals(ThemeResolver.PICTURE_DIM, pic.wallpaperDim)
        // A pack this build doesn't know: the preset's wallpaper.
        val neon = ThemeCatalog.preset("neon")!!
        assertEquals(ThemeResolver.parseWallpaper(neon.wallpaper), ThemeResolver.resolve(Theme(id = "neon", wallpaper = "pack:x/y")).wallpaper)
        assertNull(ThemeResolver.parseWallpaper("media:sha256-short"))
    }

    @Test
    fun adaptiveAccentComesFromThePackPalette() {
        val t = ThemeResolver.resolve(Theme(id = "dark", wallpaper = "pack:ocean/sunset", adaptiveAccent = true))
        val hsv = FloatArray(3)
        ThemeColorMath.toHsv(t.accent, hsv)
        assertTrue("sunset orange, got hue ${hsv[0]}", hsv[0] in 15f..50f)
    }

    @Test
    fun adaptiveAccentUsesThePictureAndFallsBackWithoutOne() {
        val sha = "b".repeat(64)
        val theme = Theme(id = "dark", wallpaper = "media:sha256-$sha", adaptiveAccent = true)
        assertEquals(ThemeColorMath.readableOn(0xFF00C853.toInt(), ThemeCatalog.preset("dark")!!.background), ThemeResolver.resolve(theme, pictureAccent = 0xFF00C853.toInt()).accent)
        assertEquals(ThemeCatalog.preset("dark")!!.accent, ThemeResolver.resolve(theme, pictureAccent = null).accent)
    }

    @Test
    fun darkAccentIsLightenedUntilReadable() {
        val t = ThemeResolver.resolve(Theme(id = "dark-oled", accent = "#202020"))
        assertTrue(ThemeColorMath.contrast(t.accent, t.background) >= 3.0)
    }

    @Test
    fun iconPacksAndScreensavers() {
        assertEquals(IconPackRef.Uniform, IconPackRef.parse("banditbox-uniform"))
        assertEquals(IconPackRef.Installed("com.example.pack"), IconPackRef.parse("pkg:com.example.pack"))
        assertEquals(IconPackRef.System, IconPackRef.parse("pkg:"))
        assertEquals("banditbox-mono", IconPackRef.Mono.id)
        val saver = ThemeResolver.resolve(Theme(screensaver = Screensaver("oled-drift", 500))).screensaver
        assertEquals(ScreensaverSpec(ScreensaverType.OLED_DRIFT, 120), saver)
        assertEquals(ScreensaverType.SYSTEM, ScreensaverType.parse("hologram"))
    }

    @Test
    fun decodesTheThemedExample() {
        val dir = File(requireNotNull(System.getProperty("banditbox.schemaDir")))
        val doc = LayoutJson.decode(File(dir, "examples/themed.json").readText())
        val t = ThemeResolver.resolve(doc.theme)
        assertEquals("neon-tokyo", t.presetId)
        assertEquals(IconPackRef.Installed("com.example.iconpack"), t.iconPack)
        assertEquals(ScreensaverSpec(ScreensaverType.OLED_STARFIELD, 15), t.screensaver)
        assertEquals(0.2f, t.wallpaperDim)
        assertEquals(doc, LayoutJson.decode(LayoutJson.encode(doc)))
    }
}

class ThemeColorMathTest {
    @Test
    fun parsesAndFormats() {
        assertEquals(0xFF39FF14.toInt(), ThemeColorMath.parse("#39FF14"))
        assertEquals(0x8039FF14.toInt(), ThemeColorMath.parse("#8039FF14"))
        assertNull(ThemeColorMath.parse("39FF14"))
        assertNull(ThemeColorMath.parse("#XYZXYZ"))
        assertEquals("#39FF14", ThemeColorMath.format(0xFF39FF14.toInt()))
    }

    @Test
    fun picksTheDominantVividColorAndIgnoresGrey() {
        val blue = 0xFF1565C0.toInt()
        val pixels = IntArray(100) { i -> if (i < 30) blue else 0xFF808080.toInt() }
        val hsv = FloatArray(3)
        ThemeColorMath.toHsv(requireNotNull(ThemeColorMath.pickAccent(pixels)), hsv)
        assertTrue("blue, got ${hsv[0]}", hsv[0] in 200f..225f)
        assertNull(ThemeColorMath.pickAccent(IntArray(100) { 0xFF777777.toInt() }))
        assertNull(ThemeColorMath.pickAccent(IntArray(0)))
    }

    @Test
    fun hsvRoundTrips() {
        val hsv = FloatArray(3)
        for (c in listOf(0xFFFF2BD6.toInt(), 0xFF00E5FF.toInt(), 0xFF39FF14.toInt(), 0xFFFFB300.toInt())) {
            ThemeColorMath.toHsv(c, hsv)
            assertEquals(ThemeColorMath.format(c), ThemeColorMath.format(ThemeColorMath.fromHsv(hsv[0], hsv[1], hsv[2])))
        }
    }
}

class ThemeEditsTest {
    private val base = LayoutDocument(profileId = "p", version = 1, profile = Profile("P"))

    @Test
    fun applyPresetResetsTheLookButKeepsChoices() {
        val custom = base.copy(
            theme = Theme(id = "dark", accent = "#FF0000", wallpaper = "pack:space/nebula", iconPack = "banditbox-mono", adaptiveAccent = true),
        )
        val neon = LayoutEdits.applyPreset(custom, "neon")
        assertEquals(Theme(id = "neon", iconPack = "banditbox-mono", adaptiveAccent = true), neon.theme)
        assertEquals(2, neon.version)
        assertSame(neon, LayoutEdits.applyPreset(neon, "neon"))
    }

    @Test
    fun settersBumpOnlyOnChange() {
        val walled = LayoutEdits.setWallpaper(base, "pack:ocean/deep")
        assertEquals("pack:ocean/deep", walled.theme?.wallpaper)
        assertEquals(2, walled.version)
        assertSame(walled, LayoutEdits.setWallpaper(walled, "pack:ocean/deep"))

        val accent = LayoutEdits.setAccent(LayoutEdits.setAdaptiveAccent(base, true), "#00FF00")
        assertEquals(false, accent.theme?.adaptiveAccent)
        assertEquals("#00FF00", accent.theme?.accent)

        assertEquals(0.9f, LayoutEdits.setWallpaperDim(base, 3f).theme?.wallpaperDim)
        assertNull(LayoutEdits.setIconPack(LayoutEdits.setIconPack(base, IconPackRef.Uniform), IconPackRef.System).theme?.iconPack)
        assertEquals("pkg:a.b", LayoutEdits.setIconPack(base, IconPackRef.Installed("a.b")).theme?.iconPack)

        val saver = LayoutEdits.setScreensaver(base, ScreensaverType.OLED_DRIFT, 5)
        assertEquals(Screensaver("oled-drift", 5), saver.theme?.screensaver)
        assertEquals(Screensaver("oled-black", 5), LayoutEdits.setScreensaver(saver, ScreensaverType.OLED_BLACK).theme?.screensaver)
    }
}
