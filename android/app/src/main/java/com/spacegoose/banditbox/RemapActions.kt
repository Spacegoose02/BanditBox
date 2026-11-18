package com.spacegoose.banditbox

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.spacegoose.banditbox.guard.GuardStatus
import com.spacegoose.banditbox.layout.Action
import com.spacegoose.banditbox.system.passthroughInputs

/** Runs a remote button rule. Called from the BanditBox service, which may start activities from the background. */
object RemapActions {
    /** Extra on BanditBox's own Home intent: which screen to show. */
    const val EXTRA_SHOW = "com.spacegoose.banditbox.SHOW"
    const val SHOW_ALL_APPS = "allApps"
    const val SHOW_SETTINGS = "settings"

    fun perform(context: Context, action: Action) {
        val intent = intentFor(context, action) ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("BanditBox", "Couldn't run button action $action", e)
        } catch (e: SecurityException) {
            Log.w("BanditBox", "Couldn't run button action $action", e)
        }
    }

    private fun intentFor(context: Context, action: Action): Intent? = when (action) {
        is Action.OpenApp -> context.packageManager.let { pm ->
            pm.getLeanbackLaunchIntentForPackage(action.packageName) ?: pm.getLaunchIntentForPackage(action.packageName)
        }
        // Inputs are saved by id; a label also matches, for documents written by hand.
        is Action.OpenInput -> passthroughInputs(context)
            .firstOrNull { it.id == action.input || it.label.equals(action.input, ignoreCase = true) }
            ?.intent()
        Action.Home -> GuardStatus.homeIntent(context)
        Action.AllApps -> show(context, SHOW_ALL_APPS)
        Action.LauncherSettings -> show(context, SHOW_SETTINGS)
        Action.Settings -> Intent(Settings.ACTION_SETTINGS)
        Action.None -> null
    }

    private fun show(context: Context, screen: String) =
        Intent(context, HomeActivity::class.java).putExtra(EXTRA_SHOW, screen)
}
