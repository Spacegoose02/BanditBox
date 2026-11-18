package com.spacegoose.banditbox.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Gives [requester] focus as soon as its item is on screen. Lazy rows and grids lay their
 * items out a frame or two after the screen appears, so this retries for a few frames.
 */
suspend fun focusWhenReady(requester: FocusRequester, frames: Int = 10) {
    repeat(frames) {
        withFrameNanos { }
        // requestFocus() throws until the item is attached, and may also report failure.
        val result: Any? = runCatching { requester.requestFocus() }.getOrNull()
        if (result != null && result != false) return
    }
}
