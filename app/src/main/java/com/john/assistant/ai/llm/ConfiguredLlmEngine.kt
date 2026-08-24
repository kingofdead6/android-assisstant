package com.john.assistant.ai.llm

import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.tool.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfiguredLlmEngine @Inject constructor(
    private val local: LocalLlmEngine,
    private val remote: HuggingFaceLlmEngine,
) : LlmEngine {

    private fun selected(): LlmEngine = if (remote.isReady) remote else local

    override val displayName: String get() = selected().displayName
    override val isReady: Boolean get() = selected().isReady
    override val runsLocally: Boolean get() = selected().runsLocally

    override suspend fun warmUp() = selected().warmUp()
    override suspend fun unload() = selected().unload()

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions,
    ): LlmResponse = selected().generate(messages, tools, options)
}
