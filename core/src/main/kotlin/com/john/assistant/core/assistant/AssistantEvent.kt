package com.john.assistant.core.assistant

import com.john.assistant.core.tool.PermissionKey

/**
 * What the assistant is doing, streamed as it happens.
 *
 * The orchestrator emits these; the Android layer turns them into orb states,
 * speech and history rows. Nothing above the orchestrator has to reconstruct
 * the state machine from return values.
 */
sealed interface AssistantEvent {

    /** The transcript John is acting on. */
    data class Heard(val text: String) : AssistantEvent

    /** Inference has started. */
    data object Thinking : AssistantEvent

    /** A tool passed validation and is about to run. */
    data class Executing(val toolName: String, val description: String) : AssistantEvent

    /** Final answer for this turn. The caller speaks it. */
    data class Reply(val text: String, val spoken: Boolean = true) : AssistantEvent

    /** John needs a yes before continuing. The next utterance answers it. */
    data class AwaitingConfirmation(val question: String) : AssistantEvent

    /** John needs the user to pick one of [options]. */
    data class AwaitingChoice(val question: String, val options: List<String>) : AssistantEvent

    /** A capability is missing. The caller opens the right settings screen. */
    data class PermissionNeeded(
        val permission: PermissionKey,
        val message: String,
    ) : AssistantEvent

    /** The turn is over, whatever the outcome. Always emitted last. */
    data object Done : AssistantEvent
}

/** Coarse UI state, derived from the event stream. */
enum class AssistantState { IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, AWAITING_INPUT, ERROR }
