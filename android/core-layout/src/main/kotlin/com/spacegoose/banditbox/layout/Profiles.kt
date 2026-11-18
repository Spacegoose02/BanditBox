package com.spacegoose.banditbox.layout

/** What the picker and settings need to know about a profile without loading its rows. */
data class ProfileSummary(
    val id: String,
    val name: String,
    val pinHash: String?,
    val autoLogin: Boolean,
    /** A kids profile's approved apps; null for a normal profile. */
    val kidsApps: Set<String>? = null,
) {
    val hasPin: Boolean get() = pinHash != null
    val isKids: Boolean get() = kidsApps != null
}

fun LayoutDocument.summary() = ProfileSummary(
    id = profileId,
    name = profile.name,
    pinHash = profile.pin,
    autoLogin = profile.autoLogin,
    kidsApps = profile.kids?.approvedApps?.toSet(),
)

/** Which profile to open when the launcher starts. */
sealed interface Startup {
    /** Open this profile without asking (no PIN): the saved one, or the auto sign-in one at boot. */
    data class Open(val profileId: String) : Startup

    /** Show the profile picker. */
    data object Pick : Startup
}

object Profiles {

    /**
     * At boot: the auto sign-in profile if there is one, otherwise the picker when there's more
     * than one profile. Otherwise (the launcher was just restarted): stay on the last profile.
     */
    fun startup(profiles: List<ProfileSummary>, savedId: String?, freshBoot: Boolean): Startup {
        if (profiles.isEmpty()) return Startup.Open(DefaultLayout.PROFILE_ID)
        if (freshBoot) {
            profiles.firstOrNull { it.autoLogin }?.let { return Startup.Open(it.id) }
            if (profiles.size > 1) return Startup.Pick
            return Startup.Open(profiles.single().id)
        }
        return Startup.Open(profiles.firstOrNull { it.id == savedId }?.id ?: profiles.first().id)
    }

    fun summaries(store: LayoutStore): List<ProfileSummary> =
        store.profileIds().mapNotNull { store.load(it)?.summary() }

    /** Makes and saves a new profile with the default layout. */
    fun create(store: LayoutStore, name: String, tvApps: List<String>): LayoutDocument {
        val taken = store.profileIds().toSet()
        var n = 2
        while ("p_$n" in taken) n++
        val doc = DefaultLayout.create(tvApps, profileId = "p_$n", name = name.trim().ifEmpty { "Profile $n" })
        store.save(doc)
        return doc
    }

    /** Makes and saves a new kids profile that can use [approvedApps]. */
    fun createKids(store: LayoutStore, name: String, approvedApps: List<String>, parentPin: String?): LayoutDocument {
        val doc = DefaultLayout.createKids(nextId(store), name.trim().ifEmpty { "Kids" }, approvedApps, parentPin)
        store.save(doc)
        return doc
    }

    /** Approves or removes [pkg] on the kids profile [kidsId]. Returns the saved document, or null if it isn't a kids profile. */
    fun setKidsApproval(store: LayoutStore, kidsId: String, pkg: String, approved: Boolean): LayoutDocument? {
        val doc = store.load(kidsId) ?: return null
        if (doc.profile.kids == null) return null
        val next = if (approved) LayoutEdits.approveApp(doc, pkg) else LayoutEdits.unapproveApp(doc, pkg)
        if (next !== doc) store.save(next)
        return next
    }

    private fun nextId(store: LayoutStore): String {
        val taken = store.profileIds().toSet()
        var n = 2
        while ("p_$n" in taken) n++
        return "p_$n"
    }

    /** Only one profile signs in at boot: turn it off on every profile except [keepId]. */
    fun clearAutoLoginExcept(store: LayoutStore, keepId: String) {
        for (id in store.profileIds()) {
            if (id == keepId) continue
            val doc = store.load(id) ?: continue
            if (doc.profile.autoLogin) store.save(LayoutEdits.setAutoLogin(doc, false))
        }
    }
}
