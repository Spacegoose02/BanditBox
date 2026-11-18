package com.spacegoose.banditbox.guard

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Home button override for TVs whose stock launcher keeps Home (Google TV on the onn 4K).
 * When the stock launcher's Home screen comes to the front, BanditBox is opened on top of it,
 * the same approach Projectivy uses. Does nothing when BanditBox is already the default Home app.
 */
class GuardService : AccessibilityService() {
    private var override: HomeOverride? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        override = HomeOverride(GuardStatus.stockHomeActivities(this))
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
}
