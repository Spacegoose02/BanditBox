package com.spacegoose.banditbox.guard

import android.content.Context
import com.spacegoose.banditbox.layout.Action
import com.spacegoose.banditbox.layout.KidsPolicy
import com.spacegoose.banditbox.layout.Remap
import java.lang.ref.WeakReference

/** What the service needs from the launcher: the open profile's rules, and how to run an action. */
interface GuardHost {
    /** The button rules of the profile in use. May read from disk; never called on the main thread. */
    fun remaps(): List<Remap>

    /** Runs [action] from the service. [context] may start activities from the background. */
    fun perform(context: Context, action: Action)

    /**
     * The open profile's kids rules with today's screen time, or null for a normal profile.
     * May read from disk; never called on the main thread.
     */
    fun kidsPolicy(): KidsPolicy? = null
}

/** Connects the launcher and the service, which run in the same process. */
object Guard {
    /** Set by the Application before any service or activity starts. */
    @Volatile
    var host: GuardHost? = null

    @Volatile
    internal var service: WeakReference<GuardService>? = null

    /** Call after the open profile's button rules change, so the service picks them up. */
    fun remapsChanged() {
        service?.get()?.reloadRules()
    }

    /** Call after a kids profile opens or closes, its rules change, or a parent unlocks it. */
    fun kidsChanged() {
        service?.get()?.reloadRules()
    }
}
