package com.john.assistant.core.conversation

import com.john.assistant.core.llm.ChatMessage
import com.john.assistant.core.tool.ToolResult

/**
 * Short-term memory for the current conversation.
 *
 * Deliberately *short*: a rolling window of recent turns plus the referents the
 * last tool produced. Long-term memory ("my music app is Spotify") is a
 * separate, user-controlled store — see `core/memory`. Keeping them apart means
 * clearing history never silently wipes preferences, and disabling memory never
 * breaks follow-up questions.
 *
 * Not thread-safe by design; the orchestrator serialises access.
 */
class ConversationContextManager(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
) {

    private val turns = ArrayDeque<ConversationTurn>()

    var focus: ConversationFocus = ConversationFocus()
        private set

    var pending: PendingAction? = null
        private set

    val history: List<ConversationTurn> get() = turns.toList()

    fun record(turn: ConversationTurn) {
        turns.addLast(turn)
        while (turns.size > maxTurns) turns.removeFirst()
    }

    fun awaiting(action: PendingAction?) {
        pending = action
    }

    fun clearPending() {
        pending = null
    }

    /** Fold referents out of a successful tool result into the focus. */
    fun noteToolSuccess(toolName: String, result: ToolResult.Success) {
        val data = result.data
        focus = focus.copy(
            lastToolName = toolName,
            lastAppLabel = data["app_label"]?.toString() ?: focus.lastAppLabel,
            lastAppPackage = data["package"]?.toString() ?: focus.lastAppPackage,
            lastContactName = data["contact_name"]?.toString() ?: focus.lastContactName,
            facts = focus.facts + data
                .filterKeys { it in CARRIED_FACTS }
                .mapValues { (_, value) -> value.toString() },
        )
    }

    fun noteToolFailure(toolName: String) {
        focus = focus.copy(lastToolName = toolName)
    }

    /** Recent turns as model messages, oldest first. */
    fun toChatMessages(limit: Int = maxTurns): List<ChatMessage> =
        turns.takeLast(limit).flatMap { turn ->
            buildList {
                add(ChatMessage.user(turn.userText))
                if (turn.assistantText.isNotBlank()) add(ChatMessage.assistant(turn.assistantText))
            }
        }

    fun clear() {
        turns.clear()
        focus = ConversationFocus()
        pending = null
    }

    /** Drop everything older than [olderThanMillis]; used by the auto-forget setting. */
    fun expire(nowMillis: Long, olderThanMillis: Long) {
        while (turns.isNotEmpty() && nowMillis - turns.first().timestampMillis > olderThanMillis) {
            turns.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_TURNS = 8

        /**
         * Result keys carried forward as conversational referents. An allow-list,
         * not a filter: tool results can contain notification text and contact
         * numbers, and none of that belongs in the next prompt by default.
         */
        private val CARRIED_FACTS = setOf(
            "track", "artist", "album", "event_title", "alarm_time", "query", "url",
        )
    }
}
