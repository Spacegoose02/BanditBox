package com.spacegoose.banditbox.apps

/** Entries in the long-press menu on an app tile. */
enum class AppMenuAction {
    OPEN,

    /** The app's own settings screen, or App Info when it has none. */
    APP_SETTINGS,
    APP_INFO,

    /** Android has no API for a launcher to stop another app, so this opens App Info on its Force Stop button. */
    FORCE_CLOSE,
    UNINSTALL,
}

/** Which menu entries an app gets. System apps can't be uninstalled. */
fun menuFor(entry: AppEntry): List<AppMenuAction> = buildList {
    add(AppMenuAction.OPEN)
    add(AppMenuAction.APP_SETTINGS)
    add(AppMenuAction.APP_INFO)
    add(AppMenuAction.FORCE_CLOSE)
    if (!entry.isSystem) add(AppMenuAction.UNINSTALL)
}
