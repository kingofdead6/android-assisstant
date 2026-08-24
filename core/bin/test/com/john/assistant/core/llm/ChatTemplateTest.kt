package com.john.assistant.core.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatTemplateTest {

    private val conversation = listOf(
        ChatMessage.system("You are John."),
        ChatMessage.user("open YouTube"),
        ChatMessage.assistant("""{"tool":"open_app"}"""),
        ChatMessage.user("pause"),
    )

    @Test
    fun `ChatML wraps every turn and leaves the assistant header open`() {
        val prompt = ChatTemplate.CHAT_ML.render(conversation)

        assertTrue(prompt.startsWith("<|im_start|>system\nYou are John.<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\npause<|im_end|>"))
        // The trailing open header is what makes the model continue rather than
        // start a new conversation.
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `Llama 3 emits the begin token exactly once`() {
        val prompt = ChatTemplate.LLAMA3.render(conversation)

        assertEquals(1, prompt.split("<|begin_of_text|>").size - 1)
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>\n\nopen YouTube<|eot_id|>"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun `Gemma folds the system prompt into the first user turn`() {
        val prompt = ChatTemplate.GEMMA.render(conversation)

        // Gemma has no system role; emitting one produces a model that ignores it.
        assertFalse(prompt.contains("<start_of_turn>system"))
        assertTrue(prompt.contains("<start_of_turn>user\nYou are John.\n\nopen YouTube"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `Gemma renders assistant turns as the model role`() {
        val prompt = ChatTemplate.GEMMA.render(conversation)
        assertTrue(prompt.contains("<start_of_turn>model\n{\"tool\":\"open_app\"}<end_of_turn>"))
    }

    @Test
    fun `Gemma without a system message emits no stray blank line`() {
        val prompt = ChatTemplate.GEMMA.render(listOf(ChatMessage.user("pause")))
        assertEquals("<start_of_turn>user\npause<end_of_turn>\n<start_of_turn>model\n", prompt)
    }

    @Test
    fun `Phi uses its own role markers`() {
        val prompt = ChatTemplate.PHI.render(conversation)

        assertTrue(prompt.startsWith("<|system|>\nYou are John.<|end|>"))
        assertTrue(prompt.endsWith("<|assistant|>\n"))
    }

    @Test
    fun `plain uses no control tokens at all`() {
        val prompt = ChatTemplate.PLAIN.render(conversation)

        assertFalse(prompt.contains("<|"))
        assertFalse(prompt.contains("<start_of_turn>"))
        assertTrue(prompt.endsWith("Assistant: "))
    }

    @Test
    fun `tool observations are presented as user turns`() {
        // No small model handles a distinct tool role reliably; they were
        // trained on observations arriving as user text.
        val prompt = ChatTemplate.CHAT_ML.render(
            listOf(ChatMessage.tool("get_battery", "74 percent")),
        )
        assertTrue(prompt.contains("<|im_start|>user\n74 percent<|im_end|>"))
    }

    @Test
    fun `every template declares stop sequences`() {
        ChatTemplate.entries.forEach { template ->
            assertTrue(template.stopSequences.isNotEmpty()) { "${template.name} has none" }
        }
    }

    @Test
    fun `unknown template ids fall back to plain rather than guessing`() {
        assertEquals(ChatTemplate.PLAIN, ChatTemplate.fromId("some-new-model"))
        assertEquals(ChatTemplate.PLAIN, ChatTemplate.fromId(null))
        assertEquals(ChatTemplate.CHAT_ML, ChatTemplate.fromId("chat_ml"))
        assertEquals(ChatTemplate.GEMMA, ChatTemplate.fromId(" gemma "))
    }
}
