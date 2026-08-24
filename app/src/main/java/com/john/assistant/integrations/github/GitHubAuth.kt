package com.john.assistant.integrations.github

import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.integrations.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** A pending device-flow authorisation the user has to complete in a browser. */
data class DeviceCodeGrant(
    val deviceCode: String,
    /** The short code the user types, e.g. `WDJB-MJHT`. */
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failed(val reason: String) : AuthResult
    data object Cancelled : AuthResult
}

/**
 * GitHub sign-in, via the OAuth **device flow**.
 *
 * Chosen deliberately over the authorisation-code flow, for one reason that
 * matters a great deal in a mobile app: the device flow needs **no client
 * secret**. A secret shipped in an APK is not a secret — it is extractable by
 * anyone who downloads the app — so any flow requiring one either leaks it or
 * requires running a backend, and John is a local-first assistant with no
 * server to run.
 *
 * The trade is a slightly clunkier sign-in: John shows a short code, the user
 * types it at github.com/login/device, and John polls until it is approved.
 * That is a fair price for never holding a credential it cannot protect.
 *
 * The client ID is *not* hardcoded. It is a public identifier, but it is also
 * per-installation: whoever builds John registers their own OAuth app and
 * enters its ID in settings. Shipping one would tie every user of every build
 * to a single OAuth app.
 */
@Singleton
class GitHubAuth @Inject constructor(
    private val tokenStore: SecureTokenStore,
    private val logger: AssistantLogger,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val isConnected: Boolean get() = accessToken() != null

    fun accessToken(): String? = tokenStore.get(TOKEN_KEY)

    fun clientId(): String? = tokenStore.get(CLIENT_ID_KEY)

    fun setClientId(clientId: String) = tokenStore.put(CLIENT_ID_KEY, clientId.trim())

    fun disconnect() {
        tokenStore.remove(TOKEN_KEY)
    }

    /** Step one: ask GitHub for a code the user can type. */
    suspend fun requestDeviceCode(): Result<DeviceCodeGrant> = withContext(Dispatchers.IO) {
        val clientId = clientId()
            ?: return@withContext Result.failure(IllegalStateException("No GitHub client ID set."))

        runCatching {
            val response = post(
                url = DEVICE_CODE_URL,
                body = "client_id=$clientId&scope=${SCOPES.replace(" ", "%20")}",
            )

            DeviceCodeGrant(
                deviceCode = response.string("device_code") ?: error("no device_code"),
                userCode = response.string("user_code") ?: error("no user_code"),
                verificationUri = response.string("verification_uri") ?: VERIFICATION_FALLBACK,
                intervalSeconds = response.int("interval") ?: DEFAULT_INTERVAL_SECONDS,
                expiresInSeconds = response.int("expires_in") ?: DEFAULT_EXPIRY_SECONDS,
            )
        }
    }

    /**
     * Step two: poll until the user approves, refuses, or the code expires.
     *
     * GitHub's documented pacing rules are honoured rather than approximated:
     * `authorization_pending` means keep waiting, `slow_down` means add five
     * seconds to the interval, and ignoring either gets the client rate-limited
     * out of the flow entirely.
     */
    suspend fun pollForToken(grant: DeviceCodeGrant): AuthResult = withContext(Dispatchers.IO) {
        val clientId = clientId() ?: return@withContext AuthResult.Failed("No GitHub client ID set.")

        var intervalSeconds = grant.intervalSeconds
        val deadline = System.currentTimeMillis() + grant.expiresInSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(intervalSeconds * 1000L)

            val response = runCatching {
                post(
                    url = ACCESS_TOKEN_URL,
                    body = "client_id=$clientId" +
                        "&device_code=${grant.deviceCode}" +
                        "&grant_type=$DEVICE_GRANT_TYPE",
                )
            }.onFailure { error ->
                // A dropped request mid-flow is common on mobile. Keep polling
                // until the code expires rather than failing the whole sign-in.
                logger.warn(TAG, "Token poll failed", error)
            }.getOrNull()

            if (response == null) continue

            response.string("access_token")?.let { token ->
                tokenStore.put(TOKEN_KEY, token)
                logger.info(TAG, "GitHub account connected")
                return@withContext AuthResult.Success
            }

            when (val error = response.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += SLOW_DOWN_INCREMENT_SECONDS
                "expired_token" -> return@withContext AuthResult.Failed("The code expired. Try again.")
                "access_denied" -> return@withContext AuthResult.Cancelled
                else -> return@withContext AuthResult.Failed(
                    error?.replace('_', ' ') ?: "Sign-in failed.",
                )
            }
        }

        AuthResult.Failed("The code expired. Try again.")
    }

    private fun post(url: String, body: String): JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        connection.outputStream.use { it.write(body.toByteArray()) }

        val text = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: IOException) {
            // GitHub returns the OAuth error body with a 4xx status, and
            // HttpURLConnection routes that to the error stream.
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: throw error
        }

        return json.parseToJsonElement(text) as? JsonObject
            ?: error("Unexpected response from GitHub")
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    companion object {
        const val TOKEN_KEY = "github_access_token"
        const val CLIENT_ID_KEY = "github_client_id"

        /** Read-only. John never asks for write access to anyone's repositories. */
        const val SCOPES = "repo:status read:user notifications"

        private const val TAG = "GitHubAuth"
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        private const val VERIFICATION_FALLBACK = "https://github.com/login/device"
        private const val DEFAULT_INTERVAL_SECONDS = 5
        private const val DEFAULT_EXPIRY_SECONDS = 900
        private const val SLOW_DOWN_INCREMENT_SECONDS = 5
        private const val TIMEOUT_MILLIS = 20_000
    }
}
