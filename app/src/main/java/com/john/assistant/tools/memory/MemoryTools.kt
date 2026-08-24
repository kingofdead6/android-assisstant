package com.john.assistant.tools.memory

import com.john.assistant.core.memory.MemoryCategory
import com.john.assistant.core.memory.MemoryEntry
import com.john.assistant.core.memory.MemorySource
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.core.util.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Remember that my preferred music app is Spotify."
 *
 * The only way anything reaches long-term memory. There is no inference path
 * that writes memories from ordinary conversation — the user says "remember",
 * or nothing is stored. That is what makes the memory screen's list complete:
 * everything in it is there because it was asked for.
 *
 * Statements are split into a key and a value so later turns can look them up
 * ("my music app is Spotify" becomes `music_app` → `Spotify`), while the
 * original wording is kept for anything that will not split.
 */
@Singleton
class RememberFactTool @Inject constructor(
    private val memory: MemoryStore,
    private val timeSource: TimeSource,
) : AssistantTool {

    override val name = "remember_fact"

    override val description =
        "Store something the user explicitly asked John to remember, such as a preference."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "statement",
            type = ParameterType.STRING,
            description = "What to remember, in the user's words.",
            required = true,
        ),
        ToolParameter("key", ParameterType.STRING, "A short label, if one is obvious."),
    )

    override val riskLevel = RiskLevel.LOW

    override val examples = listOf(
        "remember that my music app is Spotify",
        "remember my office is on Rue Ben Boulaid",
    )

    override fun describeAction(arguments: ToolArguments): String =
        "remember that ${arguments.string("statement", "that")}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        if (!memory.isEnabled) {
            return ToolResult.Failure(
                message = "Memory is switched off. You can turn it on in privacy settings.",
                recoverable = false,
            )
        }

        val statement = arguments.string("statement")?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("What should I remember?")

        val (key, value) = arguments.string("key")
            ?.let { normaliseKey(it) to statement }
            ?: splitStatement(statement)

        val now = timeSource.nowMillis()
        memory.remember(
            MemoryEntry(
                key = key,
                value = value,
                category = if (isPreference(statement)) MemoryCategory.PREFERENCE else MemoryCategory.FACT,
                source = MemorySource.EXPLICIT,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )

        return ToolResult.Success("Got it — I'll remember that.")
    }

    /**
     * "my preferred music app is Spotify" -> `music_app` / `Spotify`.
     *
     * Falls back to storing the whole sentence under a generated key, which is
     * still useful: it goes into the prompt and John can recall it verbatim.
     */
    private fun splitStatement(statement: String): Pair<String, String> {
        val match = SUBJECT_IS_VALUE.find(statement)
            ?: return normaliseKey(statement.take(KEY_LENGTH)) to statement

        val subject = match.groupValues[1]
        val value = match.groupValues[2].trim()
        return normaliseKey(subject) to value
    }

    private fun normaliseKey(raw: String): String = raw
        .lowercase()
        .replace(FILLER_WORDS, " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .trim()
        .replace(Regex("\\s+"), "_")
        .take(KEY_LENGTH)
        .ifEmpty { "note" }

    private fun isPreference(statement: String): Boolean =
        PREFERENCE_WORDS.containsMatchIn(statement)

    private companion object {
        const val KEY_LENGTH = 40

        val SUBJECT_IS_VALUE = Regex("""^(.*?)\s+(?:is|are)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val FILLER_WORDS = Regex("""\b(my|the|a|an|preferred|favourite|favorite|default)\b""", RegexOption.IGNORE_CASE)
        val PREFERENCE_WORDS = Regex("""\b(prefer|preferred|favourite|favorite|default|always use)\b""", RegexOption.IGNORE_CASE)
    }
}

/** "What do you remember about me?" */
@Singleton
class RecallMemoryTool @Inject constructor(
    private val memory: MemoryStore,
) : AssistantTool {

    override val name = "recall_memory"

    override val description = "Say what John has been asked to remember."

    override val parameters = ToolParameters.of(
        ToolParameter("about", ParameterType.STRING, "Optional subject to narrow it to."),
    )

    override val examples = listOf("what do you remember", "what's my music app")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        if (!memory.isEnabled) {
            return ToolResult.Success("Memory is switched off, so I'm not keeping anything.")
        }

        val about = arguments.string("about")?.lowercase()
        val entries = memory.all()
            .filter { about == null || it.key.contains(about) || it.value.lowercase().contains(about) }

        if (entries.isEmpty()) {
            return ToolResult.Success(
                if (about != null) "I don't have anything about $about." else "I'm not remembering anything yet.",
            )
        }

        return ToolResult.Success(
            entries.take(MAX_SPOKEN).joinToString(". ") {
                "${it.key.replace('_', ' ')}: ${it.value}"
            },
        )
    }

    private companion object {
        const val MAX_SPOKEN = 6
    }
}

/**
 * "Forget my music app."
 *
 * MEDIUM risk on purpose: deleting a memory is not reversible from the voice
 * interface, and the user should hear what is about to go.
 */
@Singleton
class ForgetMemoryTool @Inject constructor(
    private val memory: MemoryStore,
) : AssistantTool {

    override val name = "forget_memory"

    override val description = "Delete something John was remembering."

    override val parameters = ToolParameters.of(
        ToolParameter("key", ParameterType.STRING, "What to forget.", required = true),
    )

    override val riskLevel = RiskLevel.MEDIUM

    override val examples = listOf("forget my music app", "stop remembering my office")

    override fun describeAction(arguments: ToolArguments): String =
        "forget ${arguments.string("key", "that")}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val query = arguments.string("key")?.lowercase()?.replace(' ', '_')
            ?: return ToolResult.Failure("What should I forget?")

        val match = memory.all().firstOrNull { it.key.contains(query) }
            ?: return ToolResult.Failure("I'm not remembering anything like that.", recoverable = false)

        memory.forget(match.key)
        return ToolResult.Success("Forgotten.")
    }
}
