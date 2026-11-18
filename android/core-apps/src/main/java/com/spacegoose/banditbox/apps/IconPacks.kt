package com.spacegoose.banditbox.apps

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** An installed icon pack app. */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * Reads icon pack apps in the ADW / Nova format that most packs on the Play Store use: an
 * appfilter.xml that maps each app's activity to a drawable in the pack. Only one pack is held
 * in memory at a time, and only its entries for apps installed on this TV.
 */
class IconPacks(context: Context) {
    private val pm = context.applicationContext.packageManager

    private var loadedPack: String? = null
    private var byComponent: Map<String, String> = emptyMap()
    private var byPackage: Map<String, String> = emptyMap()
    private var resources: Resources? = null

    /** Icon pack apps installed on the TV, by label. */
    fun installed(): List<IconPackInfo> {
        val found = LinkedHashMap<String, IconPackInfo>()
        for (action in PACK_ACTIONS) {
            @Suppress("DEPRECATION") // The ResolveInfoFlags overload is API 33+.
            val matches = pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            for (info in matches) {
                val pkg = info.activityInfo.packageName
                if (pkg !in found) found[pkg] = IconPackInfo(pkg, info.loadLabel(pm).toString())
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** The pack's icon for [component], or null when the pack has none (or is gone). Does IO. */
    @Synchronized
    @SuppressLint("DiscouragedApi") // Icon packs are looked up by drawable name; there is no other way.
    fun icon(packPackage: String, component: ComponentName): Drawable? {
        if (loadedPack != packPackage) load(packPackage)
        val res = resources ?: return null
        val name = byComponent[component.packageName + "/" + component.className] ?: byPackage[component.packageName] ?: return null
        val id = res.getIdentifier(name, "drawable", packPackage)
        if (id == 0) return null
        return try {
            res.getDrawable(id, null)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun load(packPackage: String) {
        loadedPack = packPackage
        byComponent = emptyMap()
        byPackage = emptyMap()
        resources = null
        val res = try {
            pm.getResourcesForApplication(packPackage)
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        val wanted = launchablePackages()
        val components = HashMap<String, String>()
        val packages = HashMap<String, String>()
        try {
            val xmlId = res.getIdentifier("appfilter", "xml", packPackage)
            val parser: XmlPullParser = if (xmlId != 0) {
                res.getXml(xmlId)
            } else {
                XmlPullParserFactory.newInstance().newPullParser().apply { setInput(res.assets.open("appfilter.xml"), null) }
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    val parsed = component?.let(::parseComponent)
                    if (parsed != null && !drawable.isNullOrEmpty() && parsed.first in wanted) {
                        components.putIfAbsent(parsed.first + "/" + parsed.second, drawable)
                        packages.putIfAbsent(parsed.first, drawable)
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // A broken pack must never take the launcher down: draw system icons instead.
            Log.w("BanditBox", "Couldn't read icon pack $packPackage", e)
        }
        byComponent = components
        byPackage = packages
        resources = res
    }

    private fun launchablePackages(): Set<String> {
        val set = HashSet<String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).forEach { set += it.activityInfo.packageName }
        }
        return set
    }

    companion object {
        private val PACK_ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme")

        /** "ComponentInfo{pkg/activity}" to (pkg, full activity class), or null. */
        fun parseComponent(raw: String): Pair<String, String>? {
            val inner = raw.substringAfter("ComponentInfo{", "").substringBefore("}")
            val pkg = inner.substringBefore("/", "")
            val activity = inner.substringAfter("/", "")
            if (pkg.isEmpty() || activity.isEmpty()) return null
            return pkg to if (activity.startsWith(".")) pkg + activity else activity
        }
    }
}
