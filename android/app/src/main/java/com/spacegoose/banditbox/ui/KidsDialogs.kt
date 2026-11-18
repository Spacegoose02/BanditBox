package com.spacegoose.banditbox.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.apps.AppEntry
import com.spacegoose.banditbox.apps.AppMenuAction
import com.spacegoose.banditbox.layout.Bedtime
import com.spacegoose.banditbox.layout.LayoutEdits

/** What the kids dialogs can ask the launcher to do. */
class KidsActions(
    /** Runs [then] after the parent PIN, or straight away when there is no kids profile open or no parent PIN. */
    val parentGate: (then: () -> Unit) -> Unit,
    /** Makes a kids profile named [name], locked with [parentPin] (a hash), and opens it. */
    val add: (name: String, parentPin: String) -> Unit,
    /** Approves or removes an app on another (kids) profile. */
    val setApproval: (kidsId: String, pkg: String, approved: Boolean) -> Unit,
    val hasUsageAccess: () -> Boolean,
    val openUsageAccess: () -> Unit,
    val serviceOn: () -> Boolean,
)

/** Long-press on a kids profile: Open, and parent options behind the PIN. */
@Composable
internal fun KidsAppMenuDialog(app: AppEntry, ctx: OverlayContext) {
    val close = { ctx.show(null) }
    ChoiceDialog(
        title = app.label,
        choices = listOf(
            Choice(stringResource(R.string.action_open)) { close(); ctx.deps.onAppAction(app, AppMenuAction.OPEN) },
            Choice(stringResource(R.string.kids_parent_options)) { ctx.kids.parentGate { ctx.show(Overlay.KidsAppParent(app)) } },
        ),
        onDismiss = close,
    )
}

/** Parent options for one app on a kids profile. */
@Composable
internal fun KidsAppParentDialog(app: AppEntry, ctx: OverlayContext) {
    val close = { ctx.show(null) }
    ChoiceDialog(
        title = app.label,
        choices = listOf(
            Choice(stringResource(R.string.kids_remove_app)) { close(); ctx.edit { LayoutEdits.unapproveApp(it, app.packageName) } },
            Choice(stringResource(R.string.kids_settings)) { ctx.show(Overlay.KidsSettings) },
            Choice(stringResource(R.string.settings)) { ctx.show(Overlay.Settings) },
        ),
        onDismiss = close,
    )
}

/** Everything a parent sets on a kids profile. Reached only through the parent PIN. */
@Composable
internal fun KidsSettingsDialog(ctx: OverlayContext) {
    val close = { ctx.show(null) }
    val context = LocalContext.current
    val kids = ctx.doc.profile.kids
    if (kids == null) {
        LaunchedEffect(Unit) { close() } // kids mode was turned off from elsewhere
        return
    }
    val name = ctx.doc.profile.name
    val endTitle = stringResource(R.string.kids_end_confirm, name)
    val endLabel = stringResource(R.string.kids_end)
    val off = stringResource(R.string.kids_off)
    val bedtime = kids.bedtime?.let { stringResource(R.string.kids_bedtime_range, it.start, it.end) } ?: off
    val choices = buildList {
        add(Choice(stringResource(R.string.kids_apps, kids.approvedApps.count { it in ctx.apps })) { ctx.show(Overlay.KidsApps) })
        add(Choice(stringResource(R.string.kids_limit, kids.dailyLimitMin?.let { duration(context, it) } ?: off)) { ctx.show(Overlay.KidsLimit) })
        add(Choice(stringResource(R.string.kids_bedtime, bedtime)) { ctx.show(Overlay.KidsBedtimeStart) })
        add(Choice(stringResource(R.string.kids_unlock, duration(context, kids.unlockMin))) { ctx.show(Overlay.KidsUnlock) })
        add(Choice(stringResource(R.string.kids_warn, if (kids.warnMin == 0) off else duration(context, kids.warnMin))) { ctx.show(Overlay.KidsWarn) })
        add(
            Choice(stringResource(R.string.kids_change_parent_pin)) {
                ctx.show(Overlay.NewPin { hash -> ctx.edit { LayoutEdits.setParentPin(it, hash) } })
            },
        )
        if (kids.dailyLimitMin != null && !ctx.kids.hasUsageAccess()) {
            add(Choice(stringResource(R.string.kids_usage_access)) { ctx.show(Overlay.KidsUsageAccess) })
        }
        if (!ctx.kids.serviceOn()) {
            add(Choice(stringResource(R.string.kids_service_off)) { ctx.show(Overlay.KidsServiceOff) })
        }
        add(Choice(endLabel) { ctx.show(Overlay.Confirm(endTitle, endLabel) { ctx.edit(LayoutEdits::endKids) }) })
    }
    ChoiceDialog(title = stringResource(R.string.kids_settings_title, name), choices = choices, onDismiss = close)
}

