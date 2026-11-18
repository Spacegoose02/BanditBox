package com.spacegoose.banditbox.guard

/**
 * Decides which app windows a kids profile is sent home from. Only real apps count (ones with
 * a launcher entry), plus the settings apps, so keyboards, system dialogs and permission
 * prompts that pop up inside an approved app are left alone.
 */
object KidsGate {
    /** Settings apps that lead out of the launcher's control. Blocked even without a launcher entry. */
    val SETTINGS_PACKAGES = setOf(
        "com.android.tv.settings",
        "com.android.settings",
        "com.google.android.tv.settings",
        "com.android.vending", // Play Store
    )

    /**
     * True when a kids profile must not stay in [pkg]. [locked] means bedtime or time-up, when
     * only BanditBox itself (which shows the lock screen) may be in front.
     */
    fun blocks(pkg: String, selfPackage: String, approved: Set<String>, locked: Boolean, isLaunchable: Boolean): Boolean {
        if (pkg == selfPackage) return false
        if (pkg in SETTINGS_PACKAGES) return true
        if (!isLaunchable) return false
        return locked || pkg !in approved
    }
}
