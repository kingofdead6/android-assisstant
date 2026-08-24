package com.john.assistant.core.llm.rules

import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.llm.ChatRole
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.tool.ToolDefinition
import com.john.assistant.core.util.TimeSource

/**
 * An [LlmEngine] with no model behind it.
 *
 * It matches the utterance against [DefaultIntentPatterns] and returns a tool
 * call directly. This is what John falls back to when no weights are installed,
 * when the user has turned inference off to save battery, or when a model fails
 * to load — and it means a fresh install answers "what's my battery?" correctly
 * before the user has downloaded a gigabyte of anything.
 *
 * It reports [runsLocally] as true and [isReady] as always true, because it is:
 * there is nothing to load and nothing leaves the device.
 *
 * Deliberately conservative. When no pattern matches it returns
 * [LlmResponse.Text] saying so, rather than reaching for the nearest tool.
 * Guessing an action from a phrase it does not understand is exactly the
 * failure mode this class exists to avoid.
 */
class RuleBasedLlmEngine(
    private val timeSource: TimeSource = TimeSource.SYSTEM,
    private val patternsProvider: (Int) -> List<IntentPattern> = DefaultIntentPatterns::catalogue,
) : LlmEngine {

    override val displayName: String = "Built-in commands (no model)"
    override val isReady: Boolean = true
    override val runsLocally: Boolean = true

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        options: LlmOptions,
    ): LlmResponse {
        val utterance = messages.lastOrNull { it.role == ChatRole.USER }?.content?.trim().orEmpty()
        if (utterance.isEmpty()) return LlmResponse.Text(NO_INPUT)

        val available = tools.mapTo(HashSet()) { it.name }
        val normalised = normalise(utterance)

        val match = patternsProvider(currentHour())
            // Only offer tools that are actually registered and enabled right now.
            .asSequence()
            .filter { it.tool in available }
            .mapNotNull { candidate -> candidate.match(normalised)?.let { candidate.tool to it } }
            .firstOrNull()
            ?: return LlmResponse.Text(unmatchedReply(utterance))

        return LlmResponse.ToolCall(toolName = match.first, arguments = match.second)
    }

    private fun currentHour(): Int {
        val millisIntoDay = timeSource.nowMillis() % MILLIS_PER_DAY
        return (millisIntoDay / MILLIS_PER_HOUR).toInt()
    }

    /** Strips the wake word and filler so patterns match natural phrasing. */
    private fun normalise(utterance: String): String = utterance
        .trim()
        .removePrefix("\"")
        .removeSuffix("\"")
        .replace(WAKE_PREFIX, "")
        .replace(POLITENESS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', '!', '?')

    private fun unmatchedReply(utterance: String): String =
        "I don't have a built-in command for that yet. " +
            "Install a language model in settings and I'll be able to work out what \"" +
            utterance.take(60) + "\" means."

    private companion object {
        const val NO_INPUT = "I didn't catch that."
        const val MILLIS_PER_HOUR = 3_600_000L
        const val MILLIS_PER_DAY = 86_400_000L

        val WAKE_PREFIX = Regex("^\\s*(hey|hi|hello|ok|okay)[ ,]+john[ ,]*", RegexOption.IGNORE_CASE)
        val POLITENESS = Regex("\\b(please|could you|can you|would you|i want you to)\\b", RegexOption.IGNORE_CASE)
    }
}
