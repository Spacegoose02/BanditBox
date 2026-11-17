package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun p(id: String, auto: Boolean = false) = ProfileSummary(id, id, pinHash = null, autoLogin = auto)

    @Test
    fun bootOpensAutoLoginProfile() {
        assertEquals(Startup.Open("b"), Profiles.startup(listOf(p("a"), p("b", auto = true)), savedId = "a", freshBoot = true))
    }

    @Test
    fun bootWithSeveralProfilesAndNoAutoLoginShowsPicker() {
        assertEquals(Startup.Pick, Profiles.startup(listOf(p("a"), p("b")), savedId = "a", freshBoot = true))
    }

    @Test
    fun bootWithOneProfileOpensIt() {
        assertEquals(Startup.Open("a"), Profiles.startup(listOf(p("a")), savedId = null, freshBoot = true))
    }

    @Test
    fun restartKeepsTheLastProfile() {
        assertEquals(Startup.Open("b"), Profiles.startup(listOf(p("a"), p("b")), savedId = "b", freshBoot = false))
        assertEquals(Startup.Open("a"), Profiles.startup(listOf(p("a"), p("b")), savedId = "gone", freshBoot = false))
    }

    @Test
    fun noProfilesYetOpensTheDefault() {
        assertEquals(Startup.Open(DefaultLayout.PROFILE_ID), Profiles.startup(emptyList(), null, freshBoot = true))
    }

    @Test
    fun createPicksAFreeIdAndOnlyOneProfileKeepsAutoLogin() {
        val store = LayoutStore(tmp.root)
        store.save(LayoutEdits.setAutoLogin(DefaultLayout.create(emptyList()), true))
        val kids = Profiles.create(store, "Kids", listOf("a.bingebandit"))
        assertEquals("p_2", kids.profileId)
        assertEquals("p_3", Profiles.create(store, "Guest", emptyList()).profileId)

        store.save(LayoutEdits.setAutoLogin(kids, true))
        Profiles.clearAutoLoginExcept(store, kids.profileId)
        assertEquals(listOf("p_2"), Profiles.summaries(store).filter { it.autoLogin }.map { it.id })

        store.delete("p_3")
        assertEquals(listOf("p_2", DefaultLayout.PROFILE_ID), store.profileIds())
    }

    @Test
    fun pinHashVerifiesOnlyTheRightPin() {
        val stored = PinHash.hash("1234")
        assertTrue(PinHash.verify("1234", stored))
        assertFalse(PinHash.verify("4321", stored))
        assertFalse(PinHash.verify("1234", null))
        assertFalse(PinHash.verify("1234", "argon2id-placeholder"))
        assertNotEquals(stored, PinHash.hash("1234")) // salted
        assertFalse(PinHash.isValidPin("12a4"))
    }

    @Test
    fun profileEdits() {
        val doc = DefaultLayout.create(emptyList())
        val renamed = LayoutEdits.renameProfile(doc, " Living room ")
        assertEquals("Living room", renamed.profile.name)
        val locked = LayoutEdits.setPin(renamed, PinHash.hash("0000"))
        assertTrue(locked.summary().hasPin)
        assertFalse(LayoutEdits.setPin(locked, null).summary().hasPin)
    }
}
