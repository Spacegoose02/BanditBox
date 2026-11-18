package com.spacegoose.banditbox.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.spacegoose.banditbox.BanditBoxApplication

/** Gets each install's outcome from PackageInstaller: shows the confirm prompt, or reports the result. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sha = intent.getStringExtra(EXTRA_SHA) ?: return
        val sync = (context.applicationContext as BanditBoxApplication).sync
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: RuntimeException) {
                        Log.w("BanditBox", "Couldn't show the install prompt", e)
                    }
                }
                sync.reportInstall(sha, ApkInstaller.WAITING, "")
            }
            PackageInstaller.STATUS_SUCCESS -> sync.installFinished(sha, true, "")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                sync.installFinished(sha, false, message)
            }
        }
    }

    companion object {
        const val ACTION = "com.spacegoose.banditbox.INSTALL_RESULT"
        const val EXTRA_SHA = "sha256"
    }
}
