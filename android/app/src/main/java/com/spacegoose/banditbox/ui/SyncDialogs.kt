package com.spacegoose.banditbox.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.sync.ServerFinder
import com.spacegoose.banditbox.sync.SyncController.PairStep

/** Settings → Sync: find a server and pair, or see how syncing is going and stop it. */
@Composable
fun SyncDialog(ctx: OverlayContext) {
    val sync = ctx.deps.sync ?: return
    val status by sync.status.collectAsState()
    val context = LocalContext.current
    val close = { ctx.show(null) }
    val title = stringResource(R.string.sync_settings)
    val cancel = stringResource(R.string.cancel)

    if (!status.signedIn) {
        val servers by remember { ServerFinder.find(context) }.collectAsState(initial = emptyList())
        val addressTitle = stringResource(R.string.sync_address_title)
        val intro = stringResource(R.string.sync_intro)
        ChoiceDialog(
            title = title,
            message = if (servers.isEmpty()) intro + "\n\n" + stringResource(R.string.sync_searching) else intro,
            choices = servers.map { server ->
                Choice(context.getString(R.string.sync_use_server, server.name)) { ctx.show(Overlay.SyncPair(server.url, server.name)) }
            } + listOf(
                Choice(stringResource(R.string.sync_enter_address)) {
                    ctx.show(Overlay.TextInput(addressTitle, "") { address -> ctx.show(Overlay.SyncPair(address, null)) })
                },
                Choice(cancel) { close() },
            ),
            onDismiss = close,
        )
        return
    }

    val server = status.serverName ?: status.serverUrl.orEmpty()
    val message = when {
        status.revoked -> stringResource(R.string.sync_revoked, server)
        status.offline -> stringResource(R.string.sync_offline, server)
        status.lastSynced != null -> stringResource(
            R.string.sync_synced,
            server,
            DateUtils.getRelativeTimeSpanString(status.lastSynced!!.toEpochMilli(), System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS),
        )
        else -> stringResource(R.string.sync_on, server)
    }
    val stopTitle = stringResource(R.string.sync_stop_confirm, server)
    val stop = stringResource(R.string.sync_stop)
    ChoiceDialog(
        title = title,
        message = message,
        choices = listOfNotNull(
            if (status.revoked) {
                Choice(stringResource(R.string.sync_pair_again)) { ctx.show(Overlay.SyncPair(status.serverUrl.orEmpty(), status.serverName)) }
            } else {
                Choice(stringResource(R.string.sync_now)) { sync.requestSync(); close() }
            },
            Choice(stop) { ctx.show(Overlay.Confirm(stopTitle, stop) { sync.signOut() }) },
            Choice(stringResource(R.string.sync_close)) { close() },
        ),
        onDismiss = close,
    )
}

/** Pairs with the server at [o]'s address: shows a code for the admin to approve in the web app. */
@Composable
fun SyncPairDialog(o: Overlay.SyncPair, ctx: OverlayContext) {
    val sync = ctx.deps.sync ?: return
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf<PairStep?>(null) }
    LaunchedEffect(attempt) {
        step = null
        sync.pair(o.address, o.serverName).collect { step = it }
    }
    val close = { ctx.show(null) }
    val title = stringResource(R.string.sync_settings)
    val cancel = Choice(stringResource(R.string.cancel)) { close() }
    val again = Choice(stringResource(R.string.sync_try_again)) { attempt++ }

    when (val s = step) {
        null -> ChoiceDialog(title = title, message = stringResource(R.string.sync_connecting, o.address), choices = listOf(cancel), onDismiss = close)
        is PairStep.Showing -> PairCodeDialog(s.code, s.serverUrl, close)
        PairStep.Approved -> ChoiceDialog(
            title = title,
            message = stringResource(R.string.sync_paired, o.serverName ?: o.address),
            choices = listOf(Choice(stringResource(R.string.ok)) { close() }),
            onDismiss = close,
        )
        PairStep.Denied -> ChoiceDialog(title = title, message = stringResource(R.string.sync_denied), choices = listOf(again, cancel), onDismiss = close)
        PairStep.Expired -> ChoiceDialog(title = title, message = stringResource(R.string.sync_expired), choices = listOf(again, cancel), onDismiss = close)
        is PairStep.Failed -> ChoiceDialog(
            title = title,
            message = context.getString(R.string.sync_failed, o.address, s.error.message ?: s.error.toString()),
            choices = listOf(again, cancel),
            onDismiss = close,
        )
    }
}

@Composable
private fun PairCodeDialog(code: String, serverUrl: String, onCancel: () -> Unit) {
    val button = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(420.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.sync_code_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.sync_code_help, serverUrl), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().focusRequester(button)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
    LaunchedEffect(code) { button.requestFocus() }
}
