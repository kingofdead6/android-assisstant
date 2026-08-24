package com.john.assistant.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The set of actions John is allowed to take, right now.
 *
 * The registry is the security boundary: a tool the model names but that is not
 * registered simply does not exist, and there is no path from model output to
 * execution that bypasses [resolve].
 *
 * Registration is done once at startup by dependency injection. Tools can also
 * be disabled at runtime (a user switching a capability off in settings), which
 * removes them from both execution and the schema shown to the model — so the
 * model stops suggesting things the user has turned off.
 */
class ToolRegistry(tools: Iterable<AssistantTool> = emptyList()) {

    private val tools = LinkedHashMap<String, AssistantTool>()
    private val disabled = LinkedHashSet<String>()

    init {
        tools.forEach(::register)
    }

    fun register(tool: AssistantTool) {
        require(tool.name.matches(NAME_PATTERN)) {
            "Tool name '${tool.name}' must be lower_snake_case."
        }
        require(!tools.containsKey(tool.name)) { "Duplicate tool name '${tool.name}'." }
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
        disabled.remove(name)
    }

    /** Tools available to the model and to execution. */
    fun available(): List<AssistantTool> = tools.values.filter { it.name !in disabled }

    fun all(): List<AssistantTool> = tools.values.toList()

    fun isEnabled(name: String): Boolean = tools.containsKey(name) && name !in disabled

    fun setEnabled(name: String, enabled: Boolean) {
        if (!tools.containsKey(name)) return
        if (enabled) disabled.remove(name) else disabled.add(name)
    }

    /** Restrict the registry to the named tools; everything else is disabled. */
    fun enableOnly(names: Set<String>) {
        disabled.clear()
        tools.keys.filterNot { it in names }.forEach { disabled.add(it) }
    }

    /**
     * Look up a tool the model asked for.
     *
     * Unknown and disabled names are deliberately indistinguishable to the
     * caller's *user-facing* message, but distinct in the returned type so the
     * orchestrator can log the difference.
     */
    fun resolve(name: String): ToolLookup {
        val normalised = name.trim().lowercase().replace(' ', '_').replace('-', '_')
        val tool = tools[normalised] ?: return ToolLookup.Unknown(name)
        if (normalised in disabled) return ToolLookup.Disabled(tool)
        return ToolLookup.Found(tool)
    }

    fun definitions(): List<ToolDefinition> = available().map { it.definition() }

    /** The tool list rendered as JSON Schema for the system prompt. */
    fun toJsonSchema(): JsonArray = JsonArray(definitions().map { it.toJsonSchema() })

    fun schemaObjects(): List<JsonObject> = definitions().map { it.toJsonSchema() }

    /** Tools that cannot run without a connection; used by offline mode. */
    fun onlineOnly(): List<AssistantTool> = available().filterNot { it.worksOffline }

    val size: Int get() = tools.size

    private companion object {
        val NAME_PATTERN = Regex("^[a-z][a-z0-9_]*$")
    }
}

/** Result of looking a tool name up in the registry. */
sealed interface ToolLookup {
    data class Found(val tool: AssistantTool) : ToolLookup
    data class Disabled(val tool: AssistantTool) : ToolLookup
    data class Unknown(val requestedName: String) : ToolLookup
}
