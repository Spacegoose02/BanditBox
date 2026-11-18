package com.spacegoose.banditbox.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.layout.WidgetFeed
import com.spacegoose.banditbox.layout.WidgetFeeds
import com.spacegoose.banditbox.system.NetworkKind
import com.spacegoose.banditbox.system.networkKind
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/** What the top bar's buttons do. Kept separate so the bar stays a plain drawing. */
class TopBarActions(
    val onSearch: () -> Unit,
    val onAllApps: () -> Unit,
    val onInputs: () -> Unit,
    val onNetwork: () -> Unit,
    val onSettings: () -> Unit,
    val onProfile: () -> Unit,
)

/**
 * The top bar, drawn from the layout's topBar list in order. The clock and widget feeds (such
 * as the weather) sit on the left, buttons on the right. Unknown items are skipped.
 */
@Composable
fun TopBar(
    items: List<String>,
    profileName: String,
    hasInputs: Boolean,
    actions: TopBarActions,
    modifier: Modifier = Modifier,
    /** Kids profile: no inputs or network buttons, which lead out of the launcher. */
    kidsMode: Boolean = false,
    /** Shown after the clock, for example a kids profile's time left today. */
    note: String? = null,
    /** Looks up a widget feed named in [items], as the open profile may see it. */
    feed: (String) -> WidgetFeed? = { null },
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if ("clock" in items) Clock()
        for (item in items) {
            if (WidgetFeeds.isFeedId(item)) feed(item)?.let { TopBarFeed(it) }
        }
        if (note != null) {
            Text(text = note, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = actions.onSearch) { Text(stringResource(R.string.search)) }
        Button(onClick = actions.onAllApps) { Text(stringResource(R.string.all_apps)) }
        for (item in items) {
            when (item) {
                "profile" -> Button(onClick = actions.onProfile) { Text(profileName) }
                "inputs" -> if (hasInputs && !kidsMode) Button(onClick = actions.onInputs) { Text(stringResource(R.string.inputs)) }
                "network" -> if (!kidsMode) NetworkButton(onClick = actions.onNetwork)
                "settings" -> Button(onClick = actions.onSettings) { Text(stringResource(R.string.settings)) }
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE_MS - now % MINUTE_MS) // wake on the minute, not every second
        }
    }
    val time = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    val date = remember(now) {
        DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"), now).toString()
    }
    Column {
        Text(text = time, style = MaterialTheme.typography.headlineMedium)
        Text(text = date, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NetworkButton(onClick: () -> Unit) {
    val context = LocalContext.current
    val flow = remember(context) { networkKind(context) }
    val kind by flow.collectAsState(initial = NetworkKind.OTHER)
    val label = when (kind) {
        NetworkKind.ETHERNET -> R.string.network_ethernet
        NetworkKind.WIFI -> R.string.network_wifi
        NetworkKind.OTHER -> R.string.network
        NetworkKind.OFFLINE -> R.string.network_offline
    }
    Button(onClick = onClick) { Text(stringResource(label)) }
}

private const val MINUTE_MS = 60_000L
