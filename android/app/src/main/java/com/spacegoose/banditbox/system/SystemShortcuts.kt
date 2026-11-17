package com.spacegoose.banditbox.system

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Intents for the settings shortcuts. Each is a list: the first one the device handles wins. */
object SystemShortcuts {
    val androidSettings = listOf(Intent(Settings.ACTION_SETTINGS))

    val network = listOf(
        Intent(Settings.ACTION_WIFI_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** "Install unknown apps", where sideloading apps is allowed per installer. */
    val installUnknownApps = listOf(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}

/** An HDMI or other passthrough input on a TV. */
data class TvInput(val id: String, val label: String) {
    fun intent(): Intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
}

/** Passthrough inputs (HDMI, AV). Empty on streaming boxes like the onn 4K and Shield. */
fun passthroughInputs(context: Context): List<TvInput> {
    val manager = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
    return manager.tvInputList
        .filter { it.isPassthroughInput && it.type != TvInputInfo.TYPE_TUNER }
        .map { TvInput(it.id, it.loadLabel(context).toString()) }
}

enum class NetworkKind { ETHERNET, WIFI, OTHER, OFFLINE }

/** The default network's type, updated as it changes. */
fun networkKind(context: Context): Flow<NetworkKind> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    if (cm == null) {
        trySend(NetworkKind.OTHER)
        awaitClose {}
        return@callbackFlow
    }
    fun kindOf(caps: NetworkCapabilities?): NetworkKind = when {
        caps == null -> NetworkKind.OFFLINE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
        else -> NetworkKind.OTHER
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(kindOf(caps))
        }

        override fun onLost(network: Network) {
            trySend(NetworkKind.OFFLINE)
        }
    }
    trySend(kindOf(cm.getNetworkCapabilities(cm.activeNetwork)))
    cm.registerDefaultNetworkCallback(callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
