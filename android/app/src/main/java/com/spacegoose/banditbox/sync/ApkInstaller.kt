package com.spacegoose.banditbox.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Installs APKs the server sent this TV, with Android's PackageInstaller. A BanditBox APK
 * updates the launcher itself. Android may ask the user to confirm each install on screen;
 * [InstallReceiver] shows that prompt and reports the outcome.
 */
class ApkInstaller(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, "apks")
    private val prefs = app.getSharedPreferences("installs", Context.MODE_PRIVATE)

    /** Works through the server's list. Blocking: call off the main thread. */
    fun run(api: HttpSyncApi) {
        val pending = try {
            api.installs()
        } catch (e: IOException) {
            Log.i(TAG, "Couldn't fetch installs: ${e.message}")
            return
        } catch (e: ServerException) {
            return // an older server without installs
        } catch (_: RevokedException) {
            return // the next sync shows "not synced"
        }
        for (apk in pending) {
            try {
                handle(api, apk)
            } catch (e: IOException) {
                Log.w(TAG, "Install of ${apk.packageName} stopped", e)
                report(api, apk.sha256, FAILED, e.message ?: "download failed")
            } catch (e: ServerException) {
                Log.w(TAG, "Server refused for ${apk.packageName}: ${e.message}")
            } catch (_: RevokedException) {
                return
            } catch (e: RuntimeException) {
                // PackageInstaller refused the session (no space, bad file, not allowed).
                Log.w(TAG, "Install of ${apk.packageName} failed", e)
                report(api, apk.sha256, FAILED, e.message ?: e.toString())
            }
        }
        cleanUp(pending.map { it.sha256 }.toSet())
    }

    private fun handle(api: HttpSyncApi, apk: SyncApi.PendingApk) {
        val installed = installedVersion(apk.packageName)
        if (installed != null && installed >= apk.versionCode) {
            // Done already: by us (the update restarted the app before it could report) or by hand.
            val ours = prefs.contains(apk.sha256)
            prefs.edit().remove(apk.sha256).apply()
            report(api, apk.sha256, if (ours) INSTALLED else SKIPPED, if (ours) "" else "version $installed is installed")
            return
        }
        // Don't ask again and again while someone ignores the prompt; the admin can send it again.
        val started = prefs.getLong(apk.sha256, 0)
        if (System.currentTimeMillis() - started < RETRY_MS) return

        if (!app.packageManager.canRequestPackageInstalls()) {
            report(api, apk.sha256, FAILED, "Allow BanditBox to install unknown apps in the TV's settings, then send it again")
            return
        }
        dir.mkdirs()
        val file = File(dir, "${apk.sha256}.apk")
        if (!file.isFile || sha256(file) != apk.sha256) {
            val part = File(dir, "${apk.sha256}.part")
            api.downloadApk(apk.sha256, part)
            if (sha256(part) != apk.sha256) {
                part.delete()
                throw IOException("the download was corrupted")
            }
            if (!part.renameTo(file)) throw IOException("couldn't save the download")
        }
        prefs.edit().putLong(apk.sha256, System.currentTimeMillis()).apply()
        install(file, apk)
    }

    private fun install(file: File, apk: SyncApi.PendingApk) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(apk.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Updates to apps BanditBox installed can go through without a prompt.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, InstallReceiver::class.java)
                .setAction(InstallReceiver.ACTION)
                .putExtra(InstallReceiver.EXTRA_SHA, apk.sha256)
            // Mutable: PackageInstaller adds the result to it.
            val pi = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "Installing ${apk.packageName} ${apk.versionName}")
    }

    /** Called with the result of an install this class started. */
    fun finished(sha256: String, success: Boolean) {
        if (success) prefs.edit().remove(sha256).apply()
        File(dir, "$sha256.apk").delete()
    }

    private fun cleanUp(keep: Set<String>) {
        dir.listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in keep) f.delete() }
        prefs.all.keys.filter { it !in keep }.forEach { prefs.edit().remove(it).apply() }
    }

    private fun installedVersion(pkg: String): Long? = try {
        app.packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun report(api: HttpSyncApi, sha: String, status: String, detail: String) {
        try {
            api.reportInstall(sha, status, detail)
        } catch (e: IOException) {
            Log.i(TAG, "Couldn't report install: ${e.message}")
        } catch (e: ServerException) {
            Log.i(TAG, "Server refused install report: ${e.message}")
        } catch (_: RevokedException) {
        }
    }

    companion object {
        private const val TAG = "BanditBox"
        private const val RETRY_MS = 30 * 60_000L
        const val WAITING = "waiting"
        const val INSTALLED = "installed"
        const val SKIPPED = "skipped"
        const val FAILED = "failed"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
