package com.spacegoose.banditbox.sync

/**
 * What the TV is doing, sent to the server for Home Assistant: the app in front ("" when not
 * known), whether something is playing, and whether the screen is on.
 */
data class TvActivity(
    val app: String,
    val appLabel: String,
    val playing: Boolean,
    val screenOn: Boolean,
)
