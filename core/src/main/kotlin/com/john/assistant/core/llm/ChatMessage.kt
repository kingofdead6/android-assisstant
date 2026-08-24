package com.john.assistant.core.llm

/** Who produced a message in the conversation sent to the model. */
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * One message in the model's context window.
 *
 * [toolName] is set on [ChatRole.TOOL] messages so the model can tell which
 * action produced the observation it is reading.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolName: String? = null,
) {
    companion object {
        fun system(content: String) = ChatMessage(ChatRole.SYSTEM, content)
        fun user(content: String) = ChatMessage(ChatRole.USER, content)
        fun assistant(content: String) = ChatMessage(ChatRole.ASSISTANT, content)
        fun tool(name: String, content: String) = ChatMessage(ChatRole.TOOL, content, name)
    }
}
