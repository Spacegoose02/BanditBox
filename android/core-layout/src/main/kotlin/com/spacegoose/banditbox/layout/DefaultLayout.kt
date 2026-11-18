package com.spacegoose.banditbox.layout

/** The layout a fresh install starts with. */
object DefaultLayout {
    const val PROFILE_ID = "p_default"
    const val FAVORITES_ROW_ID = "favorites"
    const val CONTINUE_WATCHING_ROW_ID = "continue"
    private const val FAVORITES_LIMIT = 8

    val TOP_BAR = listOf("clock", "profile", "inputs", "network", "settings")

    /**
     * A Favorites row seeded with the first few TV apps, a Continue watching row under it
     * (empty until other apps publish to Watch Next and BanditBox may read it), and the
     * standard top bar.
     */
    fun create(tvApps: List<String>, profileId: String = PROFILE_ID, name: String = "Home"): LayoutDocument =
        LayoutDocument(
            profileId = profileId,
            profile = Profile(name = name),
            rows = listOf(
                Row.Apps(
                    id = FAVORITES_ROW_ID,
                    title = "Favorites",
                    tile = Tile(size = TileSize.LARGE),
                    items = tvApps.distinct().take(FAVORITES_LIMIT),
                ),
                Row.WatchNext(id = CONTINUE_WATCHING_ROW_ID, title = "Continue watching"),
            ),
            topBar = TOP_BAR,
        )

    const val KIDS_ROW_ID = "kids"

    /** Kids top bar: no inputs or network, which lead out of the launcher. */
    val KIDS_TOP_BAR = listOf("clock", "profile", "settings")

    /** A kids profile: one big row of its approved apps, the kids theme, and the parent PIN. */
    fun createKids(profileId: String, name: String, approvedApps: List<String>, parentPin: String?): LayoutDocument =
        LayoutDocument(
            profileId = profileId,
            profile = Profile(name = name, kids = Kids(parentPin = parentPin, approvedApps = approvedApps.distinct())),
            theme = Theme(id = LayoutEdits.KIDS_THEME_ID),
            rows = listOf(
                Row.Apps(
                    id = KIDS_ROW_ID,
                    title = null,
                    tile = Tile(size = TileSize.LARGE, style = TileStyle.BANNER, shape = TileShape.ROUNDED),
                    items = approvedApps.distinct(),
                ),
            ),
            topBar = KIDS_TOP_BAR,
        )
}
