package com.john.assistant.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One capability John can invoke.
 *
 * A tool is the *only* way the model reaches the device. The model never emits
 * code, shell or Android calls — it emits a tool name plus arguments, which the
 * pipeline validates, permission-checks and confirms before anything runs.
 *
 * Implementations must be:
 *  - **independent** — no tool calls another tool;
 *  - **total** — every failure path returns a [ToolResult], never an exception;
 *  - **honest** — only return [ToolResult.Success] when the action really happened.
 */
interface AssistantTool {

    /** Stable snake_case identifier the model emits, e.g. `open_app`. */
    val name: String

    /** One line, written for the model: what this does and when to pick it. */
    val description: String

    /** Declared inputs. Anything not declared here never reaches [execute]. */
    val parameters: ToolParameters get() = ToolParameters.NONE

    /** Drives the confirmation policy. */
    val riskLevel: RiskLevel get() = RiskLevel.LOW

    /** Checked by the orchestrator before [execute] is called. */
    val requiredPermissions: Set<PermissionKey> get() = emptySet()

    /** False for tools that cannot work offline (see the offline-mode docs). */
    val worksOffline: Boolean get() = true

    /**
     * Example utterances. Used by the prompt builder for few-shot grounding and
     * by the deterministic fallback matcher when no LLM is loaded.
     */
    val examples: List<String> get() = emptyList()

    /**
     * Run the action.
     *
     * Called off the main thread. [arguments] have already been validated
     * against [parameters], so accessors are safe.
     */
    suspend fun execute(arguments: ToolArguments): ToolResult

    /**
     * How John describes this action out loud when asking permission to do it,
     * phrased to slot into "Do you want me to ___?".
     *
     * Tools with a side effect should override this: "send Mom a WhatsApp
     * message saying I'll be home at eight" is a question a user can answer;
     * "run send whatsapp message" is not.
     */
    fun describeAction(arguments: ToolArguments): String = name.replace('_', ' ')

    /** The schema advertised to the model. */
    fun definition(): ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = parameters,
        riskLevel = riskLevel,
    )
}

/** The model-facing description of a tool. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: ToolParameters,
    val riskLevel: RiskLevel = RiskLevel.LOW,
) {
    fun toJsonSchema(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", parameters.toJsonSchema())
    }
}
