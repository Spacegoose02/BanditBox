package com.spacegoose.banditbox.ui

import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.guard.GuardService
import com.spacegoose.banditbox.guard.GuardStatus
import com.spacegoose.banditbox.guard.KeyReplay
import com.spacegoose.banditbox.layout.Action
import com.spacegoose.banditbox.layout.LayoutEdits
import com.spacegoose.banditbox.layout.Press
import com.spacegoose.banditbox.layout.Remap

/** Buttons most TV remotes have, offered by name. Any other button can be picked by pressing it. */
private val KNOWN_KEYS = listOf(
    "HOME" to R.string.key_home,
    "BACK" to R.string.key_back,
    "MENU" to R.string.key_menu,
    "PROG_RED" to R.string.key_red,
    "PROG_GREEN" to R.string.key_green,
    "PROG_YELLOW" to R.string.key_yellow,
    "PROG_BLUE" to R.string.key_blue,
    "BOOKMARK" to R.string.key_bookmark,
    "GUIDE" to R.string.key_guide,
    "TV_INPUT" to R.string.key_input,
    "SETTINGS" to R.string.key_settings,
)

/** Settings → Remote buttons: every shortcut of this profile, and a way to add one. */
@Composable
fun ButtonsDialog(ctx: OverlayContext) {
    val context = LocalContext.current
    val close = { ctx.show(null) }
    val serviceOn = remember { GuardStatus.isServiceEnabled(context) }
    val rules = ctx.doc.remap
    val choices = buildList {
        if (!serviceOn) add(Choice(stringResource(R.string.buttons_service_off)) { close(); ctx.openHomeSetup() })
        rules.forEach { rule ->
            val label = stringResource(R.string.button_rule, keyLabel(rule.key), pressLabel(rule.press), actionLabel(rule.action, ctx))
            add(Choice(label) { ctx.show(Overlay.ButtonRule(rule)) })
        }
        add(Choice(stringResource(R.string.buttons_add)) { ctx.show(Overlay.PickKey) })
    }
    ChoiceDialog(
        title = stringResource(R.string.buttons_title),
        choices = choices,
        onDismiss = close,
        message = if (rules.isEmpty()) stringResource(R.string.buttons_none) else null,
    )
}

@Composable
fun ButtonRuleDialog(rule: Remap, ctx: OverlayContext) {
    val back = { ctx.show(Overlay.Buttons) }
    ChoiceDialog(
        title = stringResource(R.string.button_rule, keyLabel(rule.key), pressLabel(rule.press), actionLabel(rule.action, ctx)),
        choices = listOf(
            Choice(stringResource(R.string.button_change_action)) { ctx.show(Overlay.PickAction(rule.key, rule.press)) },
            Choice(stringResource(R.string.button_remove)) {
                ctx.edit { LayoutEdits.removeRemap(it, rule.key, rule.press) }
                back()
            },
        ),
        onDismiss = back,
    )
}

@Composable
fun PickKeyDialog(ctx: OverlayContext) {
    val choices = buildList {
        add(Choice(stringResource(R.string.button_detect)) { ctx.show(Overlay.DetectKey) })
        KNOWN_KEYS.forEach { (key, label) -> add(Choice(stringResource(label)) { ctx.show(Overlay.PickPress(key)) }) }
    }
    ChoiceDialog(title = stringResource(R.string.button_pick_key), choices = choices, onDismiss = { ctx.show(Overlay.Buttons) })
}

/**
 * Waits for one button press and uses that button. Back cancels, so Back itself is picked from
 * the list instead. A button that already has a shortcut is taken by the service and never
 * arrives here; pick it from the list too.
 */
@Composable
fun DetectKeyDialog(ctx: OverlayContext) {
    val focus = remember { FocusRequester() }
    val cancel = { ctx.show(Overlay.PickKey) }
    BanditDialog(onDismissRequest = cancel) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(420.dp)) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        // Act on release, so the press doesn't also click something in the next dialog.
                        if (event.type == KeyEventType.KeyUp) {
                            val code = event.nativeKeyEvent.keyCode
                            if (code == AndroidKeyEvent.KEYCODE_BACK) cancel() else ctx.show(Overlay.PickPress(GuardService.keyName(code)))
                        }
                        true
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.button_detect_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.button_detect_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun PickPressDialog(key: String, ctx: OverlayContext) {
    val hasShort = ctx.doc.remap.any { it.key == key && it.press == Press.SHORT }
    // Without a short rule, a short press has to be redone by the service, which Android allows only for a few buttons.
    val warn = !hasShort && !KeyReplay.canReplay(key, Build.VERSION.SDK_INT)
    ChoiceDialog(
        title = stringResource(R.string.button_pick_press, keyLabel(key)),
        choices = Press.entries.map { press -> Choice(pressLabel(press)) { ctx.show(Overlay.PickAction(key, press)) } },
        onDismiss = { ctx.show(Overlay.PickKey) },
        message = if (warn) stringResource(R.string.button_no_replay) else null,
    )
}

