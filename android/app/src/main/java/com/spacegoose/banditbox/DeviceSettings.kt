package com.spacegoose.banditbox

import android.content.Context
import android.provider.Settings

/**
 * Settings that belong to this TV, not to a profile, so they never sync: the profile last
 * used, the app to open at boot, and the last boot we handled.
 */
class DeviceSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("device", Context.MODE_PRIVATE)

    var currentProfileId: String?
        get() = prefs.getString(KEY_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_PROFILE, value).apply()

    /** Package to open once the TV has booted, or null. */
    var launchOnBoot: String?
        get() = prefs.getString(KEY_LAUNCH_ON_BOOT, null)
        set(value) = prefs.edit().putString(KEY_LAUNCH_ON_BOOT, value).apply()

    /**
     * True the first time it's called after the TV boots. The launcher is the first thing
     * Android opens, so this replaces a BOOT_COMPLETED receiver (which on Android 10+ may not
     * start activities from the background anyway).
     */
    fun consumeFreshBoot(): Boolean {
        val bootCount = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (bootCount < 0) return false
        val last = prefs.getInt(KEY_BOOT_COUNT, -1)
        if (bootCount == last) return false
        prefs.edit().putInt(KEY_BOOT_COUNT, bootCount).apply()
        // The very first run after install is not a boot; it just records the count.
        return last >= 0
    }

    private companion object {
        const val KEY_PROFILE = "current_profile"
        const val KEY_LAUNCH_ON_BOOT = "launch_on_boot"
        const val KEY_BOOT_COUNT = "boot_count"
    }
}
