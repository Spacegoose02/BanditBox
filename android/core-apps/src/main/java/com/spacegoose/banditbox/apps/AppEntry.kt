package com.spacegoose.banditbox.apps

/**
 * One launchable app. Plain data with no Android types, so the merge and menu logic
 * can be unit tested on the JVM.
 */
data class AppEntry(
    val packageName: String,
    /** Fully qualified class name of the activity to launch. */
    val activityName: String,
    val label: String,
    /** True when the activity is a TV (LEANBACK_LAUNCHER) entry point. */
    val isLeanback: Boolean,
    /** Preinstalled system app: can't be uninstalled, only disabled from App Info. */
    val isSystem: Boolean,
)

/** Package of the release build. Debug and ramcheck builds add a suffix such as ".debug". */
const val BANDITBOX_PACKAGE = "com.spacegoose.banditbox"

/**
 * True for this build of BanditBox and every other build of it installed next to it
 * (release, .debug, .ramcheck), so a TV with two builds doesn't list the other one as an app.
 */
fun isBanditBox(packageName: String, selfPackage: String): Boolean =
    packageName == selfPackage ||
        packageName == BANDITBOX_PACKAGE ||
        packageName.startsWith("$BANDITBOX_PACKAGE.")

/**
 * Builds the app list from the two launcher queries: one entry per package, preferring
 * the TV activity over the phone one, without any build of BanditBox, sorted by label.
 */
fun mergeEntries(
    leanback: List<AppEntry>,
    launcher: List<AppEntry>,
    selfPackage: String,
): List<AppEntry> {
    val byPackage = LinkedHashMap<String, AppEntry>()
    for (entry in leanback + launcher) {
        if (isBanditBox(entry.packageName, selfPackage)) continue
        byPackage.putIfAbsent(entry.packageName, entry)
    }
    return byPackage.values.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, AppEntry::label).thenBy(AppEntry::packageName),
    )
}
