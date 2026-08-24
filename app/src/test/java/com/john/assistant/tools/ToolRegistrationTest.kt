package com.john.assistant.tools

import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolRegistry
import com.john.assistant.core.fake.FakeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants every tool must hold, checked against the registry rather than
 * one tool at a time.
 *
 * These are the properties that keep the model from being able to talk John
 * into something: a name it can address, a schema it cannot exceed, and a risk
 * level the confirmation policy can act on. A tool added without them would
 * pass code review and fail here.
 *
 * The real registry is assembled by Hilt, which needs a device, so this suite
 * checks the *contract* using representative tools. `ToolRegistryTest` in
 * `:core` covers resolution behaviour, and an instrumentation test asserts the
 * production registry satisfies the same invariants on-device.
 */
class ToolRegistrationTest {

    @Test
    fun `tool names are addressable by the model`() {
        // The registry rejects anything that is not lower_snake_case, because a
        // name the model cannot reproduce is a tool that can never be called.
        val registry = ToolRegistry(
            listOf(FakeTool("open_app"), FakeTool("get_battery"), FakeTool("send_message")),
        )

        registry.all().forEach { tool ->
            assertTrue(
                "${tool.name} is not lower_snake_case",
                tool.name.matches(Regex("^[a-z][a-z0-9_]*$")),
            )
        }
    }

    @Test
    fun `every declared required parameter is enforced`() {
        val tool = FakeTool(
            name = "send_message",
            parameters = com.john.assistant.core.tool.ToolParameters.of(
                com.john.assistant.core.tool.ToolParameter(
                    name = "body",
                    type = com.john.assistant.core.tool.ParameterType.STRING,
                    description = "Message text",
                    required = true,
                ),
            ),
        )

        val missing = tool.parameters.validate(emptyMap())
        assertTrue(missing is com.john.assistant.core.tool.ValidationResult.Invalid)
    }

    @Test
    fun `describeAction produces something a user can answer yes or no to`() {
        val tool = FakeTool(
            name = "send_message",
            riskLevel = RiskLevel.MEDIUM,
            action = "send Mom a message saying I'll be late",
        )

        val question = "Do you want me to ${tool.describeAction(ToolArguments.EMPTY)}?"
        assertEquals("Do you want me to send Mom a message saying I'll be late?", question)
    }

    @Test
    fun `disabling a tool removes it from the model's schema`() {
        val registry = ToolRegistry(listOf(FakeTool("open_app"), FakeTool("send_message")))
        registry.setEnabled("send_message", false)

        val advertised = registry.toJsonSchema().toString()
        assertTrue(advertised.contains("open_app"))
        assertTrue("a disabled tool is still advertised", !advertised.contains("send_message"))
    }
}
