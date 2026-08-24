package com.john.assistant.ai.llm

import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.tool.ToolDefinition
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.data.preferences.LlmBackendChoice
import com.john.assistant.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a turn to the engine the user picked.
 *
 * Selection is read from [SettingsRepository.getActiveBackend] rather than
 * inferred from which engine happens to look ready. The previous rule —
 * "remote if `remote.isReady`" — meant saving a Hugging Face model ID silently
 * took over routing and clearing it silently handed it back, neither of which
 * the user asked for.
 *
 * When the chosen engine cannot run, the missing piece is always named — either
 * as an [LlmResponse.Error], or in the log when an installed local model can
 * still answer the turn. What it never does is fail silently: a user whose
 * token was never saved would otherwise have no way to tell.
 */
@Singleton
class ConfiguredLlmEngine @Inject constructor(
    private val local: LocalLlmEngine,
    private val remote: HuggingFaceLlmEngine,
    private val settingsRepository: SettingsRepository,
    private val logger: AssistantLogger,
    scope: CoroutineScope,
) : LlmEngine {

    /**
     * The stored choice, mirrored for the synchronous [displayName]/[isReady]
     * properties the UI reads. [generate] re-reads settings directly so a
     * change applies to the very next turn.
     */
    private val choice = MutableStateFlow(LlmBackendChoice.DEFAULT)

    init {
        scope.launch {
            settingsRepository.settings.collect { choice.value = it.llmBackend }
        }
    }

    private fun engineFor(selection: LlmBackendChoice): LlmEngine = when (selection) {
        LlmBackendChoice.HUGGING_FACE -> remote
        LlmBackendChoice.LOCAL -> local
        // Prefer on-device: it is private and free. The remote engine takes
        // over only when it is fully configured and the local one is not.
        LlmBackendChoice.AUTOMATIC -> if (!local.isReady && remote.isReady) remote else local
    }

    private fun selected(): LlmEngine = engineFor(choice.value)

    override val displayName: String get() = selected().displayName
    override val isReady: Boolean get() = selected().isReady
    override val runsLocally: Boolean get() = selected().runsLocally

    override suspend fun warmUp() = selected().warmUp()
    override suspend fun unload() = selected().unload()

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions,
    ): LlmResponse {
        val selection = settingsRepository.getActiveBackend()
        choice.value = selection

        // A chosen Hugging Face backend that cannot run must not pretend to
        // work. If an installed local model can answer, use it and say the
        // routing changed; otherwise surface the configuration problem rather
        // than failing vaguely.
        if (selection == LlmBackendChoice.HUGGING_FACE && !remote.isReady) {
            if (!local.isReady) {
                return LlmResponse.Error(missingHuggingFaceConfiguration())
            }
            logger.warn(
                TAG,
                "Hugging Face is selected but not configured; answering with the local model",
            )
            return local.generate(messages, tools, options)
        }

        return engineFor(selection).generate(messages, tools, options)
    }

    private fun missingHuggingFaceConfiguration(): String = when {
        !remote.hasToken() ->
            "Hugging Face is selected but no API token is saved. Add one on the AI models screen."
        else ->
            "Hugging Face is selected but no model ID is set. Add one on the AI models screen."
    }

    private companion object {
        const val TAG = "ConfiguredLlm"
    }
}
