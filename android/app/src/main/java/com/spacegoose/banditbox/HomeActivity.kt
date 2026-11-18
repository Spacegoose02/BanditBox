package com.spacegoose.banditbox

import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spacegoose.banditbox.apps.AppActions
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppIndex
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.AppVisuals
import com.spacegoose.banditbox.content.TvProviderReader
import com.spacegoose.banditbox.layout.LayoutStore
import com.spacegoose.banditbox.ui.BanditBoxApp
import com.spacegoose.banditbox.ui.LauncherDeps
import com.spacegoose.banditbox.ui.Posters
import com.spacegoose.banditbox.ui.ShowRequest
import com.spacegoose.banditbox.ui.theme.BanditBoxTheme
import java.io.File

/**
 * The single activity. Registered as HOME, so pressing Home lands here.
 * Startup must never wait on the network: everything drawn here comes from local data.
 */
class HomeActivity : ComponentActivity() {
    private val visuals by lazy { AppVisuals(this) }
    private var homePresses by mutableIntStateOf(0)
    private var request by mutableStateOf<ShowRequest?>(null)
    private var requestCount = 0
    private var foreground by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On the main screen Back does nothing: a launcher has nowhere to go back to.
        // Screens inside the launcher (All apps) register their own Back handlers on top of this.
        onBackPressedDispatcher.addCallback(this) {}

        val settings = DeviceSettings(this)
        // Only the first creation can be a boot; a recreated activity (savedInstanceState) never is.
        val freshBoot = savedInstanceState == null && settings.consumeFreshBoot()
        if (freshBoot) settings.launchOnBoot?.let { pkg -> launchOnBoot(pkg) }

        val deps = LauncherDeps(
            index = AppIndex(this),
            visuals = visuals,
            store = LayoutStore(File(filesDir, "layouts")),
            settings = settings,
            tv = TvProviderReader(this),
            freshBoot = freshBoot,
            launch = ::launchFirst,
            onAppAction = ::handle,
            onRemapsChanged = { (application as BanditBoxApplication).updateRemaps(it) },
            restart = ::recreate,
        )
        if (savedInstanceState == null) request = showRequest(intent)
        setContent {
            BanditBoxTheme {
                BanditBoxApp(deps = deps, homePresses = homePresses, request = request, foreground = foreground)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    /**
     * Home pressed while BanditBox is already in front: back to the main screen. A remote button
     * shortcut can instead ask for a screen (All apps, Settings).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val show = showRequest(intent)
        if (show != null) request = show else homePresses++
    }

    private fun showRequest(intent: Intent?): ShowRequest? =
        intent?.getStringExtra(RemapActions.EXTRA_SHOW)?.let { ShowRequest(it, ++requestCount) }

    /** Opens the "Launch on boot" app. BanditBox stays underneath, so Home comes back here. */
    private fun launchOnBoot(packageName: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: return
        launchFirst(listOf(intent))
    }

    private fun handle(app: AppEntry, action: AppMenuAction) {
        launchFirst(listOf(AppActions.intentFor(action, app, packageManager)))
    }

    private fun launchFirst(intents: List<Intent>) {
        for (intent in intents) {
            try {
                startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next one.
            } catch (_: SecurityException) {
                // Try the next one.
            }
        }
        Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Keep the idle footprint low: banners are cheap to reload from PackageManager.
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> visuals.trim(0f)
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> visuals.trim(0.5f)
        }
        // Posters come back from the disk cache when the home screen is drawn again.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Posters.trim()
    }
}
