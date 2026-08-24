package com.john.assistant.core.prompt

import com.john.assistant.core.conversation.ConversationFocus
import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.tool.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json

/** Everything that varies between turns, gathered in one place. */
data class PromptContext(
    val toolDefinitions: List<ToolDefinition>,
    val focus: ConversationFocus = ConversationFocus(),
    val memoryLines: List<String> = emptyList(),
    val recentTurns: List<ChatMessage> = emptyList(),
    val isOnline: Boolean = true,
    val accessibilityEnabled: Boolean = false,
    val deviceFacts: List<String> = emptyList(),
)

/**
 * Assembles the message list handed to the model.
 *
 * Ordering matters for small models: instructions, then the tool catalogue,
 * then situational context, then history, then the request. Putting the tool
 * schema *before* the conversation keeps it inside the attention budget of a
 * 1–4B model with a short window, which is what actually runs on a phone.
 */
class PromptBuilder(
    private val systemPrompt: String = SystemPrompts.DEFAULT,
    /** Turns of history to include. Small on purpose: tokens are latency. */
    private val historyLimit: Int = 6,
) {

    private val json = Json { prettyPrint = false }

    fun build(userUtterance: String, context: PromptContext): List<ChatMessage> = buildList {
        add(ChatMessage.system(buildSystemBlock(context)))
        addAll(context.recentTurns.takeLast(historyLimit * 2))
        add(ChatMessage.user(userUtterance))
    }

    /** Second pass: turn a tool outcome into a spoken sentence. */
    fun buildPhrasing(userUtterance: String, toolName: String, outcome: String): List<ChatMessage> =
        listOf(
            ChatMessage.system(SystemPrompts.RESPONSE_PHRASING),
            ChatMessage.user("The user said: \"$userUtterance\""),
            ChatMessage.tool(toolName, outcome),
        )

    private fun buildSystemBlock(context: PromptContext): String = buildString {
        appendLine(systemPrompt)
        appendLine()

        appendLine("AVAILABLE TOOLS")
        appendLine(json.encodeToString(JsonArray.serializer(), toolSchema(context.toolDefinitions)))

        if (!context.isOnline) {
            appendLine()
            appendLine(SystemPrompts.OFFLINE_NOTICE)
        }
        if (context.accessibilityEnabled) {
            appendLine()
            appendLine(SystemPrompts.ACCESSIBILITY_NOTICE)
        }

        val situation = buildList {
            addAll(context.deviceFacts)
            addAll(context.focus.describe())
        }
        if (situation.isNotEmpty()) {
            appendLine()
            appendLine("CURRENT CONTEXT")
            situation.forEach { appendLine("- $it") }
        }

        if (context.memoryLines.isNotEmpty()) {
            appendLine()
            appendLine("WHAT YOU REMEMBER ABOUT THIS USER")
            context.memoryLines.forEach { appendLine("- $it") }
        }
    }.trimEnd()

    private fun toolSchema(definitions: List<ToolDefinition>): JsonArray =
        JsonArray(definitions.map { it.toJsonSchema() })
}
