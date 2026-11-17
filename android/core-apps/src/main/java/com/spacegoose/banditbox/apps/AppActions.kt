package com.spacegoose.banditbox.apps

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Intents behind the long-press menu. All are started from the launcher activity. */
object AppActions {
    fun open(entry: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(if (entry.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(entry.packageName, entry.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun appInfo(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app's own settings screen if it declares one, otherwise App Info. */
    fun appSettings(pm: PackageManager, packageName: String): Intent {
        val prefs = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION") // The ResolveInfoFlags overload is API 33+.
        val resolved = pm.resolveActivity(prefs, 0)
        return if (resolved != null) prefs else appInfo(packageName)
    }

    fun uninstall(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun intentFor(action: AppMenuAction, entry: AppEntry, pm: PackageManager): Intent = when (action) {
        AppMenuAction.OPEN -> open(entry)
        AppMenuAction.APP_SETTINGS -> appSettings(pm, entry.packageName)
        AppMenuAction.APP_INFO, AppMenuAction.FORCE_CLOSE -> appInfo(entry.packageName)
        AppMenuAction.UNINSTALL -> uninstall(entry.packageName)
    }
}
