package com.john.assistant.core.tool

import com.john.assistant.core.fake.FakeTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolRegistryTest {

    @Test
    fun `resolves a registered tool`() {
        val registry = ToolRegistry(listOf(FakeTool("open_app")))
        assertTrue(registry.resolve("open_app") is ToolLookup.Found)
    }

    @Test
    fun `normalises the spelling models produce`() {
        val registry = ToolRegistry(listOf(FakeTool("open_app")))

        listOf("Open_App", " open_app ", "open-app", "open app").forEach { spelling ->
            assertTrue(registry.resolve(spelling) is ToolLookup.Found) { "failed for '$spelling'" }
        }
    }

    @Test
    fun `an unregistered name is unknown, never a near match`() {
        val registry = ToolRegistry(listOf(FakeTool("open_app")))

        // "open_apps" is one character away from a real tool. Resolving it to
        // open_app would mean a hallucination silently becoming an action.
        assertTrue(registry.resolve("open_apps") is ToolLookup.Unknown)
        assertTrue(registry.resolve("delete_everything") is ToolLookup.Unknown)
    }

    @Test
    fun `a disabled tool is reported as disabled and hidden from the model`() {
        val registry = ToolRegistry(listOf(FakeTool("open_app"), FakeTool("send_sms")))
        registry.setEnabled("send_sms", false)

        assertTrue(registry.resolve("send_sms") is ToolLookup.Disabled)
        assertFalse(registry.isEnabled("send_sms"))
        assertEquals(listOf("open_app"), registry.definitions().map { it.name })
    }

    @Test
    fun `enableOnly restricts the registry to a whitelist`() {
        val registry = ToolRegistry(listOf(FakeTool("a_tool"), FakeTool("b_tool"), FakeTool("c_tool")))
        registry.enableOnly(setOf("b_tool"))

        assertEquals(listOf("b_tool"), registry.available().map { it.name })
        assertEquals(3, registry.size)
    }

    @Test
    fun `rejects a duplicate registration`() {
        val registry = ToolRegistry(listOf(FakeTool("open_app")))
        assertThrows<IllegalArgumentException> { registry.register(FakeTool("open_app")) }
    }

    @Test
    fun `rejects a name that is not lower snake case`() {
        assertThrows<IllegalArgumentException> { ToolRegistry(listOf(FakeTool("OpenApp"))) }
        assertThrows<IllegalArgumentException> { ToolRegistry(listOf(FakeTool("open-app"))) }
    }

    @Test
    fun `lists tools that need a connection`() {
        val registry = ToolRegistry(
            listOf(FakeTool("get_battery"), FakeTool("github_notifications", worksOffline = false)),
        )
        assertEquals(listOf("github_notifications"), registry.onlineOnly().map { it.name })
    }
}
