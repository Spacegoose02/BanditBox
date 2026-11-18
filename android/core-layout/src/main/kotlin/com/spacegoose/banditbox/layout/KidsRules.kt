package com.spacegoose.banditbox.layout

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Why a kids profile is locked right now. */
enum class KidsLock { BEDTIME, TIME_UP }

/**
 * Where a kids profile stands at one moment.
 *
 * @property lock why the profile is locked, or null when apps can be used.
 * @property minutesLeft whole minutes of app time left today, or null with no daily limit.
 * @property warning true inside the warning window before the limit or bedtime.
 * @property lockEndsAtMs when a bedtime lock lifts by itself (null for time-up, which lasts until midnight).
 * @property nextCheckAtMs the next moment the answer can change, assuming apps stay in use. The
 *   service sets a timer for it so a kid already in an app is still stopped on time.
 */
data class KidsStatus(
    val lock: KidsLock?,
    val minutesLeft: Int?,
    val warning: Boolean,
    val lockEndsAtMs: Long?,
    val nextCheckAtMs: Long?,
)

/**
 * Everything the service and the launcher need to apply a kids profile's rules. [usedTodayMs]
 * is app time already used today, measured at [measuredAtMs]; [unlockedUntilMs] is when a
 * parent-PIN unlock runs out (0 when there is none).
 */
data class KidsPolicy(
    val profileId: String,
    val kids: Kids,
    val usedTodayMs: Long,
    val measuredAtMs: Long,
    val unlockedUntilMs: Long,
)

/** The rules of a kids profile, kept free of Android so they can be tested on the JVM. */
object KidsRules {
    private const val MINUTE_MS = 60_000L

    /** Placeholder package names for the owner's apps; matching by label covers the real ones. */
    val DEFAULT_PACKAGES = listOf("com.spacegoose.wavelength", "com.spacegoose.bingebandit")
    private val DEFAULT_LABELS = setOf("wavelength", "bingebandit")

    /** Apps a new kids profile starts with: Wavelength and BingeBandit, found by package or by name. */
    fun defaultApproved(installed: List<Pair<String, String>>): List<String> {
        val found = installed.filter { (pkg, label) ->
            pkg in DEFAULT_PACKAGES || label.replace(" ", "").lowercase() in DEFAULT_LABELS
        }.map { it.first }
        return (found + DEFAULT_PACKAGES).distinct()
    }

