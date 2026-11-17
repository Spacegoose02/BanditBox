package com.spacegoose.banditbox.layout

/** What the picker and settings need to know about a profile without loading its rows. */
data class ProfileSummary(val id: String, val name: String, val pinHash: String?, val autoLogin: Boolean) {
    val hasPin: Boolean get() = pinHash != null
}

fun LayoutDocument.summary() =
    ProfileSummary(id = profileId, name = profile.name, pinHash = profile.pin, autoLogin = profile.autoLogin)

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

    /** Only one profile signs in at boot: turn it off on every profile except [keepId]. */
    fun clearAutoLoginExcept(store: LayoutStore, keepId: String) {
        for (id in store.profileIds()) {
            if (id == keepId) continue
            val doc = store.load(id) ?: continue
            if (doc.profile.autoLogin) store.save(LayoutEdits.setAutoLogin(doc, false))
        }
    }
}
