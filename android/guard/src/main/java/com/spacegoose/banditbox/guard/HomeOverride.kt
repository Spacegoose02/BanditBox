package com.spacegoose.banditbox.guard

/**
 * Decides when the stock launcher has just shown its Home screen. Only the stock launchers'
 * actual HOME activities count, so their other screens (for example a settings panel) and any
 * other launcher the user opens on purpose are left alone.
 */
class HomeOverride(
    /** Class names of the stock launchers' HOME activities (and their alias targets). */
    private val stockHomeActivities: Set<String>,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private var lastRedirectAt = Long.MIN_VALUE / 2

    /** True when [className] from [packageName] came to the front and BanditBox should replace it. */
    fun shouldRedirect(packageName: String, className: String, nowMs: Long): Boolean {
        if (packageName !in STOCK_LAUNCHERS) return false
        if (className !in stockHomeActivities) return false
        // A launcher bounce can fire several window events; one redirect is enough.
        if (nowMs - lastRedirectAt < debounceMs) return false
        lastRedirectAt = nowMs
        return true
    }

    companion object {
        const val DEBOUNCE_MS = 800L

        /** Built-in launchers BanditBox replaces. Fire TV (com.amazon.tv.launcher) comes later. */
        val STOCK_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx", // Google TV (onn 4K, Chromecast)
            "com.google.android.tvlauncher", // Android TV (Nvidia Shield)
            "com.google.android.leanbacklauncher", // Older Android TV
        )
    }
}
