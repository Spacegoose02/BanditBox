package com.spacegoose.banditbox.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.spacegoose.banditbox.guard.Guard
import com.spacegoose.banditbox.guard.GuardStatus
import com.spacegoose.banditbox.sync.TvActivity

/**
 * Watches what the TV is doing for Home Assistant: the app in front (exactly while the
 * BanditBox service is on; otherwise only "BanditBox" or "another app"), whether sound is
 * playing (Android's music stream, which video apps use too), and whether the screen is on.
 * Changes go to [send] after a short pause, so a quick app switch is one report.
 *
 * Needs no permission. Sound is checked every few seconds, and only while the screen is on.
 */
class ActivityReporter(context: Context, private val send: (TvActivity) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private var front: String? = null
    private var playing = false
    private var screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    private var started = false

    private val poll = object : Runnable {
        override fun run() {
            val now = audio?.isMusicActive == true
            if (now != playing) {
                playing = now
                changed()
            }
            if (screenOn) main.postDelayed(this, POLL_MS)
        }
    }
    private val report = Runnable { send(current()) }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            main.removeCallbacks(poll)
            if (screenOn) {
                main.post(poll)
            } else {
                playing = false
            }
            changed()
        }
    }

    /** Starts watching; call once, on the main thread. */
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Screen on and off are system broadcasts, which reach a receiver that isn't exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(screen, filter)
        }
        Guard.onForeground = { pkg ->
            front = pkg
            changed()
        }
        if (screenOn) main.post(poll)
        changed()
    }

    /** BanditBox's own screen came to the front or left it. */
    fun launcherShown(shown: Boolean) {
        if (shown) {
            front = app.packageName
        } else if (!GuardStatus.isServiceEnabled(app)) {
            // Without the service, all we know is that some other app is in front.
            front = ""
        }
        changed()
    }

    private fun changed() {
        main.removeCallbacks(report)
        main.postDelayed(report, DEBOUNCE_MS)
    }

    private fun current(): TvActivity {
        val pkg = front.orEmpty()
        return TvActivity(app = pkg, appLabel = label(pkg), playing = playing, screenOn = screenOn)
    }

    private fun label(pkg: String): String {
        if (pkg.isEmpty()) return ""
        return try {
            val info = app.packageManager.getApplicationInfo(pkg, 0)
            app.packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val DEBOUNCE_MS = 1_500L
    }
}
