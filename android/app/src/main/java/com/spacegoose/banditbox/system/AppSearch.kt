package com.spacegoose.banditbox.system

import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent

/** Hands a search to other apps: one app's own search, Google TV's search, or voice typing. */
object AppSearch {
    private const val GLOBAL_SEARCH = "android.search.action.GLOBAL_SEARCH"

    /** Packages with a search screen that takes a typed query. */
    fun searchablePackages(pm: PackageManager): Set<String> {
        @Suppress("DEPRECATION") // The ResolveInfoFlags overload is API 33+.
        val found = pm.queryIntentActivities(Intent(Intent.ACTION_SEARCH), 0)
        return found.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    fun inApp(packageName: String, query: String): Intent =
        Intent(Intent.ACTION_SEARCH).setPackage(packageName).putExtra(SearchManager.QUERY, query)

    /** The TV's own search across apps (Google TV, Android TV), when there is one. */
    fun global(query: String): Intent = Intent(GLOBAL_SEARCH).putExtra(SearchManager.QUERY, query)

    fun hasGlobal(pm: PackageManager): Boolean = pm.resolveActivity(Intent(GLOBAL_SEARCH), 0) != null

    fun voice(prompt: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    fun hasVoice(pm: PackageManager): Boolean = pm.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0) != null
}
