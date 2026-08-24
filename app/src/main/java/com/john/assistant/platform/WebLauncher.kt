package com.john.assistant.platform

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The web, maps and the camera — all of it through Intents.
 *
 * Nothing here scrapes a search results page or drives a browser's UI. Android
 * already has a first-class mechanism for "show the user this", and using it
 * means John works with whatever browser, maps app and camera the user actually
 * prefers instead of the ones this code happened to be written against.
 */
@Singleton
class WebLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Run a web search.
     *
     * `ACTION_WEB_SEARCH` is the correct intent and routes to the user's chosen
     * search app. Some devices ship without a handler for it, so a plain
     * https search URL is the fallback — every device can open one of those.
     */
    fun search(query: String): Boolean {
        val webSearch = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (webSearch.resolveActivity(context.packageManager) != null &&
            runCatching { context.startActivity(webSearch) }.isSuccess
        ) {
            return true
        }

        return openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
    }

    /** Open a URL, adding https:// when the user said a bare domain. */
    fun openUrl(rawUrl: String): Boolean {
        val url = normaliseUrl(rawUrl) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Search within a specific site, e.g. YouTube after "open YouTube". */
    fun searchOnSite(siteUrlTemplate: String, query: String): Boolean =
        openUrl(siteUrlTemplate.replace(QUERY_PLACEHOLDER, Uri.encode(query)))

    fun openMaps(query: String): Boolean {
        // geo: is the documented maps scheme and is honoured by every maps app.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) != null &&
            runCatching { context.startActivity(intent) }.isSuccess
        ) {
            return true
        }

        return openUrl("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")
    }

    /**
     * Open the camera app.
     *
     * `INTENT_ACTION_STILL_IMAGE_CAMERA` opens the camera for the user; it needs
     * no CAMERA permission because John is not the one taking the picture. An
     * assistant that captured silently would be a very different application.
     */
    fun openCamera(): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun normaliseUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null

        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }

        // A malformed host would otherwise become an ACTION_VIEW with no handler,
        // which surfaces as a confusing "no app can do this" rather than a typo.
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return if (parsed.host.isNullOrBlank()) null else url
    }

    companion object {
        const val QUERY_PLACEHOLDER = "{query}"

        /**
         * Sites whose in-app search John can deep-link into, used to make
         * "search for AI tutorials" work after "open YouTube".
         */
        val SITE_SEARCH_TEMPLATES = mapOf(
            "com.google.android.youtube" to "https://www.youtube.com/results?search_query=$QUERY_PLACEHOLDER",
            "com.spotify.music" to "https://open.spotify.com/search/$QUERY_PLACEHOLDER",
            "com.github.android" to "https://github.com/search?q=$QUERY_PLACEHOLDER",
        )
    }
}
