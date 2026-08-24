package com.john.assistant.core.assistant

import com.john.assistant.core.llm.LlmOptions
import com.john.assistant.core.policy.ConfirmationPolicy
import com.john.assistant.core.prompt.SystemPrompts

/**
 * Everything the user can change that alters how a turn is handled.
 *
 * Read fresh at the start of each turn, so toggling a setting takes effect on
 * the next thing said rather than on the next app launch.
 */
data class AssistantConfig(
    val systemPrompt: String = SystemPrompts.DEFAULT,
    val llmOptions: LlmOptions = LlmOptions(),
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.BALANCED,
    /**
     * Run a second inference pass to rephrase tool results.
     *
     * Off by default: tool messages are already written as speech, and a second
     * pass on a phone-sized model costs a second of latency and adds a chance
     * of the model embellishing an outcome it did not observe.
     */
    val phraseResultsWithLlm: Boolean = false,
    val useMemory: Boolean = true,
    val historyTurns: Int = 6,
)

/** Supplies the current config. Backed by DataStore in the app. */
fun interface AssistantConfigProvider {
    suspend fun current(): AssistantConfig

    companion object {
        fun fixed(config: AssistantConfig = AssistantConfig()) = AssistantConfigProvider { config }
    }
}
