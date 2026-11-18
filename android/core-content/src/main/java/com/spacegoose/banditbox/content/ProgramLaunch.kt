package com.spacegoose.banditbox.content

import android.content.Intent
import android.content.pm.PackageManager
import java.net.URISyntaxException

/** Turns a published program into intents to start, best first. */
object ProgramLaunch {
    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /**
     * The program's own intent, kept to the app that published it, then the app's launch
     * intent as a fallback when the deep link doesn't resolve.
     */
    fun intents(program: TvProgram, pm: PackageManager): List<Intent> {
        val deepLink = program.intentUri?.let { raw ->
            try {
                Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).apply {
                    // Never pass on URI grants or a selector from another app's data.
                    selector = null
                    flags = flags and GRANT_FLAGS.inv()
                    if (component == null && `package` == null) setPackage(program.packageName)
                }
            } catch (_: URISyntaxException) {
                null
            }
        }
        val app = pm.getLeanbackLaunchIntentForPackage(program.packageName)
            ?: pm.getLaunchIntentForPackage(program.packageName)
        return listOfNotNull(deepLink, app)
    }
}