    /** "HH:MM" to a time, or null when it isn't one. */
    fun parseTime(text: String): LocalTime? {
        val m = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$").matchEntire(text) ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /**
     * The bedtime window that contains [now], or else the next one to start, as epoch millis.
     * An end earlier than the start runs past midnight; equal times mean no bedtime.
     */
    fun bedtimeWindow(bedtime: Bedtime, now: ZonedDateTime): LongRange? {
        val start = parseTime(bedtime.start) ?: return null
        val end = parseTime(bedtime.end) ?: return null
        if (start == end) return null
        val zone = now.zone
        val nowMs = now.toInstant().toEpochMilli()
        fun window(startDay: LocalDate): LongRange {
            val s = ZonedDateTime.of(startDay, start, zone)
            val e = ZonedDateTime.of(if (end > start) startDay else startDay.plusDays(1), end, zone)
            return s.toInstant().toEpochMilli() until e.toInstant().toEpochMilli()
        }
        val today = now.toLocalDate()
        // Yesterday's window can still be running this morning.
        return listOf(window(today.minusDays(1)), window(today), window(today.plusDays(1)))
            .firstOrNull { nowMs < it.last + 1 }
    }

    /** Midnight at the start of the day that holds [nowMs]. */
    fun startOfDay(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Applies [kids] at [nowMs]. [usedTodayMs] is app time used today; the caller has already
     * reset it at midnight. A parent unlock until [unlockedUntilMs] lifts both kinds of lock.
     */
    fun evaluate(kids: Kids, nowMs: Long, zone: ZoneId, usedTodayMs: Long, unlockedUntilMs: Long = 0): KidsStatus {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val warnMs = kids.warnMin.coerceAtLeast(0) * MINUTE_MS
        val checks = mutableListOf<Long>()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val limitMs = kids.dailyLimitMin?.let { it * MINUTE_MS }
        val leftMs = limitMs?.let { (it - usedTodayMs).coerceAtLeast(0) }
        val minutesLeft = leftMs?.let { ((it + MINUTE_MS - 1) / MINUTE_MS).toInt() }

        val window = kids.bedtime?.let { bedtimeWindow(it, now) }
        val inBedtime = window != null && nowMs in window
        val bedtimeEnd = window?.let { it.last + 1 }

        val unlocked = nowMs < unlockedUntilMs
        if (unlocked) checks += unlockedUntilMs

        val lock = when {
            unlocked -> null
            inBedtime -> KidsLock.BEDTIME
            leftMs == 0L -> KidsLock.TIME_UP
            else -> null
        }

        var warning = false
        if (lock == null) {
            if (leftMs != null && leftMs > 0) {
                // While an app stays open, time runs out this far from now.
                val out = nowMs + leftMs
                if (!unlocked || out > unlockedUntilMs) {
                    checks += out
                    if (warnMs > 0) {
                        if (leftMs <= warnMs) warning = !unlocked else checks += out - warnMs
                    }
                }
            }
            if (window != null && !inBedtime) {
                val start = window.first
                checks += start
                if (warnMs > 0) {
                    if (start - nowMs <= warnMs) warning = warning || !unlocked else checks += start - warnMs
                }
            }
            if (inBedtime && bedtimeEnd != null) checks += bedtimeEnd
        } else if (lock == KidsLock.BEDTIME && bedtimeEnd != null) {
            checks += bedtimeEnd
        }
        // A new day resets the time used.
        if (limitMs != null) checks += midnight

        return KidsStatus(
            lock = lock,
            minutesLeft = minutesLeft,
            warning = warning,
            lockEndsAtMs = if (lock == KidsLock.BEDTIME) bedtimeEnd else null,
            nextCheckAtMs = checks.filter { it > nowMs }.minOrNull(),
        )
    }

    /**
     * The button rules a profile may run. A kids profile drops the ones that lead out of the
     * launcher: Android settings, BanditBox settings, inputs, and apps it isn't approved for.
     */
    fun allowedRemaps(doc: LayoutDocument): List<Remap> {
        val kids = doc.profile.kids ?: return doc.remap
        return doc.remap.filter { rule ->
            when (val action = rule.action) {
                is Action.OpenApp -> action.packageName in kids.approvedApps
                is Action.OpenInput, Action.Settings, Action.LauncherSettings -> false
                Action.Home, Action.AllApps, Action.None -> true
            }
        }
    }
}

/** One app coming to the front or leaving it, from Android's usage events. */
data class UsageEvent(val packageName: String, val timeMs: Long, val resumed: Boolean) {
    companion object {
        /** The screen went off: nothing is in front any more, whatever pause events went missing. */
        fun screenOff(timeMs: Long) = UsageEvent(SCREEN_OFF, timeMs, resumed = false)

        const val SCREEN_OFF = ""
    }
}

/** Adds up how long apps were on screen, from usage events. */
object ScreenTimeMath {
    /**
     * Milliseconds apps other than those in [ignore] were in front between [fromMs] and [toMs].
     * [events] are in time order and may start before [fromMs]; an app still open at [toMs]
     * counts up to [toMs]. Overlapping apps (one activity resuming before the last pauses) are
     * counted once.
     */
    fun foregroundMs(events: List<UsageEvent>, fromMs: Long, toMs: Long, ignore: Set<String>): Long {
        if (toMs <= fromMs) return 0
        val open = HashMap<String, Int>()
        var total = 0L
        var since: Long? = null
        fun anyOpen() = open.values.any { it > 0 }
        for (e in events) {
            if (e.timeMs > toMs) break
            if (e.packageName in ignore) continue
            val wasOpen = anyOpen()
            if (e.packageName == UsageEvent.SCREEN_OFF) {
                open.clear()
            } else {
                val count = open[e.packageName] ?: 0
                open[e.packageName] = if (e.resumed) count + 1 else (count - 1).coerceAtLeast(0)
            }
            val isOpen = anyOpen()
            if (!wasOpen && isOpen) since = e.timeMs
            if (wasOpen && !isOpen) {
                since?.let { total += overlap(it, e.timeMs, fromMs, toMs) }
                since = null
            }
        }
        since?.let { if (anyOpen()) total += overlap(it, toMs, fromMs, toMs) }
        return total
    }

    private fun overlap(a: Long, b: Long, from: Long, to: Long): Long =
        (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0)
}
