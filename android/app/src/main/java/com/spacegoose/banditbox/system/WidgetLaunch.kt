package com.spacegoose.banditbox.system

import android.content.Intent
import android.content.pm.PackageManager
import com.spacegoose.banditbox.layout.WidgetCard
import com.spacegoose.banditbox.layout.WidgetFeed
import java.net.URISyntaxException

/** Turns a widget card's link into intents to start, best first. */
object WidgetLaunch {
    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /** The card's link (kept to its app when it names one), then the app's launch intent. */
    fun intents(card: WidgetCard, feed: WidgetFeed, pm: PackageManager): List<Intent> =
        intents(card.link, card.packageName ?: feed.packageName, pm)

    /** [link] (an intent URI or any URI an app handles), then [packageName]'s launch intent. */
    fun intents(link: String?, packageName: String?, pm: PackageManager): List<Intent> {
        val out = mutableListOf<Intent>()
        val parsed = link?.let { raw ->
            try {
                Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).apply {
                    // Never pass on URI grants or a selector from a feed.
                    selector = null
                    flags = flags and GRANT_FLAGS.inv()
                }
            } catch (_: URISyntaxException) {
                null
            }
        }
        if (parsed != null) {
            if (packageName != null && parsed.component == null && parsed.`package` == null) {
                // Open it in the feed's own app when that app can, else in whatever handles it.
                out += Intent(parsed).setPackage(packageName)
            }
            out += parsed
        }
        if (packageName != null) {
            (pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName))?.let(out::add)
        }
        return out
    }
}
