package com.spacegoose.banditbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.guard.GuardStatus
import com.spacegoose.banditbox.guard.SleepTimer
import com.spacegoose.banditbox.layout.SleepSteps
import kotlinx.coroutines.delay

/** Minutes left on the sleep timer, or null when it's off; follows changes and ticks each half minute. */
@Composable
fun rememberSleepMinutes(): Int? {
    var endsAt by remember { mutableStateOf(SleepTimer.endsAtMs) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = { endsAt = SleepTimer.endsAtMs }
        SleepTimer.addListener(listener)
        onDispose { SleepTimer.removeListener(listener) }
    }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            delay(30_000)
            tick++
        }
    }
    return remember(endsAt, tick) { SleepSteps.minutesLeft(endsAt, System.currentTimeMillis()) }
}

@Composable
internal fun sleepTimerLabel(): String {
    val context = LocalContext.current
    val left = rememberSleepMinutes()
    return if (left == null) stringResource(R.string.sleep_timer) else stringResource(R.string.sleep_timer_on, duration(context, left))
}

/** Off, or one of the steps; the message says what happens when the time is up. */
@Composable
internal fun SleepTimerDialog(ctx: OverlayContext) {
    val context = LocalContext.current
    val close = { ctx.show(null) }
    val left = rememberSleepMinutes()
    val choices = listOf(Choice(stringResource(R.string.sleep_timer_off)) { SleepTimer.set(context, null); close() }) +
        SleepSteps.MINUTES.map { min -> Choice(duration(context, min)) { SleepTimer.set(context, min); close() } }
    ChoiceDialog(
        title = if (left == null) stringResource(R.string.sleep_timer) else stringResource(R.string.sleep_timer_on, duration(context, left)),
        choices = choices,
        onDismiss = close,
        message = stringResource(
            if (GuardStatus.isServiceEnabled(context)) R.string.sleep_timer_detail_service else R.string.sleep_timer_detail_no_service,
        ),
    )
}
