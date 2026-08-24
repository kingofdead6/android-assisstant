package com.john.assistant.core.llm

import com.john.assistant.core.tool.ToolDefinition

/** Generation knobs, surfaced in settings. */
data class LlmOptions(
    val temperature: Float = 0.2f,
    val maxTokens: Int = 256,
    val topP: Float = 0.9f,
    /** Hard stop so a runaway local model can't hang the pipeline. */
    val timeoutMillis: Long = 20_000,
)

/** What the model decided to do with a turn. */
sealed interface LlmResponse {

    /** Call one tool. Arguments are still *untrusted* until validated. */
    data class ToolCall(
        val toolName: String,
        val arguments: Map<String, Any?>,
        /** Optional short line the model wants said while the tool runs. */
        val preamble: String? = null,
    ) : LlmResponse

    /** Answer in words — no device action needed. */
    data class Text(val content: String) : LlmResponse

    /** Inference failed. The pipeline degrades instead of crashing. */
    data class Error(val message: String, val cause: Throwable? = null) : LlmResponse
}

/**
 * A local language model.
 *
 * John treats the model as an *intent classifier with arguments*, not as a
 * chatbot: the overwhelmingly common response is a [LlmResponse.ToolCall].
 *
 * Implementations must be swappable — the app ships several (a deterministic
 * matcher that needs no model at all, plus on-device backends) and the user
 * picks one in settings. Nothing above this interface knows which is loaded.
 */
interface LlmEngine {

    /** Human-readable name shown in settings, e.g. "Qwen 3 1.7B (GGUF)". */
    val displayName: String

    /** True once weights are in memory and [generate] can serve a request. */
    val isReady: Boolean

    /** Whether inference happens on-device. Surfaced in the privacy screen. */
    val runsLocally: Boolean get() = true

    /** Load weights. Safe to call repeatedly; must be cheap when already loaded. */
    suspend fun warmUp() {}

    /** Free weights so the OS can reclaim the memory. */
    suspend fun unload() {}

    /**
     * Pick a tool (or answer in words) for the given conversation.
     *
     * Must never throw: inference problems come back as [LlmResponse.Error].
     */
    suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions = LlmOptions(),
    ): LlmResponse
}
