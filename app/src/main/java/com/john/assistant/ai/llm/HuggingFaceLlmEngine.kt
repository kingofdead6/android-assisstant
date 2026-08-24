package com.john.assistant.ai.llm

import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.llm.ChatTemplate
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.llm.ToolCallParser
import com.john.assistant.core.tool.ToolDefinition
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.data.preferences.SettingsRepository
import com.john.assistant.integrations.huggingface.HuggingFaceAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text generation through the Hugging Face Inference API.
 *
 * The one engine here that leaves the device, so it is deliberately explicit
 * about its two preconditions — a model ID and a token — and refuses to send a
 * request when either is missing. An unauthenticated call to a gated model
 * comes back as a 401 with an HTML body; turning that into "the assistant is
 * having trouble" tells the user nothing they can act on, so each failure is
 * mapped to the thing they would have to fix.
 */
@Singleton
class HuggingFaceLlmEngine @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val auth: HuggingFaceAuth,
    private val logger: AssistantLogger,
    private val scope: CoroutineScope,
) : LlmEngine {

    override val displayName: String get() = "Hugging Face API"
    private val configuredModelId = MutableStateFlow("")

    /**
     * Ready only when *both* halves of the configuration are present.
     *
     * The token is required, not optional. Treating a blank token as usable
     * only produced a 401 one turn later, after John had already told the user
     * it was ready.
     */
    override val isReady: Boolean
        get() = configuredModelId.value.isNotBlank() && hasToken()

    override val runsLocally: Boolean = false

    /** Whether a token is saved. Used by the UI to explain what is missing. */
    fun hasToken(): Boolean = !auth.token().isNullOrBlank()

    init {
        scope.launch {
            settingsRepository.settings.collect { configuredModelId.value = it.huggingFaceModelId.trim() }
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val model = settingsRepository.current().huggingFaceModelId.trim()
        if (model.isBlank()) {
            return@withContext LlmResponse.Error(
                "No Hugging Face model is set. Add one on the AI models screen.",
            )
        }

        // Checked before the connection is opened, so a missing token can never
        // become an unauthenticated request.
        val token = auth.token()
        if (token.isNullOrBlank()) {
            logger.warn(TAG, "Refusing Hugging Face request for $model: no API token saved")
            return@withContext LlmResponse.Error(
                "No Hugging Face API token is saved. Add one on the AI models screen.",
            )
        }

        val prompt = ChatTemplate.PLAIN.render(messages)
        runCatching {
            // Path-encoded: a model ID is "owner/name" and the slash is a real
            // path separator, but the segments themselves are user input.
            val endpoint = "$API_BASE/${encodeModelPath(model)}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val body = buildJsonObject {
                put("inputs", prompt)
                put("parameters", buildJsonObject {
                    put("max_new_tokens", options.maxTokens)
                    put("temperature", options.temperature.toDouble())
                    put("top_p", options.topP.toDouble())
                    put("return_full_text", false)
                })
                // Without this a cold model returns 503 rather than queueing.
                put("options", buildJsonObject { put("wait_for_model", true) })
            }.toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                logger.warn(TAG, "Hugging Face returned HTTP $status for $model")
                return@runCatching LlmResponse.Error(describeHttpFailure(status, model, responseText))
            }

            val raw = parseGeneratedText(Json.parseToJsonElement(responseText))
                ?: error("Hugging Face returned no generated text")
            ToolCallParser.parse(truncateAtStop(raw))
        }.onFailure { error ->
            logger.warn(TAG, "Hugging Face request failed", error)
        }.getOrElse { error ->
            LlmResponse.Error(
                "Hugging Face request failed: ${error.message ?: error::class.java.simpleName}",
                error,
            )
        }
    }

    /**
     * Turn a status code into the action that fixes it.
     *
     * 401/403 is the token, 404 is the model ID, 429 is quota. Saying which one
     * is wrong is the difference between a user fixing it in ten seconds and
     * giving up on the feature.
     */
    private fun describeHttpFailure(status: Int, model: String, body: String): String = when (status) {
        401 -> "Hugging Face rejected the API token. Check it on the AI models screen."
        403 -> "That token isn't allowed to use $model. The model's licence may need accepting."
        404 -> "Hugging Face has no model called $model. Check the ID (owner/model-name)."
        429 -> "Hugging Face rate limit reached. Try again shortly."
        503 -> "The model $model is still loading on Hugging Face. Try again in a moment."
        else -> "Hugging Face returned HTTP $status: ${body.take(ERROR_LIMIT)}"
    }

    private fun encodeModelPath(model: String): String =
        model.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    private fun parseGeneratedText(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstOrNull()?.jsonObject?.string("generated_text")
        is JsonObject -> element.string("generated_text")
        else -> null
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun truncateAtStop(raw: String): String {
        val stops = listOf("\nUser:", "\nSystem:")
        val cut = stops.mapNotNull { raw.indexOf(it).takeIf { index -> index >= 0 } }.minOrNull()
        return (if (cut == null) raw else raw.substring(0, cut)).trim()
    }

    override suspend fun warmUp() = Unit
    override suspend fun unload() = Unit

    private companion object {
        const val TAG = "HuggingFaceLlm"
        const val API_BASE = "https://api-inference.huggingface.co/models"
        const val TIMEOUT_MILLIS = 30_000
        const val ERROR_LIMIT = 300
    }
}
