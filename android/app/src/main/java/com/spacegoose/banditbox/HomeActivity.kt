package com.spacegoose.banditbox

import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import com.spacegoose.banditbox.apps.AppActions
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppIndex
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.ui.HomeScreen
import com.spacegoose.banditbox.ui.theme.BanditBoxTheme

/**
 * The single activity. Registered as HOME, so pressing Home lands here.
 * Startup must never wait on the network: everything drawn here comes from local data.
 */
class HomeActivity : ComponentActivity() {
    private val index by lazy { AppIndex(this) }
    private val visuals by lazy { AppVisuals(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher has nowhere to go back to, so Back on the home screen does nothing.
        onBackPressedDispatcher.addCallback(this) {}
        setContent {
            BanditBoxTheme {
                HomeScreen(index = index, visuals = visuals, onAction = ::handle)
            }
        }
    }

    private fun handle(app: AppEntry, action: AppMenuAction) {
        try {
            startActivity(AppActions.intentFor(action, app, packageManager))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Keep the idle footprint low: banners are cheap to reload from PackageManager.
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> visuals.trim(0f)
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> visuals.trim(0.5f)
        }
    }
}
