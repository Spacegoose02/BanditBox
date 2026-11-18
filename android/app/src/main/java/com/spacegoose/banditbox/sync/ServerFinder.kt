package com.spacegoose.banditbox.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.ArrayDeque

/** A BanditBox server announced on the network. */
data class FoundServer(val name: String, val url: String)

/**
 * Looks for BanditBox servers on the local network (mDNS, _banditbox._tcp) while collected.
 * Each emission is everything found so far.
 */
object ServerFinder {
    private const val TYPE = "_banditbox._tcp"

    fun find(context: Context): Flow<List<FoundServer>> = callbackFlow {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = linkedMapOf<String, FoundServer>()
        // NsdManager resolves one service at a time, so the rest wait their turn.
        val waiting = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun next() {
            if (resolving) return
            val info = waiting.pollFirst() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    synchronized(found) { resolving = false; next() }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host
                    synchronized(found) {
                        if (host != null) {
                            val address = if (host is Inet4Address) host.hostAddress else "[${host.hostAddress}]"
                            found[info.serviceName] = FoundServer(info.serviceName, "http://$address:${info.port}")
                            trySend(found.values.toList())
                        }
                        resolving = false
                        next()
                    }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.w("BanditBox", "Server search failed to start: $error")
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(found) { waiting.addLast(info); next() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(info.serviceName) != null) trySend(found.values.toList())
                }
            }
        }
        trySend(emptyList())
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
