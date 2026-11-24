package com.spacegoose.banditbox.ui

/**
 * Keeps a dialog from acting on the OK press that opened it.
 *
 * Holding OK on a tile opens its menu while the button is still down, and the menu's first
 * button ("Open") takes focus. Without this, the held key's repeats and its release land on
 * that button, so the menu flashes and the app opens. The guard swallows OK events until the
 * dialog sees a fresh press (a key-down with no repeats), then lets everything through.
 */
class HeldSelectGuard {
    private var armed = false

    /** True when this event should be swallowed. */
    fun shouldConsume(isSelectKey: Boolean, isDown: Boolean, repeatCount: Int): Boolean {
        if (!isSelectKey || armed) return false
        if (isDown && repeatCount == 0) {
            armed = true
            return false
        }
        return true
    }
}