/** Every installed app, ticked when the kids profile may use it. Selecting one flips it. */
@Composable
internal fun KidsAppsDialog(ctx: OverlayContext) {
    val approved = ctx.doc.profile.kids?.approvedApps.orEmpty().toSet()
    val choices = ctx.apps.values.sortedBy { it.label.lowercase() }.map { app ->
        val on = app.packageName in approved
        Choice(stringResource(if (on) R.string.kids_app_on else R.string.kids_app_off, app.label)) {
            ctx.edit { if (on) LayoutEdits.unapproveApp(it, app.packageName) else LayoutEdits.approveApp(it, app.packageName) }
        }
    }
    ChoiceDialog(title = stringResource(R.string.kids_apps_title), choices = choices, onDismiss = { ctx.show(Overlay.KidsSettings) })
}

@Composable
internal fun KidsLimitDialog(ctx: OverlayContext) {
    val context = LocalContext.current
    val back = { ctx.show(Overlay.KidsSettings) }
    val choices = listOf(Choice(stringResource(R.string.kids_off)) { ctx.edit { LayoutEdits.setDailyLimit(it, null) }; back() }) +
        LIMIT_CHOICES.map { min -> Choice(duration(context, min)) { ctx.edit { LayoutEdits.setDailyLimit(it, min) }; back() } }
    ChoiceDialog(title = stringResource(R.string.kids_limit_title), choices = choices, onDismiss = back)
}

/** Bedtime in two steps: when it starts, then when it ends. */
@Composable
internal fun KidsBedtimeStartDialog(ctx: OverlayContext) {
    val back = { ctx.show(Overlay.KidsSettings) }
    val choices = listOf(Choice(stringResource(R.string.kids_off)) { ctx.edit { LayoutEdits.setBedtime(it, null) }; back() }) +
        BEDTIME_STARTS.map { t -> Choice(t) { ctx.show(Overlay.KidsBedtimeEnd(t)) } }
    ChoiceDialog(title = stringResource(R.string.kids_bedtime_start), choices = choices, onDismiss = back)
}

@Composable
internal fun KidsBedtimeEndDialog(start: String, ctx: OverlayContext) {
    val back = { ctx.show(Overlay.KidsSettings) }
    val choices = BEDTIME_ENDS.map { t -> Choice(t) { ctx.edit { LayoutEdits.setBedtime(it, Bedtime(start, t)) }; back() } }
    ChoiceDialog(title = stringResource(R.string.kids_bedtime_end), choices = choices, onDismiss = { ctx.show(Overlay.KidsBedtimeStart) })
}

@Composable
internal fun KidsUnlockDialog(ctx: OverlayContext) {
    val context = LocalContext.current
    val back = { ctx.show(Overlay.KidsSettings) }
    val choices = UNLOCK_CHOICES.map { min -> Choice(duration(context, min)) { ctx.edit { LayoutEdits.setUnlockMinutes(it, min) }; back() } }
    ChoiceDialog(title = stringResource(R.string.kids_unlock, ""), choices = choices, onDismiss = back)
}

@Composable
internal fun KidsWarnDialog(ctx: OverlayContext) {
    val context = LocalContext.current
    val back = { ctx.show(Overlay.KidsSettings) }
    val choices = WARN_CHOICES.map { min ->
        Choice(if (min == 0) context.getString(R.string.kids_off) else duration(context, min)) {
            ctx.edit { LayoutEdits.setWarnMinutes(it, min) }
            back()
        }
    }
    ChoiceDialog(title = stringResource(R.string.kids_warn, ""), choices = choices, onDismiss = back)
}

@Composable
internal fun KidsUsageAccessDialog(ctx: OverlayContext) {
    val context = LocalContext.current
    val back = { ctx.show(Overlay.KidsSettings) }
    ChoiceDialog(
        title = stringResource(R.string.kids_usage_access),
        message = stringResource(R.string.kids_usage_access_detail, context.packageName),
        choices = listOf(
            Choice(stringResource(R.string.kids_open_usage_access)) { ctx.show(null); ctx.kids.openUsageAccess() },
            Choice(stringResource(R.string.ok)) { back() },
        ),
        onDismiss = back,
    )
}

@Composable
internal fun KidsServiceOffDialog(ctx: OverlayContext) {
    val back = { ctx.show(Overlay.KidsSettings) }
    ChoiceDialog(
        title = stringResource(R.string.kids_service_off),
        message = stringResource(R.string.kids_service_off_detail),
        choices = listOf(
            Choice(stringResource(R.string.home_setup_title)) { ctx.show(null); ctx.openHomeSetup() },
            Choice(stringResource(R.string.ok)) { back() },
        ),
        onDismiss = back,
    )
}

/** "2 h 30 min", "45 min". */
fun duration(context: Context, minutes: Int): String =
    if (minutes >= 60) {
        if (minutes % 60 == 0) {
            context.getString(R.string.kids_hours, minutes / 60)
        } else {
            context.getString(R.string.kids_hours_minutes, minutes / 60, minutes % 60)
        }
    } else {
        context.getString(R.string.kids_minutes, minutes)
    }

private val LIMIT_CHOICES = listOf(30, 60, 90, 120, 180, 240)
private val UNLOCK_CHOICES = listOf(15, 30, 60, 120)
private val WARN_CHOICES = listOf(0, 5, 10)
private val BEDTIME_STARTS = listOf("18:30", "19:00", "19:30", "20:00", "20:30", "21:00", "21:30", "22:00")
private val BEDTIME_ENDS = listOf("06:00", "06:30", "07:00", "07:30", "08:00", "08:30", "09:00")
