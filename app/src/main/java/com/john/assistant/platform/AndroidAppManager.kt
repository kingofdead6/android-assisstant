package com.john.assistant.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** An installed app John can launch. */
data class InstalledApp(
    val label: String,
    val packageName: String,
) {
    /** Lowercase, punctuation-free form used for matching. */
    val normalisedLabel: String = normalise(label)

    companion object {
        fun normalise(text: String): String = text
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** What resolving a spoken app name produced. */
sealed interface AppMatch {
    data class Exact(val app: InstalledApp) : AppMatch

    /** Several apps fit; John asks which one rather than picking. */
    data class Ambiguous(val candidates: List<InstalledApp>) : AppMatch

    data object None : AppMatch
}

/**
 * Finds and launches installed applications.
 *
 * Nothing here hardcodes a package name. "Open YouTube" works by looking at
 * what is actually installed and matching the spoken label against it, which
 * means it also works for apps this code has never heard of, for localised app
 * names, and for the user's regional alternatives.
 *
 * Two Android constraints shape this class:
 *
 *  - Since Android 11 an app cannot see the installed-app list unless it
 *    declares a `<queries>` block. John declares the launcher-intent query,
 *    which is the narrowest declaration that makes "open X" possible, and
 *    deliberately avoids the Play-restricted QUERY_ALL_PACKAGES.
 *  - Launching requires the target to export a launcher activity. Apps without
 *    one (many system components) cannot be opened and are reported as such
 *    rather than failing silently.
 */
@Singleton
class AndroidAppManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val packageManager: PackageManager get() = context.packageManager

    @Volatile
    private var cache: List<InstalledApp>? = null

    /** Every app with a launcher entry, sorted by label. */
    suspend fun installedApps(refresh: Boolean = false): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            cache?.takeUnless { refresh } ?: queryLaunchableApps().also { cache = it }
        }

    /** Called when packages change so "open X" does not use a stale list. */
    fun invalidateCache() {
        cache = null
    }

    /**
     * Resolve a spoken app name.
     *
     * Match strength descends: exact label, then a label that starts with the
     * spoken words, then a containment match, then per-word overlap. The first
     * tier that produces results wins, so "YouTube" prefers YouTube over
     * YouTube Music, while "music" still finds the latter.
     */
    suspend fun resolve(spokenName: String): AppMatch {
        val query = InstalledApp.normalise(spokenName)
        if (query.isEmpty()) return AppMatch.None

        val apps = installedApps()

        val tiers = listOf<(InstalledApp) -> Boolean>(
            { it.normalisedLabel == query },
            { it.normalisedLabel.startsWith("$query ") || it.normalisedLabel.startsWith(query) },
            { it.normalisedLabel.contains(query) },
            { wordOverlap(it.normalisedLabel, query) },
            // Last resort: the package id itself, so "open com.spotify.music" works.
            { it.packageName.lowercase(Locale.ROOT).contains(query) },
        )

        for (tier in tiers) {
            val matches = apps.filter(tier)
            when {
                matches.isEmpty() -> continue
                matches.size == 1 -> return AppMatch.Exact(matches.single())
                else -> return AppMatch.Ambiguous(matches.take(MAX_CANDIDATES))
            }
        }

        return AppMatch.None
    }

    /**
     * Launch an app.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is required because John usually starts things
     * from a service rather than from a visible Activity.
     */
    fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun isInstalled(packageName: String): Boolean =
        packageManager.getLaunchIntentForPackage(packageName) != null

    /** Label for a package, or the package id when it cannot be read. */
    suspend fun labelFor(packageName: String): String =
        installedApps().firstOrNull { it.packageName == packageName }?.label ?: packageName

    private fun queryLaunchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(packageManager).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                InstalledApp(label, packageName)
            }
            // A package can expose several launcher activities; one entry each.
            .distinctBy { it.packageName }
            .sortedBy { it.normalisedLabel }
            .toList()
    }

    /** True when every spoken word appears as a word of the label. */
    private fun wordOverlap(label: String, query: String): Boolean {
        val labelWords = label.split(' ').filter { it.isNotEmpty() }
        val queryWords = query.split(' ').filter { it.isNotEmpty() }
        if (queryWords.isEmpty()) return false
        return queryWords.all { word -> labelWords.any { it.startsWith(word) } }
    }

    private companion object {
        const val MAX_CANDIDATES = 4
    }
}
