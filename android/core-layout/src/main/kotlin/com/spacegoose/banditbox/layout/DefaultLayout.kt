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
}
