package com.spacegoose.banditbox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.spacegoose.banditbox.HomeActivity
import com.spacegoose.banditbox.R
import com.spacegoose.banditbox.guard.GuardStatus
import kotlinx.coroutines.delay

private class HomeStatus(val isDefaultHome: Boolean, val serviceOn: Boolean, val stockLauncher: String?)

private fun readStatus(context: Context) = HomeStatus(
    isDefaultHome = GuardStatus.isDefaultHome(context),
    serviceOn = GuardStatus.isServiceEnabled(context),
    stockLauncher = GuardStatus.installedStockLauncher(context),
)

/**
 * Settings → Home button. Shows whether Home reaches BanditBox, walks through turning on the
 * Home button service (needed on Google TV), and lists ADB commands for power users.
 */
@Composable
fun HomeSetupScreen(launch: (List<Intent>) -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readStatus(context)) }
    // Re-check while the screen is open, so it updates after a trip to Accessibility settings.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            status = readStatus(context)
        }
    }

    val ok = status.isDefaultHome || status.serviceOn
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.home_setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(if (ok) R.string.home_setup_ok else R.string.home_setup_not_ok),
            style = MaterialTheme.typography.titleMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(if (status.isDefaultHome) R.string.home_default_yes else R.string.home_default_no),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(if (status.serviceOn) R.string.home_service_on else R.string.home_service_off),
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(stringResource(R.string.home_setup_steps), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.home_setup_restricted), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { launch(listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))) }) {
                Text(stringResource(R.string.open_accessibility_settings))
            }
            Button(onClick = { launch(listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))) }) {
                Text(stringResource(R.string.open_home_settings))
            }
            Button(
                onClick = {
                    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    launch(listOf(appInfo, Intent(Settings.ACTION_SETTINGS)))
                },
            ) {
                Text(stringResource(R.string.open_app_info))
            }
        }

        Text(stringResource(R.string.adb_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(R.string.adb_intro), style = MaterialTheme.typography.bodyMedium)
        adbCommands(context, status.stockLauncher).forEach { (label, command) ->
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

/** Commands use this build's real package name, so they work for debug and release builds alike. */
@Composable
private fun adbCommands(context: Context, stockLauncher: String?): List<Pair<String, String>> {
    val pkg = context.packageName
    val home = "$pkg/${HomeActivity::class.java.name}"
    val service = GuardStatus.serviceComponent(context).flattenToString()
    return buildList {
        add(stringResource(R.string.adb_set_home) to "adb shell cmd package set-home-activity $home")
        add(stringResource(R.string.adb_allow_restricted) to "adb shell appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        add(stringResource(R.string.adb_enable_service) to "adb shell settings put secure enabled_accessibility_services $service")
        add(stringResource(R.string.adb_enable_accessibility) to "adb shell settings put secure accessibility_enabled 1")
        if (stockLauncher != null) {
            add(stringResource(R.string.adb_disable_stock) to "adb shell pm disable-user --user 0 $stockLauncher")
            add(stringResource(R.string.adb_enable_stock) to "adb shell pm enable $stockLauncher")
        }
    }
}
