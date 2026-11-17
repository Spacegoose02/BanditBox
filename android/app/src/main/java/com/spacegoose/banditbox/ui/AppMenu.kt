package com.spacegoose.banditbox.ui

import androidx.annotation.StringRes
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppMenuAction

@StringRes
fun AppMenuAction.labelRes(): Int = when (this) {
    AppMenuAction.OPEN -> R.string.action_open
    AppMenuAction.APP_SETTINGS -> R.string.action_app_settings
    AppMenuAction.APP_INFO -> R.string.action_app_info
    AppMenuAction.FORCE_CLOSE -> R.string.action_force_close
    AppMenuAction.UNINSTALL -> R.string.action_uninstall
}
