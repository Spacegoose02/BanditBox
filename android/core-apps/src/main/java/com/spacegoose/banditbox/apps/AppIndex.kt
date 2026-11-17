package com.spacegoose.banditbox.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Lists the apps the launcher can open and tells you when that list changes. */
class AppIndex(context: Context) {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    /** Queries PackageManager. Does disk IO: call off the main thread. */
    fun load(): List<AppEntry> = mergeEntries(
        leanback = query(Intent.CATEGORY_LEANBACK_LAUNCHER),
        launcher = query(Intent.CATEGORY_LAUNCHER),
        selfPackage = appContext.packageName,
    )

    /** The current list, then a fresh one after every install, update or uninstall. */
    fun apps(): Flow<List<AppEntry>> = packageChanges()
        .onStart { emit(Unit) }
        .conflate()
        .map { load() }
        .flowOn(Dispatchers.IO)

    private fun query(category: String): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        @Suppress("DEPRECATION") // The ResolveInfoFlags overload is API 33+.
        val results = pm.queryIntentActivities(intent, 0)
        return results.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (!activity.enabled || !activity.exported) return@mapNotNull null
            AppEntry(
                packageName = activity.packageName,
                activityName = activity.name,
                label = info.loadLabel(pm).toString(),
                isLeanback = category == Intent.CATEGORY_LEANBACK_LAUNCHER,
                isSystem = activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
    }

    private fun packageChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Package broadcasts come from the system, which still reaches a not-exported receiver.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { appContext.unregisterReceiver(receiver) }
    }
}
