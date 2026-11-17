package com.spacegoose.banditbox.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.menuFor

/** Long-press menu for one app. */
@Composable
fun AppMenu(app: AppEntry, onDismiss: () -> Unit, onPick: (AppMenuAction) -> Unit) {
    val choices = menuFor(app).map { action -> Choice(stringResource(action.labelRes())) { onPick(action) } }
    ChoiceDialog(title = app.label, choices = choices, onDismiss = onDismiss)
}

@StringRes
private fun AppMenuAction.labelRes(): Int = when (this) {
    AppMenuAction.OPEN -> R.string.action_open
    AppMenuAction.APP_SETTINGS -> R.string.action_app_settings
    AppMenuAction.APP_INFO -> R.string.action_app_info
    AppMenuAction.FORCE_CLOSE -> R.string.action_force_close
    AppMenuAction.UNINSTALL -> R.string.action_uninstall
}
