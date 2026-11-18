package com.spacegoose.banditbox.guard

import android.accessibilityservice.AccessibilityService

/**
 * A button with a rule is taken over completely, so a press without its own rule has to be
 * redone by hand. Android lets an accessibility service redo only a few buttons this way.
 */
object KeyReplay {
    /** The global action that redoes [key]'s normal press on this Android version, or null. */
    fun globalAction(key: String, sdk: Int): Int? = when (key) {
        "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
        "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
        else -> if (sdk >= TIRAMISU) dpad[key] else null
    }

    /** True when [key]'s normal press still works after giving it only long or double rules. */
    fun canReplay(key: String, sdk: Int): Boolean = globalAction(key, sdk) != null

    private const val TIRAMISU = 33

    // GLOBAL_ACTION_DPAD_* are API 33; the values are inlined so this compiles against minSdk 30.
    private val dpad = mapOf(
        "DPAD_UP" to 16,
        "DPAD_DOWN" to 17,
        "DPAD_LEFT" to 18,
        "DPAD_RIGHT" to 19,
        "DPAD_CENTER" to 20,
    )
}
