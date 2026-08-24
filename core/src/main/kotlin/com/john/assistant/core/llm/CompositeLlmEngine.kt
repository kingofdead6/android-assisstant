package com.john.assistant.core.llm

import com.john.assistant.core.tool.ToolDefinition
import com.john.assistant.core.util.AssistantLogger

/**
 * Tries a fast deterministic engine first, then falls back to a real model.
 *
 * This is how John is wired in practice. The ordering matters:
 *
 *  - "pause" and "what's my battery" resolve in microseconds with no inference,
 *    which is most of what an assistant is actually asked;
 *  - anything with real language in it falls through to the model;
 *  - if the model is missing, still loading, or errors, the deterministic
 *    engine's answer is still there — John degrades instead of breaking.
 *
 * The composite is honest about locality: [runsLocally] is true only when
 * *every* delegate runs on-device, so the privacy screen cannot overstate.
 */
class CompositeLlmEngine(
    private val fast: LlmEngine,
    private val fallback: LlmEngine,
    private val logger: AssistantLogger = AssistantLogger.NONE,
) : LlmEngine {

    override val displayName: String
        get() = "${fast.displayName} + ${fallback.displayName}"

    override val isReady: Boolean
        get() = fast.isReady || fallback.isReady

    override val runsLocally: Boolean
        get() = fast.runsLocally && fallback.runsLocally

    override suspend fun warmUp() {
        runCatching { fallback.warmUp() }
            .onFailure { logger.warn(TAG, "Fallback engine failed to warm up", it) }
    }

    override suspend fun unload() {
        runCatching { fallback.unload() }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions,
    ): LlmResponse {
        val quick = runCatching { fast.generate(messages, tools, options) }
            .getOrElse { error ->
                logger.warn(TAG, "Fast engine threw", error)
                LlmResponse.Error("fast engine failed", error)
            }

        // A tool call from the deterministic engine is as good as it gets: it
        // matched a known phrase exactly, so there is nothing for a model to add.
        if (quick is LlmResponse.ToolCall) return quick

        if (!fallback.isReady) {
            logger.debug(TAG, "No model loaded; using the deterministic result")
            return quick
        }

        val considered = runCatching { fallback.generate(messages, tools, options) }
            .getOrElse { error ->
                logger.warn(TAG, "Fallback engine threw", error)
                LlmResponse.Error("model failed", error)
            }

        // Never let a model error erase a usable deterministic answer.
        return if (considered is LlmResponse.Error) quick else considered
    }

    private companion object {
        const val TAG = "CompositeLlm"
    }
}
