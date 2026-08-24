package com.john.assistant.core.conversation

/** How a turn ended. Drives history styling and "what went wrong" follow-ups. */
enum class TurnOutcome { SPOKEN, EXECUTED, DECLINED, FAILED, PERMISSION_NEEDED }

/** One completed exchange, as shown in the history screen and stored in Room. */
data class ConversationTurn(
    val id: Long = 0,
    val timestampMillis: Long,
    val userText: String,
    val assistantText: String,
    val toolName: String? = null,
    val outcome: TurnOutcome = TurnOutcome.SPOKEN,
)

/**
 * What the conversation is currently "about".
 *
 * This is what makes follow-ups work: after "open YouTube", a bare
 * "search for AI tutorials" should search *in YouTube*, and after
 * "call Mom" a bare "the mobile one" should pick a number for *Mom*.
 * Rather than guessing, John records the referents each tool produced and
 * offers them to the model as explicit context lines.
 */
data class ConversationFocus(
    val lastToolName: String? = null,
    val lastAppLabel: String? = null,
    val lastAppPackage: String? = null,
    val lastContactName: String? = null,
    /** Extra referents contributed by tool results, e.g. `track` or `event_title`. */
    val facts: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = lastToolName == null && lastAppLabel == null &&
            lastContactName == null && facts.isEmpty()

    /** Lines injected into the prompt. Short on purpose — context window is small. */
    fun describe(): List<String> = buildList {
        lastAppLabel?.let { add("The app currently in the foreground because of John is $it.") }
        lastContactName?.let { add("The contact under discussion is $it.") }
        lastToolName?.let { add("The last tool John ran was $it.") }
        facts.forEach { (key, value) -> add("${key.replace('_', ' ')}: $value") }
    }
}
