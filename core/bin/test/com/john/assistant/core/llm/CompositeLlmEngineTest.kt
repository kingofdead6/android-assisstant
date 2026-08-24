package com.john.assistant.core.llm

import com.john.assistant.core.fake.FakeLlmEngine
import com.john.assistant.core.tool.ToolDefinition
import com.john.assistant.core.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompositeLlmEngineTest {

    private val tools = listOf(ToolDefinition("get_battery", "battery", ToolParameters.NONE))
    private val messages = listOf(ChatMessage.user("what's my battery"))

    @Test
    fun `a deterministic hit short circuits the model`() = runTest {
        val fast = FakeLlmEngine("fast").enqueueToolCall("get_battery")
        val model = FakeLlmEngine("model")

        val response = CompositeLlmEngine(fast, model).generate(messages, tools)

        assertEquals("get_battery", (response as LlmResponse.ToolCall).toolName)
        assertTrue(model.prompts.isEmpty()) { "the model should not have been consulted" }
    }

    @Test
    fun `a deterministic miss falls through to the model`() = runTest {
        val fast = FakeLlmEngine("fast").enqueueText("no idea")
        val model = FakeLlmEngine("model").enqueueToolCall("get_battery")

        val response = CompositeLlmEngine(fast, model).generate(messages, tools)

        assertEquals("get_battery", (response as LlmResponse.ToolCall).toolName)
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun `an unloaded model leaves the deterministic answer intact`() = runTest {
        val fast = FakeLlmEngine("fast").enqueueText("built-in reply")
        val model = FakeLlmEngine("model", isReady = false)

        val response = CompositeLlmEngine(fast, model).generate(messages, tools)

        assertEquals("built-in reply", (response as LlmResponse.Text).content)
        assertTrue(model.prompts.isEmpty())
    }

    @Test
    fun `a model error never erases a usable deterministic answer`() = runTest {
        val fast = FakeLlmEngine("fast").enqueueText("built-in reply")
        val model = FakeLlmEngine("model").enqueue(LlmResponse.Error("out of memory"))

        val response = CompositeLlmEngine(fast, model).generate(messages, tools)

        assertEquals("built-in reply", (response as LlmResponse.Text).content)
    }

    @Test
    fun `runsLocally is only true when every delegate is local`() {
        val local = FakeLlmEngine("local")
        val remote = object : LlmEngine by FakeLlmEngine("remote") {
            override val runsLocally = false
        }

        assertTrue(CompositeLlmEngine(local, local).runsLocally)
        assertFalse(CompositeLlmEngine(local, remote).runsLocally)
    }
}
