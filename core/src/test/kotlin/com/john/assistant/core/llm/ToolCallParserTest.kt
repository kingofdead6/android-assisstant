package com.john.assistant.core.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolCallParserTest {

    @Test
    fun `parses the documented shape`() {
        val call = assertToolCall(
            """{"tool": "open_app", "arguments": {"app_name": "YouTube"}}""",
        )
        assertEquals("open_app", call.toolName)
        assertEquals("YouTube", call.arguments["app_name"])
    }

    @Test
    fun `parses a markdown fenced block`() {
        val call = assertToolCall(
            """
            Sure, I can do that.
            ```json
            {"tool": "get_battery", "arguments": {}}
            ```
            """.trimIndent(),
        )
        assertEquals("get_battery", call.toolName)
    }

    @Test
    fun `parses despite chatter around the object`() {
        val call = assertToolCall("""Okay! {"tool":"pause_media","arguments":{}} Let me know.""")
        assertEquals("pause_media", call.toolName)
    }

    @Test
    fun `accepts the alternative key names small models use`() {
        listOf(
            """{"tool_name": "get_time", "args": {}}""",
            """{"name": "get_time", "parameters": {}}""",
            """{"action": "get_time", "input": {}}""",
        ).forEach { raw ->
            assertEquals("get_time", assertToolCall(raw).toolName, "failed for $raw")
        }
    }

    @Test
    fun `accepts the OpenAI function call shape`() {
        val call = assertToolCall(
            """{"function": {"name": "set_alarm", "arguments": "{\"hour\": 7, \"minute\": 0}"}}""",
        )
        assertEquals("set_alarm", call.toolName)
        assertEquals(7L, call.arguments["hour"])
        assertEquals(0L, call.arguments["minute"])
    }

    @Test
    fun `takes the first call when a model emits an array`() {
        val call = assertToolCall("""[{"tool":"open_app","arguments":{"app_name":"Maps"}}]""")
        assertEquals("open_app", call.toolName)
    }

    @Test
    fun `braces inside a string value do not truncate the object`() {
        // The naive first-brace-to-last-brace approach mangles this one.
        val call = assertToolCall(
            """{"tool":"send_message","arguments":{"body":"see you at {8} :)","contact":"Mom"}}""",
        )
        assertEquals("see you at {8} :)", call.arguments["body"])
        assertEquals("Mom", call.arguments["contact"])
    }

    @Test
    fun `keeps numbers and booleans as typed values`() {
        val call = assertToolCall(
            """{"tool":"set_volume","arguments":{"percent":40,"ratio":0.5,"mute":false}}""",
        )
        assertEquals(40L, call.arguments["percent"])
        assertEquals(0.5, call.arguments["ratio"])
        assertEquals(false, call.arguments["mute"])
    }

    @Test
    fun `carries a spoken preamble when the model supplies one`() {
        val call = assertToolCall(
            """{"tool":"open_app","say":"Opening YouTube","arguments":{"app_name":"YouTube"}}""",
        )
        assertEquals("Opening YouTube", call.preamble)
    }

    @Test
    fun `plain prose becomes a text response, not a tool call`() {
        val response = ToolCallParser.parse("I'm not sure what you mean by that.")
        assertTrue(response is LlmResponse.Text)
        assertEquals("I'm not sure what you mean by that.", (response as LlmResponse.Text).content)
    }

    @Test
    fun `json without a tool name is treated as prose rather than guessed at`() {
        val response = ToolCallParser.parse("""{"thoughts": "the user wants music"}""")
        assertTrue(response is LlmResponse.Text)
    }

    @Test
    fun `malformed json degrades to text instead of throwing`() {
        val response = ToolCallParser.parse("""{"tool": "open_app", "arguments": {"app_name": }""")
        assertTrue(response is LlmResponse.Text)
    }

    @Test
    fun `empty output is handled`() {
        assertEquals("", (ToolCallParser.parse("   ") as LlmResponse.Text).content)
    }

    @Test
    fun `missing arguments become an empty map`() {
        assertTrue(assertToolCall("""{"tool":"get_time"}""").arguments.isEmpty())
    }

    @Test
    fun `extractJsonObject respects nesting and strings`() {
        assertEquals(
            """{"a":{"b":"}"}}""",
            ToolCallParser.extractJsonObject("""noise {"a":{"b":"}"}} trailing"""),
        )
        assertNull(ToolCallParser.extractJsonObject("no braces here"))
        // Unbalanced input must not return a half object.
        assertNull(ToolCallParser.extractJsonObject("""{"a": 1"""))
    }

    private fun assertToolCall(raw: String): LlmResponse.ToolCall {
        val response = ToolCallParser.parse(raw)
        assertTrue(response is LlmResponse.ToolCall) { "expected a tool call, was $response" }
        return response as LlmResponse.ToolCall
    }
}
