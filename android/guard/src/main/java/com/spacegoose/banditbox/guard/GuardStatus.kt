package com.spacegoose.banditbox.guard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Answers "does the Home button reach BanditBox, and how?" for the setup screen and the service. */
object GuardStatus {

    /** Opens this app's own Home activity. */
    fun homeIntent(context: Context): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** True when Android already sends Home to BanditBox (Shield, stock Android TV). */
    fun isDefaultHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION") // The ResolveInfoFlags overload is API 33+.
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /** True when the user has turned on the Home button service in Accessibility settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = serviceComponent(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    fun serviceComponent(context: Context) = ComponentName(context, GuardService::class.java)

    /** Class names of the installed stock launchers' HOME activities. */
    fun stockHomeActivities(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION") // The ResolveInfoFlags overload is API 33+.
        val all = context.packageManager.queryIntentActivities(home, 0)
        return all.mapNotNull { it.activityInfo }
            .filter { it.packageName in HomeOverride.STOCK_LAUNCHERS }
            .flatMap { listOfNotNull(it.name, it.targetActivity) }
            .toSet()
    }

    /** The first stock launcher installed, for the setup screen's ADB commands. */
    fun installedStockLauncher(context: Context): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val all = context.packageManager.queryIntentActivities(home, 0)
        return all.mapNotNull { it.activityInfo?.packageName }.firstOrNull { it in HomeOverride.STOCK_LAUNCHERS }
    }
}
