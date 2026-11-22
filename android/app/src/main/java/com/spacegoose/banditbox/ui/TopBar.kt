package com.spacegoose.banditbox.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if ("clock" in items) Clock()
        for (item in items) {
            if (WidgetFeeds.isFeedId(item)) feed(item)?.let { Box(Modifier.padding(start = 12.dp)) { TopBarFeed(it) } }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        TopBarButton(BanditIcons.Search, stringResource(R.string.search), actions.onSearch)
        TopBarButton(BanditIcons.Apps, stringResource(R.string.all_apps), actions.onAllApps)
        for (item in items) {
            when (item) {
                "inputs" -> if (hasInputs && !kidsMode) TopBarButton(BanditIcons.Input, stringResource(R.string.inputs), actions.onInputs)
                "network" -> if (!kidsMode) NetworkButton(onClick = actions.onNetwork)
                "settings" -> TopBarButton(BanditIcons.Settings, stringResource(R.string.settings), actions.onSettings)
            }
        }
        // The profile sits last, as an avatar with its name, like the stock TV home screens.
        if ("profile" in items) ProfileButton(profileName, actions.onProfile)
    }
}

/**
 * A round icon button that widens to show its name while focused, so the bar stays calm
 * but every button still says what it does when you land on it.
 */
@Composable
private fun TopBarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        interactionSource = interaction,
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = topBarColors(),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier.animateContentSize(tween(180)),
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(22.dp))
        if (focused) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun ProfileButton(name: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = topBarColors(),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).widthIn(max = 160.dp),
        )
    }
}

/** Glassy when resting, solid light when focused: easy to spot from the couch. */
@Composable
private fun topBarColors() = ButtonDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.08f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.onSurface,
    focusedContentColor = MaterialTheme.colorScheme.surface,
)

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
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = time,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
        )
        Text(
            text = date,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 6.dp),
        )
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
    val icon = when (kind) {
        NetworkKind.ETHERNET -> BanditIcons.Ethernet
        NetworkKind.OFFLINE -> BanditIcons.Offline
        NetworkKind.WIFI, NetworkKind.OTHER -> BanditIcons.Wifi
    }
    TopBarButton(icon, stringResource(label), onClick)
}

private const val MINUTE_MS = 60_000L
