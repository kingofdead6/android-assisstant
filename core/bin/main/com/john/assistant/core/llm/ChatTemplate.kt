package com.john.assistant.core.llm

/**
 * Renders a conversation in the prompt format a given model family expects.
 *
 * This is not cosmetic. An instruction-tuned model given the wrong control
 * tokens degrades badly — it drifts into continuing the conversation instead of
 * answering, and its tool-call formatting falls apart, which for John means
 * every command becomes a shrug. The template is therefore part of the model's
 * identity in the catalogue, not a global setting.
 */
enum class ChatTemplate {

    /** Qwen, and most models that publish a ChatML tokenizer config. */
    CHAT_ML {
        override fun render(messages: List<ChatMessage>): String = buildString {
            messages.forEach { message ->
                append("<|im_start|>").append(message.role.chatMlName()).append('\n')
                append(message.content).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }

        override val stopSequences = listOf("<|im_end|>", "<|im_start|>")
    },

    /** Llama 3.x instruct. */
    LLAMA3 {
        override fun render(messages: List<ChatMessage>): String = buildString {
            append("<|begin_of_text|>")
            messages.forEach { message ->
                append("<|start_header_id|>").append(message.role.chatMlName())
                append("<|end_header_id|>\n\n")
                append(message.content).append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }

        override val stopSequences = listOf("<|eot_id|>", "<|end_of_text|>")
    },

    /** Gemma 2 / 3 instruct. Gemma has no system role, so it is folded in. */
    GEMMA {
        override fun render(messages: List<ChatMessage>): String = buildString {
            val system = messages.filter { it.role == ChatRole.SYSTEM }
                .joinToString("\n") { it.content }
            var systemEmitted = system.isEmpty()

            messages.filterNot { it.role == ChatRole.SYSTEM }.forEach { message ->
                val turn = if (message.role == ChatRole.ASSISTANT) "model" else "user"
                append("<start_of_turn>").append(turn).append('\n')
                if (!systemEmitted && turn == "user") {
                    append(system).append("\n\n")
                    systemEmitted = true
                }
                append(message.content).append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }

        override val stopSequences = listOf("<end_of_turn>", "<start_of_turn>")
    },

    /** Phi-3 / Phi-4 instruct. */
    PHI {
        override fun render(messages: List<ChatMessage>): String = buildString {
            messages.forEach { message ->
                append("<|").append(message.role.chatMlName()).append("|>\n")
                append(message.content).append("<|end|>\n")
            }
            append("<|assistant|>\n")
        }

        override val stopSequences = listOf("<|end|>", "<|user|>")
    },

    /**
     * No control tokens.
     *
     * The safe choice for an unknown model: a plain transcript will not confuse
     * a model that expected something else nearly as badly as the wrong special
     * tokens would.
     */
    PLAIN {
        override fun render(messages: List<ChatMessage>): String = buildString {
            messages.forEach { message ->
                append(message.role.plainName()).append(": ").append(message.content).append("\n\n")
            }
            append("Assistant: ")
        }

        override val stopSequences = listOf("\nUser:", "\nSystem:")
    },
    ;

    abstract fun render(messages: List<ChatMessage>): String

    abstract val stopSequences: List<String>

    protected fun ChatRole.chatMlName(): String = when (this) {
        ChatRole.SYSTEM -> "system"
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
        // No template has a tool role that small models handle reliably;
        // presenting observations as user turns is what they were trained on.
        ChatRole.TOOL -> "user"
    }

    protected fun ChatRole.plainName(): String = when (this) {
        ChatRole.SYSTEM -> "System"
        ChatRole.USER -> "User"
        ChatRole.ASSISTANT -> "Assistant"
        ChatRole.TOOL -> "Tool result"
    }

    companion object {
        fun fromId(raw: String?): ChatTemplate =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PLAIN
    }
}
