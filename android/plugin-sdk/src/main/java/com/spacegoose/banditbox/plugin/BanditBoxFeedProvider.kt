package com.spacegoose.banditbox.plugin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Publishes your app's feeds to BanditBox. Subclass it, return your feeds from [feeds], and
 * declare it in your manifest:
 *
 * ```xml
 * <provider
 *     android:name=".MyBanditBoxFeeds"
 *     android:authorities="${applicationId}.banditbox"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.spacegoose.banditbox.action.WIDGET_FEEDS" />
 *     </intent-filter>
 * </provider>
 * ```
 *
 * BanditBox asks for the feeds while its home screen is in front, and again whenever you call
 * [notifyChanged]. Keep [feeds] quick: it runs on a binder thread, so read from memory or a
 * small cache, not the network.
 */
abstract class BanditBoxFeedProvider : ContentProvider() {
    /** The feeds to show now. */
    abstract fun feeds(): List<Feed>

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_FEEDS) return super.call(method, arg, extras)
        val json = JSONObject().put("feeds", JSONArray(feeds().map { it.toJson() })).toString()
        return Bundle().apply {
            putInt(KEY_VERSION, PROTOCOL_VERSION)
            putString(KEY_FEEDS, json)
        }
    }

    // Feeds go through call(); these are unused unless a subclass serves images through
    // openFile, which it may override.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** The intent filter action BanditBox finds feed providers by. */
        const val ACTION = "com.spacegoose.banditbox.action.WIDGET_FEEDS"
        const val METHOD_GET_FEEDS = "getFeeds"
        const val KEY_FEEDS = "feeds"
        const val KEY_VERSION = "version"
        const val PROTOCOL_VERSION = 1

        /** The authority BanditBox expects: your package name plus ".banditbox". */
        fun authority(context: Context): String = context.packageName + ".banditbox"

        /** Tells BanditBox your feeds changed, so it asks again. Cheap: call it whenever they do. */
        fun notifyChanged(context: Context) {
            context.contentResolver.notifyChange(Uri.parse("content://" + authority(context) + "/feeds"), null)
        }
    }
}
