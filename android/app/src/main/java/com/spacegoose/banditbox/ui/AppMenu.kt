package com.spacegoose.banditbox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.apps.menuFor

/** Long-press menu for one app. Back closes it. */
@Composable
fun AppMenu(
    app: AppEntry,
    onDismiss: () -> Unit,
    onPick: (AppMenuAction) -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(320.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                menuFor(app).forEachIndexed { i, action ->
                    Button(
                        onClick = { onPick(action) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(firstItem) else Modifier),
                    ) {
                        Text(stringResource(action.labelRes()))
                    }
                }
            }
        }
    }
    LaunchedEffect(app) { firstItem.requestFocus() }
}

@StringRes
private fun AppMenuAction.labelRes(): Int = when (this) {
    AppMenuAction.OPEN -> R.string.action_open
    AppMenuAction.APP_SETTINGS -> R.string.action_app_settings
    AppMenuAction.APP_INFO -> R.string.action_app_info
    AppMenuAction.FORCE_CLOSE -> R.string.action_force_close
    AppMenuAction.UNINSTALL -> R.string.action_uninstall
}