@Composable
fun PickActionDialog(key: String, press: Press, ctx: OverlayContext) {
    val set = { action: Action ->
        ctx.edit { LayoutEdits.setRemap(it, key, press, action) }
        ctx.show(Overlay.Buttons)
    }
    val choices = buildList {
        add(Choice(stringResource(R.string.action_open_app)) { ctx.show(Overlay.PickApp(key, press)) })
        if (ctx.inputs.isNotEmpty()) add(Choice(stringResource(R.string.action_open_input)) { ctx.show(Overlay.PickInput(key, press)) })
        add(Choice(stringResource(R.string.action_home)) { set(Action.Home) })
        add(Choice(stringResource(R.string.action_all_apps)) { set(Action.AllApps) })
        add(Choice(stringResource(R.string.action_launcher_settings)) { set(Action.LauncherSettings) })
        add(Choice(stringResource(R.string.action_android_settings)) { set(Action.Settings) })
        add(Choice(stringResource(R.string.action_sleep_timer)) { set(Action.SleepTimer) })
        add(Choice(stringResource(R.string.action_none)) { set(Action.None) })
    }
    ChoiceDialog(
        title = stringResource(R.string.button_pick_action),
        choices = choices,
        onDismiss = { ctx.show(Overlay.PickPress(key)) },
    )
}

@Composable
fun PickAppDialog(key: String, press: Press, ctx: OverlayContext) {
    val apps = remember(ctx.apps) { ctx.apps.values.sortedBy { it.label.lowercase() } }
    ChoiceDialog(
        title = stringResource(R.string.button_pick_app),
        choices = apps.map { app ->
            Choice(app.label) {
                ctx.edit { LayoutEdits.setRemap(it, key, press, Action.OpenApp(app.packageName)) }
                ctx.show(Overlay.Buttons)
            }
        },
        onDismiss = { ctx.show(Overlay.PickAction(key, press)) },
    )
}

@Composable
fun PickInputDialog(key: String, press: Press, ctx: OverlayContext) {
    ChoiceDialog(
        title = stringResource(R.string.button_pick_input),
        choices = ctx.inputs.map { input ->
            Choice(input.label) {
                ctx.edit { LayoutEdits.setRemap(it, key, press, Action.OpenInput(input.id)) }
                ctx.show(Overlay.Buttons)
            }
        },
        onDismiss = { ctx.show(Overlay.PickAction(key, press)) },
    )
}

@Composable
private fun keyLabel(key: String): String {
    val known = KNOWN_KEYS.firstOrNull { it.first == key }?.second
    return if (known != null) stringResource(known) else key.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun pressLabel(press: Press): String = stringResource(press.labelRes())

@StringRes
private fun Press.labelRes() = when (this) {
    Press.SHORT -> R.string.press_short
    Press.LONG -> R.string.press_long
    Press.DOUBLE -> R.string.press_double
}

@Composable
private fun actionLabel(action: Action, ctx: OverlayContext): String = when (action) {
    is Action.OpenApp -> stringResource(R.string.action_open_app_named, ctx.apps[action.packageName]?.label ?: action.packageName)
    is Action.OpenInput -> stringResource(
        R.string.action_open_input_named,
        ctx.inputs.firstOrNull { it.id == action.input }?.label ?: action.input,
    )
    Action.Home -> stringResource(R.string.action_home)
    Action.AllApps -> stringResource(R.string.action_all_apps)
    Action.LauncherSettings -> stringResource(R.string.action_launcher_settings)
    Action.Settings -> stringResource(R.string.action_android_settings)
    Action.SleepTimer -> stringResource(R.string.action_sleep_timer)
    Action.None -> stringResource(R.string.action_none)
}
