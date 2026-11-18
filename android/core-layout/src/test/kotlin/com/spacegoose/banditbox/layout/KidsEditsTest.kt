package com.spacegoose.banditbox.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KidsEditsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun kidsDoc() = DefaultLayout.createKids("p_kids", "Kids", listOf("app.wave", "app.binge"), parentPin = "hash")

    @Test
    fun kidsProfileStartsWithItsAppsAndTheKidsTheme() {
        val doc = kidsDoc()
        assertEquals("kids", doc.theme?.id)
        assertEquals(listOf("app.wave", "app.binge"), (doc.rows.single() as Row.Apps).items)
        assertEquals("hash", doc.profile.kids?.parentPin)
        assertFalse("inputs" in doc.topBar)
        assertTrue(doc.summary().isKids)
    }

    @Test
    fun onlyApprovedAppsAreShown() {
        val doc = LayoutEdits.addToRow(kidsDoc(), DefaultLayout.KIDS_ROW_ID, "app.youtube")
        val installed = setOf("app.wave", "app.binge", "app.youtube", "app.settings")
        val rows = LayoutResolver.appsRows(doc, installed)
        assertEquals(listOf("app.wave", "app.binge"), rows.single().items.map { (it as ResolvedItem.App).packageName })
        assertEquals(listOf("app.binge", "app.wave"), LayoutResolver.allApps(doc, listOf("app.binge", "app.settings", "app.wave")))
    }

    @Test
    fun approvingAddsToTheRowAndUnapprovingHidesIt() {
        val approved = LayoutEdits.approveApp(kidsDoc(), "app.pbs")
        assertTrue("app.pbs" in approved.profile.kids!!.approvedApps)
        assertEquals("app.pbs", (approved.rows.single() as Row.Apps).items.last())
        assertEquals(kidsDoc().version + 1, approved.version)
        assertSame(approved, LayoutEdits.approveApp(approved, "app.pbs"))

        val removed = LayoutEdits.unapproveApp(approved, "app.pbs")
        assertFalse("app.pbs" in removed.profile.kids!!.approvedApps)
        // Still in the row, so approving again puts it back in the same place.
        assertTrue("app.pbs" in (removed.rows.single() as Row.Apps).items)
        val rows = LayoutResolver.appsRows(removed, setOf("app.wave", "app.binge", "app.pbs"))
        assertFalse(rows.single().items.any { (it as ResolvedItem.App).packageName == "app.pbs" })
    }

    @Test
    fun approvingOnANormalProfileDoesNothing() {
        val doc = DefaultLayout.create(listOf("a.b"))
        assertSame(doc, LayoutEdits.approveApp(doc, "x.y"))
        assertNull(doc.summary().kidsApps)
    }

    @Test
    fun limitsAndBedtimeEdits() {
        var doc = kidsDoc()
        doc = LayoutEdits.setDailyLimit(doc, 90)
        doc = LayoutEdits.setBedtime(doc, Bedtime("19:30", "07:00"))
        doc = LayoutEdits.setUnlockMinutes(doc, 1)
        doc = LayoutEdits.setWarnMinutes(doc, 10)
        val kids = doc.profile.kids!!
        assertEquals(90, kids.dailyLimitMin)
        assertEquals(Bedtime("19:30", "07:00"), kids.bedtime)
        assertEquals(5, kids.unlockMin)
        assertEquals(10, kids.warnMin)
        assertNull(LayoutEdits.setDailyLimit(doc, null).profile.kids!!.dailyLimitMin)
        assertNull(LayoutEdits.endKids(doc).profile.kids)
    }

    @Test
    fun makeKidsKeepsRowsAndSetsTheme() {
        val doc = LayoutEdits.makeKids(DefaultLayout.create(listOf("a.b")), Kids(approvedApps = listOf("a.b")))
        assertEquals("kids", doc.theme?.id)
        assertEquals(listOf("a.b"), doc.profile.kids?.approvedApps)
    }

    @Test
    fun kidsButtonRulesCantLeaveTheLauncher() {
        var doc = kidsDoc()
        doc = LayoutEdits.setRemap(doc, "PROG_RED", Press.SHORT, Action.OpenApp("app.wave"))
        doc = LayoutEdits.setRemap(doc, "PROG_GREEN", Press.SHORT, Action.OpenApp("app.youtube"))
        doc = LayoutEdits.setRemap(doc, "PROG_BLUE", Press.SHORT, Action.Settings)
        doc = LayoutEdits.setRemap(doc, "PROG_YELLOW", Press.SHORT, Action.OpenInput("HDMI1"))
        doc = LayoutEdits.setRemap(doc, "HOME", Press.LONG, Action.AllApps)
        assertEquals(listOf("PROG_RED", "HOME"), KidsRules.allowedRemaps(doc).map { it.key })
        assertEquals(5, KidsRules.allowedRemaps(LayoutEdits.endKids(doc)).size)
    }

    @Test
    fun kidsDocumentRoundTripsThroughJson() {
        val doc = LayoutEdits.setBedtime(kidsDoc(), Bedtime("20:00", "07:00"))
        assertEquals(doc, LayoutJson.decode(LayoutJson.encode(doc)))
    }

    @Test
    fun approvalFromAnotherProfileIsSaved() {
        val store = LayoutStore(tmp.root)
        store.save(DefaultLayout.create(listOf("a.b")))
        val kids = Profiles.createKids(store, "Kids", listOf("app.wave"), "hash")
        assertEquals("p_2", kids.profileId)
        Profiles.setKidsApproval(store, kids.profileId, "app.pbs", approved = true)
        assertTrue("app.pbs" in store.load(kids.profileId)!!.profile.kids!!.approvedApps)
        Profiles.setKidsApproval(store, kids.profileId, "app.pbs", approved = false)
        assertFalse("app.pbs" in store.load(kids.profileId)!!.profile.kids!!.approvedApps)
        assertNull(Profiles.setKidsApproval(store, DefaultLayout.PROFILE_ID, "app.pbs", approved = true))
    }
}
