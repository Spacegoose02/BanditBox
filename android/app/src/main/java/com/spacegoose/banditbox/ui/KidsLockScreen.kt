package com.spacegoose.banditbox.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.layout.KidsLock
import java.util.Date

/**
 * Covers the home screen of a kids profile at bedtime or once the day's time is up. Only the
 * parent PIN gets past it: to unlock for a while, or to switch to another profile.
 */
@Composable
fun KidsLockScreen(lock: KidsLock, endsAtMs: Long?, onUnlock: () -> Unit, onSwitchProfile: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val title = when (lock) {
        KidsLock.BEDTIME -> stringResource(R.string.kids_lock_bedtime)
        KidsLock.TIME_UP -> stringResource(R.string.kids_lock_time_up)
    }
    val detail = when {
        lock == KidsLock.BEDTIME && endsAtMs != null ->
            stringResource(R.string.kids_lock_bedtime_until, DateFormat.getTimeFormat(context).format(Date(endsAtMs)))
        lock == KidsLock.TIME_UP -> stringResource(R.string.kids_lock_time_up_detail)
        else -> ""
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = if (lock == KidsLock.BEDTIME) "🌙" else "⏰", style = MaterialTheme.typography.displayLarge)
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onUnlock, modifier = Modifier.focusRequester(first)) { Text(stringResource(R.string.kids_parent_unlock)) }
            Button(onClick = onSwitchProfile) { Text(stringResource(R.string.switch_profile)) }
        }
    }
    LaunchedEffect(lock) { first.requestFocus() }
}
