package com.john.assistant.core.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolParametersTest {

    private val schema = ToolParameters.of(
        ToolParameter("app_name", ParameterType.STRING, "App", required = true),
        ToolParameter("hour", ParameterType.INTEGER, "Hour", min = 0.0, max = 23.0),
        ToolParameter("confirm", ParameterType.BOOLEAN, "Confirm"),
        ToolParameter("channel", ParameterType.STRING, "Channel", allowedValues = listOf("sms", "whatsapp")),
        ToolParameter("tags", ParameterType.STRING_LIST, "Tags"),
    )

    @Test
    fun `accepts a well-formed call`() {
        val result = schema.validate(
            mapOf("app_name" to "YouTube", "hour" to 7, "confirm" to true, "tags" to listOf("a", "b")),
        )

        val valid = assertValid(result)
        assertEquals("YouTube", valid.string("app_name"))
        assertEquals(7, valid.int("hour"))
        assertEquals(true, valid.boolean("confirm"))
        assertEquals(listOf("a", "b"), valid.stringList("tags"))
    }

    @Test
    fun `drops arguments the tool never declared`() {
        // Small models add commentary keys. They must not reach the tool, and
        // must not fail the call either.
        val result = schema.validate(mapOf("app_name" to "Spotify", "reason" to "user asked", "sudo" to true))

        val valid = assertValid(result)
        assertEquals(setOf("app_name"), valid.values.keys)
    }

    @Test
    fun `rejects a missing required argument`() {
        val result = schema.validate(mapOf("hour" to 7))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `treats a blank required string as missing`() {
        val result = schema.validate(mapOf("app_name" to "   "))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `coerces numeric strings that models emit`() {
        val valid = assertValid(schema.validate(mapOf("app_name" to "Clock", "hour" to "7")))
        assertEquals(7, valid.int("hour"))
    }

    @Test
    fun `rejects a fractional value for an integer parameter`() {
        val result = schema.validate(mapOf("app_name" to "Clock", "hour" to 7.5))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `enforces numeric bounds`() {
        assertTrue(schema.validate(mapOf("app_name" to "Clock", "hour" to 25)) is ValidationResult.Invalid)
        assertTrue(schema.validate(mapOf("app_name" to "Clock", "hour" to -1)) is ValidationResult.Invalid)
    }

    @Test
    fun `enforces the allowed value list and normalises case`() {
        val valid = assertValid(schema.validate(mapOf("app_name" to "x", "channel" to "WhatsApp")))
        assertEquals("whatsapp", valid.string("channel"))

        assertTrue(
            schema.validate(mapOf("app_name" to "x", "channel" to "carrier_pigeon")) is ValidationResult.Invalid,
        )
    }

    @Test
    fun `splits a comma separated string into a list`() {
        val valid = assertValid(schema.validate(mapOf("app_name" to "x", "tags" to "work, urgent")))
        assertEquals(listOf("work", "urgent"), valid.stringList("tags"))
    }

    @Test
    fun `matches argument names case insensitively`() {
        val valid = assertValid(schema.validate(mapOf("App_Name" to "Maps")))
        assertEquals("Maps", valid.string("app_name"))
    }

    @Test
    fun `omitted optional arguments read back as null`() {
        val valid = assertValid(schema.validate(mapOf("app_name" to "Maps")))
        assertNull(valid.int("hour"))
        assertEquals(5, valid.int("hour", default = 5))
    }

    @Test
    fun `json schema advertises types required fields and enums`() {
        val json = schema.toJsonSchema().toString()
        assertTrue(json.contains("\"app_name\""))
        assertTrue(json.contains("\"integer\""))
        assertTrue(json.contains("\"required\":[\"app_name\"]"))
        assertTrue(json.contains("\"enum\":[\"sms\",\"whatsapp\"]"))
    }

    private fun assertValid(result: ValidationResult): ToolArguments {
        assertTrue(result is ValidationResult.Valid) { "expected valid, was $result" }
        return (result as ValidationResult.Valid).arguments
    }
}
