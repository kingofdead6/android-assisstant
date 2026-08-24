package com.john.assistant.integrations.github

import com.john.assistant.core.util.AssistantLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubRepository(
    val fullName: String,
    val description: String?,
    val language: String?,
    val stars: Int,
    val isPrivate: Boolean,
    val htmlUrl: String,
)

data class GitHubNotification(
    val title: String,
    val repository: String,
    val type: String,
    val reason: String,
    val updatedAt: String,
)

/** A GitHub call either worked, or it did not — and John says which. */
sealed interface GitHubResult<out T> {
    data class Success<T>(val value: T) : GitHubResult<T>
    data class Failure(val reason: String) : GitHubResult<Nothing>
    data object NotConnected : GitHubResult<Nothing>
}

/**
 * A small, read-only GitHub client.
 *
 * Two design choices worth naming:
 *
 *  - **Read-only.** The requested scopes cannot open issues, push, or comment.
 *    A voice assistant acting on a misheard command in someone's repository is
 *    a problem John should not be able to have.
 *  - **Hand-rolled over `HttpURLConnection`.** Three endpoints do not justify
 *    adding a networking library and its transitive dependencies to an app
 *    whose whole premise is running locally.
 *
 * Responses are parsed field by field rather than deserialised into generated
 * classes: GitHub's payloads are large, John needs six fields, and pulling only
 * those keeps the parsing resilient to the rest of the schema changing.
 */
@Singleton
class GitHubClient @Inject constructor(
    private val auth: GitHubAuth,
    private val logger: AssistantLogger,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun repositories(limit: Int = 10): GitHubResult<List<GitHubRepository>> =
        get("$API_BASE/user/repos?sort=updated&per_page=$limit") { array ->
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                GitHubRepository(
                    fullName = obj.string("full_name") ?: return@mapNotNull null,
                    description = obj.string("description"),
                    language = obj.string("language"),
                    stars = obj.string("stargazers_count")?.toIntOrNull() ?: 0,
                    isPrivate = obj.string("private") == "true",
                    htmlUrl = obj.string("html_url").orEmpty(),
                )
            }
        }

    suspend fun notifications(limit: Int = 10): GitHubResult<List<GitHubNotification>> =
        get("$API_BASE/notifications?per_page=$limit") { array ->
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val subject = obj["subject"]?.jsonObject

                GitHubNotification(
                    title = subject?.string("title") ?: return@mapNotNull null,
                    repository = obj["repository"]?.jsonObject?.string("full_name").orEmpty(),
                    type = subject.string("type").orEmpty(),
                    reason = obj.string("reason").orEmpty(),
                    updatedAt = obj.string("updated_at").orEmpty(),
                )
            }
        }

    private suspend fun <T> get(
        url: String,
        parse: (JsonArray) -> T,
    ): GitHubResult<T> = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext GitHubResult.NotConnected

        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit

                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    // A revoked token is worth clearing: leaving it means every
                    // future call fails the same way with no way to recover.
                    auth.disconnect()
                    return@withContext GitHubResult.Failure(
                        "Your GitHub sign-in has expired. Connect it again in settings.",
                    )
                }

                HttpURLConnection.HTTP_FORBIDDEN ->
                    return@withContext GitHubResult.Failure("GitHub is rate-limiting me.")

                else -> return@withContext GitHubResult.Failure("GitHub returned an error ($code).")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = json.parseToJsonElement(body) as? JsonArray
                ?: return@withContext GitHubResult.Failure("Unexpected response from GitHub.")

            GitHubResult.Success(parse(array))
        }.getOrElse { error ->
            logger.warn(TAG, "GitHub request failed", error)
            GitHubResult.Failure("I couldn't reach GitHub.")
        }
    }

    /** Reads scalars as text so ints, bools and strings share one accessor. */
    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private companion object {
        const val TAG = "GitHubClient"
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        const val TIMEOUT_MILLIS = 20_000
    }
}
