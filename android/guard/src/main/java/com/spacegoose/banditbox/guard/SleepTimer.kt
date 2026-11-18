package com.spacegoose.banditbox.guard

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import com.spacegoose.banditbox.layout.SleepSteps
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Puts the TV to sleep after a set time, whatever app is in front. When the time is up, playback
 * is paused and the BanditBox service turns the screen off. With the service off, BanditBox comes
 * to the front instead, which at least stops most players. A minute before, a toast warns.
 *
 * Lives in memory only: if Android stops BanditBox, the timer is gone, which errs on the side of
 * leaving the TV on.
 */
object SleepTimer {
    private const val WARN_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** When the TV goes to sleep, in wall-clock milliseconds, or null when the timer is off. */
    @Volatile
    var endsAtMs: Long? = null
        private set

    fun minutesLeft(): Int? = SleepSteps.minutesLeft(endsAtMs, System.currentTimeMillis())

    /** [listener] runs on the main thread whenever the timer is set, cleared or runs out. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Sets the timer to [minutes] from now, or turns it off with null. Call on the main thread. */
    fun set(context: Context, minutes: Int?) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(token)
        endsAtMs = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        if (minutes != null) {
            val ms = minutes * 60_000L
            val start = SystemClock.uptimeMillis()
            if (ms > WARN_MS) main.postAtTime({ toast(app, app.getString(R.string.sleep_soon)) }, token, start + ms - WARN_MS)
            main.postAtTime({ sleepNow(app) }, token, start + ms)
        }
        listeners.forEach { it() }
    }

    /** One press of the sleep button: the next step, with a toast that says where it landed. */
    fun step(context: Context) {
        val next = SleepSteps.next(minutesLeft())
        set(context, next)
        val text = if (next == null) {
            context.getString(R.string.sleep_off)
        } else {
            context.resources.getQuantityString(R.plurals.sleep_in, next, next)
        }
        toast(context.applicationContext, text)
    }

    private fun sleepNow(context: Context) {
        endsAtMs = null
        listeners.forEach { it() }
        pauseMedia(context)
        val service = Guard.service?.get()
        if (service != null && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return
        try {
            context.startActivity(GuardStatus.homeIntent(context))
        } catch (_: ActivityNotFoundException) {
            // BanditBox's own Home activity is always there.
        }
    }

    /** Sends a pause key to whatever is playing; needs no permission. */
    private fun pauseMedia(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
