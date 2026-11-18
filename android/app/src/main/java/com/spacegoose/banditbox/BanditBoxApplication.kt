package com.spacegoose.banditbox

import android.app.Application
import android.content.Context
import android.util.Log
import com.spacegoose.banditbox.guard.Guard
import com.spacegoose.banditbox.guard.GuardHost
import com.spacegoose.banditbox.kids.KidsDevice
import com.spacegoose.banditbox.layout.Action
import com.spacegoose.banditbox.layout.KidsPolicy
import com.spacegoose.banditbox.layout.KidsRules
import com.spacegoose.banditbox.layout.LayoutStore
import com.spacegoose.banditbox.layout.Remap
import java.io.File
import java.io.IOException

/**
 * Hands the open profile's button rules to the BanditBox service. The service can start before
 * the launcher has drawn anything (Google TV keeps its own Home), so the rules are read from disk
 * the first time they are needed and kept up to date by the launcher after that.
 */
class BanditBoxApplication : Application(), GuardHost {
    @Volatile
    private var remaps: List<Remap>? = null

    override fun onCreate() {
        super.onCreate()
        Guard.host = this
    }

    override fun remaps(): List<Remap> = remaps ?: readRemaps().also { remaps = it }

    val kidsDevice by lazy { KidsDevice(this) }

    /** The open profile's kids rules, read fresh from disk so the service never acts on stale limits. */
    override fun kidsPolicy(): KidsPolicy? {
        val id = DeviceSettings(this).currentProfileId ?: return null
        val kids = try {
            LayoutStore(File(filesDir, "layouts")).load(id)?.profile?.kids
        } catch (e: IOException) {
            Log.w("BanditBox", "Couldn't read kids rules", e)
            null
        } ?: return null
        return kidsDevice.policy(id, kids)
    }

    /** Called by the launcher whenever the open profile or its rules change. */
    fun updateRemaps(latest: List<Remap>) {
        if (latest == remaps) return
        remaps = latest
        Guard.remapsChanged()
    }

    override fun perform(context: Context, action: Action) = RemapActions.perform(context, action)

    private fun readRemaps(): List<Remap> {
        val id = DeviceSettings(this).currentProfileId ?: return emptyList()
        return try {
            LayoutStore(File(filesDir, "layouts")).load(id)?.let(KidsRules::allowedRemaps).orEmpty()
        } catch (e: IOException) {
            Log.w("BanditBox", "Couldn't read button rules", e)
            emptyList()
        }
    }
}
