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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HuggingFaceLlmEngine @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val auth: HuggingFaceAuth,
    private val logger: AssistantLogger,
    private val scope: CoroutineScope,
) : LlmEngine {

    override val displayName: String get() = "Hugging Face API"
    private val configuredModelId = MutableStateFlow("")

    override val isReady: Boolean get() = configuredModelId.value.isNotBlank()
    override val runsLocally: Boolean = false

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
        val token = auth.token()
        val model = settingsRepository.current().huggingFaceModelId.trim()
        if (model.isBlank()) return@withContext LlmResponse.Error("Hugging Face model ID is missing.")

        val prompt = ChatTemplate.PLAIN.render(messages)
        runCatching {
            val connection = (URL("$API_BASE/$model").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
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
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseText = (if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (connection.responseCode !in 200..299) {
                error("Hugging Face returned HTTP ${connection.responseCode}: ${responseText.take(ERROR_LIMIT)}")
            }

            val raw = parseGeneratedText(Json.parseToJsonElement(responseText))
                ?: error("Hugging Face returned no generated text")
            ToolCallParser.parse(truncateAtStop(raw))
        }.onFailure { error ->
            logger.warn(TAG, "Hugging Face request failed", error)
        }.getOrElse { error ->
            LlmResponse.Error("Hugging Face request failed: ${error.message ?: error::class.java.simpleName}", error)
        }
    }

    private fun parseGeneratedText(element: kotlinx.serialization.json.JsonElement): String? = when (element) {
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
