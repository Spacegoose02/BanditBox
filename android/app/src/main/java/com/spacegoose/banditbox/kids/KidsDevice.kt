package com.spacegoose.banditbox.kids

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.spacegoose.banditbox.guard.HomeOverride
import com.spacegoose.banditbox.layout.Kids
import com.spacegoose.banditbox.layout.KidsPolicy
import com.spacegoose.banditbox.layout.KidsRules
import com.spacegoose.banditbox.layout.ScreenTimeMath
import com.spacegoose.banditbox.layout.UsageEvent
import java.time.ZoneId

/**
 * Kids state that belongs to this TV and never syncs: today's screen time per kids profile and
 * any parent-PIN unlock. Screen time is app time (not BanditBox itself) while a kids profile is
 * the open one, read from Android's usage stats, which need the usage access grant.
 */
class KidsDevice(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("kids", Context.MODE_PRIVATE)
    private val ignore = HomeOverride.STOCK_LAUNCHERS + appContext.packageName

    /** True when Android lets BanditBox read usage stats. Without it the daily limit can't be counted. */
    fun hasUsageAccess(): Boolean {
        val ops = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The screen that grants usage access, where the TV has one. */
    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** App time used today on kids profile [profileId]. Does IO: call off the main thread. */
    @Synchronized
    fun usedTodayMs(profileId: String, nowMs: Long = System.currentTimeMillis()): Long {
        rollDay(profileId, nowMs)
        val since = prefs.getLong(KEY_SINCE + profileId, -1)
        val live = if (since >= 0) measure(since, nowMs) else 0
        return prefs.getLong(KEY_USED + profileId, 0) + live
    }

    /** Kids profile [profileId] is now the open one: its app time starts counting. */
    @Synchronized
    fun startSession(profileId: String, nowMs: Long = System.currentTimeMillis()) {
        rollDay(profileId, nowMs)
        if (prefs.getLong(KEY_SINCE + profileId, -1) < 0) prefs.edit().putLong(KEY_SINCE + profileId, nowMs).apply()
    }

    /** Stops counting every kids profile except [keepId], banking the time they used. */
    @Synchronized
    fun endSessionsExcept(keepId: String?, nowMs: Long = System.currentTimeMillis()) {
        val open = prefs.all.keys.filter { it.startsWith(KEY_SINCE) }.map { it.removePrefix(KEY_SINCE) }
        for (id in open) {
            if (id == keepId) continue
            val used = usedTodayMs(id, nowMs)
            prefs.edit().putLong(KEY_USED + id, used).remove(KEY_SINCE + id).apply()
        }
    }

    /** When the parent unlock on [profileId] runs out, or 0. */
    fun unlockedUntil(profileId: String): Long = prefs.getLong(KEY_UNLOCK + profileId, 0)

    /** The parent PIN lifts the lock on [profileId] for [kids].unlockMin minutes. */
    fun unlock(profileId: String, kids: Kids, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_UNLOCK + profileId, nowMs + kids.unlockMin * 60_000L).apply()
    }

    fun policy(profileId: String, kids: Kids, nowMs: Long = System.currentTimeMillis()) = KidsPolicy(
        profileId = profileId,
        kids = kids,
        usedTodayMs = usedTodayMs(profileId, nowMs),
        measuredAtMs = nowMs,
        unlockedUntilMs = unlockedUntil(profileId),
    )

    /** A new day: yesterday's total goes, and a session still open counts from midnight. */
    private fun rollDay(profileId: String, nowMs: Long) {
        val today = KidsRules.startOfDay(nowMs, ZoneId.systemDefault())
        if (prefs.getLong(KEY_DAY + profileId, -1) == today) return
        val edit = prefs.edit().putLong(KEY_DAY + profileId, today).putLong(KEY_USED + profileId, 0)
        val since = prefs.getLong(KEY_SINCE + profileId, -1)
        if (since in 0 until today) edit.putLong(KEY_SINCE + profileId, today)
        edit.apply()
    }

    /** App time between [fromMs] and [toMs], or 0 without usage access. */
    private fun measure(fromMs: Long, toMs: Long): Long {
        if (toMs <= fromMs || !hasUsageAccess()) return 0
        val usm = appContext.getSystemService(UsageStatsManager::class.java) ?: return 0
        // Start a little earlier so an app already open at fromMs is seen opening.
        val events = usm.queryEvents(fromMs - LOOKBACK_MS, toMs) ?: return 0
        val list = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> list += UsageEvent(e.packageName, e.timeStamp, resumed = true)
                UsageEvents.Event.ACTIVITY_PAUSED -> list += UsageEvent(e.packageName, e.timeStamp, resumed = false)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> list += UsageEvent.screenOff(e.timeStamp)
            }
        }
        return ScreenTimeMath.foregroundMs(list, fromMs, toMs, ignore)
    }

    private companion object {
        const val KEY_DAY = "day_"
        const val KEY_USED = "used_"
        const val KEY_SINCE = "since_"
        const val KEY_UNLOCK = "unlock_"
        const val LOOKBACK_MS = 3 * 60 * 60_000L
    }
}
