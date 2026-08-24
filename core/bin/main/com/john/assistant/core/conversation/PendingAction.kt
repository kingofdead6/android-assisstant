package com.john.assistant.core.conversation

import com.john.assistant.core.tool.ClarificationOption
import com.john.assistant.core.tool.ToolArguments

/**
 * Something John has asked about and is waiting on an answer for.
 *
 * Holding this in the context manager (rather than in the UI) is what lets the
 * *voice* path answer a question: "Send it?" — "Yes." never touches the screen.
 */
sealed interface PendingAction {

    val toolName: String

    /** John proposed an action and needs an explicit yes. */
    data class Confirmation(
        override val toolName: String,
        val arguments: ToolArguments,
        val question: String,
        /** The utterance that led here, replayed into history if declined. */
        val originalUtterance: String,
    ) : PendingAction

    /** John needs the user to pick between concrete options. */
    data class Clarification(
        override val toolName: String,
        val question: String,
        val options: List<ClarificationOption>,
        val originalUtterance: String,
    ) : PendingAction
}
