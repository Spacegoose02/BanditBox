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

/**
 * Builds the app list from the two launcher queries: one entry per package, preferring
 * the TV activity over the phone one, without BanditBox itself, sorted by label.
 */
fun mergeEntries(
    leanback: List<AppEntry>,
    launcher: List<AppEntry>,
    selfPackage: String,
): List<AppEntry> {
    val byPackage = LinkedHashMap<String, AppEntry>()
    for (entry in leanback + launcher) {
        if (entry.packageName == selfPackage) continue
        byPackage.putIfAbsent(entry.packageName, entry)
    }
    return byPackage.values.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, AppEntry::label).thenBy(AppEntry::packageName),
    )
}
