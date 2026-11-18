package com.spacegoose.banditbox.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.spacegoose.banditbox.layout.Press
import com.spacegoose.banditbox.layout.Remap
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * Two optional jobs, both off until the user turns the service on:
 * - Home override for TVs whose stock launcher keeps Home (Google TV on the onn 4K). When the
 *   stock launcher's Home screen comes to the front, BanditBox is opened on top of it, the same
 *   approach Projectivy uses. Does nothing when BanditBox is already the default Home app.
 * - Button remapping: short, long and double presses run the open profile's rules. Buttons are
 *   only filtered while there are rules, so with none set the remote is untouched.
 */
class GuardService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var override: HomeOverride? = null
    private var rules: Map<String, Map<Press, Remap>> = emptyMap()
    private val presses = PressDetector(
        pressesFor = { key -> rules[key]?.keys.orEmpty() },
        scheduler = { delayMs, block ->
            val runnable = Runnable(block)
            main.postDelayed(runnable, delayMs)
            PressDetector.Cancellable { main.removeCallbacks(runnable) }
        },
        onPress = ::onPress,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        override = HomeOverride(GuardStatus.stockHomeActivities(this))
        Guard.service = WeakReference(this)
        reloadRules()
    }

    override fun onDestroy() {
        if (Guard.service?.get() === this) Guard.service = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Reads the rules off the main thread, then applies them and turns button filtering on or off. */
    internal fun reloadRules() {
        val host = Guard.host ?: return
        thread(name = "guard-rules") {
            val loaded = try {
                host.remaps()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Couldn't read button rules", e)
                emptyList()
            }
            main.post { applyRules(loaded) }
        }
    }

    private fun applyRules(list: List<Remap>) {
        presses.clear()
        rules = list.groupBy { it.key }.mapValues { (_, byKey) -> byKey.associateBy { it.press } }
        val info = serviceInfo ?: return
        val filtering = rules.isNotEmpty()
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val flags = if (filtering) info.flags or flag else info.flags and flag.inv()
        if (flags != info.flags) {
            info.flags = flags
            serviceInfo = info
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (rules.isEmpty()) return false
        val isDown = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        if (event.isCanceled) {
            presses.clear()
            return rules.containsKey(keyName(event.keyCode))
        }
        return presses.onKey(keyName(event.keyCode), isDown, event.repeatCount)
    }

    private fun onPress(key: String, press: Press) {
        val rule = rules[key]?.get(press)
        if (rule != null) {
            Guard.host?.perform(this, rule.action)
            return
        }
        // Only a short press can be without a rule here: redo the button's normal job if Android lets us.
        KeyReplay.globalAction(key, Build.VERSION.SDK_INT)?.let(::performGlobalAction)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        val decide = override ?: return
        if (!decide.shouldRedirect(pkg, cls, SystemClock.uptimeMillis())) return
        if (GuardStatus.isDefaultHome(this)) return
        try {
            startActivity(GuardStatus.homeIntent(this))
        } catch (_: ActivityNotFoundException) {
            // BanditBox's own Home activity is always there; nothing sensible to do if not.
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "BanditBoxGuard"

        /** "KEYCODE_PROG_RED" becomes "PROG_RED", the form the layout document uses. */
        fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}
