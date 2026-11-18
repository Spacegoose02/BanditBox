package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class KidsRulesTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    @Test
    fun noRulesNeverLocks() {
        val s = KidsRules.evaluate(Kids(), at("2026-11-18T23:00"), zone, usedTodayMs = 10 * 60 * min)
        assertNull(s.lock)
        assertNull(s.minutesLeft)
        assertNull(s.nextCheckAtMs)
        assertFalse(s.warning)
    }

    @Test
    fun dailyLimitCountsDownThenLocks() {
        val kids = Kids(dailyLimitMin = 60)
        val now = at("2026-11-18T15:00")
        val early = KidsRules.evaluate(kids, now, zone, usedTodayMs = 20 * min)
        assertNull(early.lock)
        assertEquals(40, early.minutesLeft)
        assertFalse(early.warning)
        // Next check is the 5-minute warning, 35 minutes from now.
        assertEquals(now + 35 * min, early.nextCheckAtMs)

        val warn = KidsRules.evaluate(kids, now, zone, usedTodayMs = 57 * min)
        assertTrue(warn.warning)
        assertEquals(3, warn.minutesLeft)
        assertEquals(now + 3 * min, warn.nextCheckAtMs)

        val up = KidsRules.evaluate(kids, now, zone, usedTodayMs = 61 * min)
        assertEquals(KidsLock.TIME_UP, up.lock)
        assertEquals(0, up.minutesLeft)
        assertNull(up.lockEndsAtMs)
        // Midnight resets the day.
        assertEquals(at("2026-11-19T00:00"), up.nextCheckAtMs)
    }

    @Test
    fun bedtimeAcrossMidnight() {
        val kids = Kids(bedtime = Bedtime("20:00", "07:00"))
        val evening = KidsRules.evaluate(kids, at("2026-11-18T21:30"), zone, 0)
        assertEquals(KidsLock.BEDTIME, evening.lock)
        assertEquals(at("2026-11-19T07:00"), evening.lockEndsAtMs)
        assertEquals(at("2026-11-19T07:00"), evening.nextCheckAtMs)

        val morning = KidsRules.evaluate(kids, at("2026-11-19T06:59"), zone, 0)
        assertEquals(KidsLock.BEDTIME, morning.lock)

        val day = KidsRules.evaluate(kids, at("2026-11-19T07:00"), zone, 0)
        assertNull(day.lock)
        assertEquals(at("2026-11-19T19:55"), day.nextCheckAtMs)

        val soon = KidsRules.evaluate(kids, at("2026-11-19T19:57"), zone, 0)
        assertNull(soon.lock)
        assertTrue(soon.warning)
        assertEquals(at("2026-11-19T20:00"), soon.nextCheckAtMs)
    }

    @Test
    fun bedtimeWithinOneDay() {
        val kids = Kids(bedtime = Bedtime("13:00", "15:00"))
        assertEquals(KidsLock.BEDTIME, KidsRules.evaluate(kids, at("2026-11-18T14:00"), zone, 0).lock)
        assertNull(KidsRules.evaluate(kids, at("2026-11-18T15:00"), zone, 0).lock)
        assertNull(KidsRules.evaluate(kids, at("2026-11-18T12:00"), zone, 0).lock)
    }

    @Test
    fun parentUnlockLiftsBothLocksUntilItEnds() {
        val kids = Kids(dailyLimitMin = 30, bedtime = Bedtime("20:00", "07:00"))
        val now = at("2026-11-18T21:00")
        val until = now + 30 * min
        val s = KidsRules.evaluate(kids, now, zone, usedTodayMs = 90 * min, unlockedUntilMs = until)
        assertNull(s.lock)
        assertFalse(s.warning)
        assertEquals(until, s.nextCheckAtMs)
        assertEquals(KidsLock.BEDTIME, KidsRules.evaluate(kids, until, zone, 90 * min, until).lock)
    }

    @Test
    fun warningOffWithZero() {
        val kids = Kids(dailyLimitMin = 60, warnMin = 0)
        val s = KidsRules.evaluate(kids, at("2026-11-18T15:00"), zone, usedTodayMs = 58 * min)
        assertFalse(s.warning)
        assertEquals(at("2026-11-18T15:02"), s.nextCheckAtMs)
    }

    @Test
    fun defaultsFindTheOwnersAppsByNameOrPackage() {
        val installed = listOf(
            "com.example.wave" to "Wavelength",
            "com.example.bb" to "Binge Bandit",
            "com.netflix.ninja" to "Netflix",
        )
        val approved = KidsRules.defaultApproved(installed)
        assertTrue("com.example.wave" in approved)
        assertTrue("com.example.bb" in approved)
        assertFalse("com.netflix.ninja" in approved)
        assertTrue(KidsRules.DEFAULT_PACKAGES.all { it in approved })
    }

    @Test
    fun foregroundTimeAddsUpAppsAndClipsToTheRange() {
        val events = listOf(
            UsageEvent("a", 0, true),
            UsageEvent("a", 10 * min, false),
            UsageEvent("launcher", 10 * min, true),
            UsageEvent("launcher", 12 * min, false),
            UsageEvent("b", 12 * min, true),
            // b's next activity resumes before the first one pauses: still one stretch.
            UsageEvent("b", 20 * min, true),
            UsageEvent("b", 20 * min + 1, false),
            UsageEvent("b", 30 * min, false),
            UsageEvent("c", 40 * min, true),
        )
        val ms = ScreenTimeMath.foregroundMs(events, fromMs = 5 * min, toMs = 50 * min, ignore = setOf("launcher"))
        // a: 5..10, b: 12..30, c: 40..50 (still open)
        assertEquals((5 + 18 + 10) * min, ms)
    }

    @Test
    fun screenOffEndsEveryOpenApp() {
        val events = listOf(
            UsageEvent("a", 0, true),
            UsageEvent.screenOff(10 * min),
        )
        assertEquals(10 * min, ScreenTimeMath.foregroundMs(events, 0, 60 * min, emptySet()))
    }
}
