package com.john.assistant.ai.llm

import com.john.assistant.ai.model.ModelManager
import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.llm.ChatTemplate
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.llm.ToolCallParser
import com.john.assistant.core.tool.ToolDefinition
import com.john.assistant.core.util.AssistantLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An on-device language model, whichever one the user installed.
 *
 * Everything model-specific is data: the weights come from [ModelManager], the
 * prompt format from the model's [ChatTemplate], and the runtime from an
 * [LlmBackend]. This class only sequences them — render, generate, parse — so
 * changing model or runtime never touches the assistant's logic.
 *
 * It reports [isReady] as false whenever the backend is missing or no model is
 * installed, which is what makes John degrade gracefully rather than fail:
 * `CompositeLlmEngine` then serves the deterministic matcher, and settings says
 * plainly that no model is loaded.
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    private val backend: LlmBackend,
    private val modelManager: ModelManager,
    private val logger: AssistantLogger,
) : LlmEngine {

    override val displayName: String
        get() = modelManager.activeModel()?.displayName ?: "No model selected"

    override val runsLocally: Boolean = true

    /**
     * Whether this engine can serve a turn — not whether it already has.
     *
     * Deliberately *not* `backend.isLoaded`. Weights load lazily on the first
     * real request (see [generate]), so a freshly installed model is never
     * loaded until something asks it to generate. Gating readiness on
     * `isLoaded` made that unreachable: `CompositeLlmEngine` skips a fallback
     * that is not ready, so the model was never asked, so it never loaded —
     * John reported "no model" with a model sitting installed and selected on
     * the models screen.
     *
     * The honest question is whether there is a runtime and a downloaded model
     * to load, which is what this now answers.
     */
    override val isReady: Boolean
        get() = backend.isSupported && (backend.isLoaded || hasInstalledModel())

    /** A selected model whose weights are actually on disk. */
    private fun hasInstalledModel(): Boolean =
        modelManager.activeModel()?.let { modelManager.fileFor(it) != null } == true

    override suspend fun warmUp() {
        if (!backend.isSupported) {
            logger.info(TAG, "No inference runtime in this build; skipping model load")
            return
        }
        if (backend.isLoaded) return

        val model = modelManager.activeModel() ?: run {
            logger.info(TAG, "No model selected")
            return
        }

        val file = modelManager.fileFor(model) ?: run {
            logger.warn(TAG, "${model.displayName} is selected but not downloaded")
            return
        }

        logger.info(TAG, "Loading ${model.displayName} (${model.sizeMb} MB)")
        val loaded = backend.load(file, model.contextTokens)
        logger.info(TAG, if (loaded) "Model ready" else "Model failed to load")
    }

    override suspend fun unload() {
        backend.unload()
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions,
    ): LlmResponse {
        if (!backend.isSupported) {
            return LlmResponse.Error("No on-device inference runtime is installed.")
        }

        if (!backend.isLoaded) {
            // Loading on first use rather than at startup: a cold start should
            // not pay for a model the user may never invoke this session.
            warmUp()
            if (!backend.isLoaded) {
                return LlmResponse.Error("No language model is loaded.")
            }
        }

        val template = modelManager.activeModel()?.template ?: ChatTemplate.PLAIN
        val prompt = template.render(messages)

        val raw = backend.generate(
            prompt = prompt,
            maxTokens = options.maxTokens,
            temperature = options.temperature,
            topP = options.topP,
            stopSequences = template.stopSequences,
        ) ?: return LlmResponse.Error("Inference produced no output.")

        logger.debug(TAG, "Raw completion: ${raw.take(RAW_LOG_LIMIT)}")

        // The parser is deliberately forgiving about shape and strict about
        // content: anything it cannot read confidently becomes spoken text
        // rather than an executed action.
        return ToolCallParser.parse(stripTemplateTokens(raw, template))
    }

    /**
     * Models routinely emit a trailing control token that the backend's stop
     * handling did not catch. Leaving it in breaks JSON parsing for what is
     * otherwise a perfectly good tool call.
     */
    private fun stripTemplateTokens(raw: String, template: ChatTemplate): String {
        var text = raw
        template.stopSequences.forEach { token -> text = text.replace(token, "") }
        return text.trim()
    }

    private companion object {
        const val TAG = "LocalLlm"
        const val RAW_LOG_LIMIT = 300
    }
}
