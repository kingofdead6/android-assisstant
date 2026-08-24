package com.john.assistant.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Turns whatever a local model actually produced into a [LlmResponse].
 *
 * Small on-device models are far less obedient than hosted ones: they wrap JSON
 * in prose, fence it in markdown, rename `arguments` to `args`, emit the
 * OpenAI function-call shape, or double-encode the arguments as a string. A
 * strict `Json.decodeFromString` would reject most real output, so this parser
 * is deliberately forgiving about *shape* while staying strict about *content* —
 * it never invents a tool name and never guesses arguments. Anything it cannot
 * confidently read comes back as [LlmResponse.Text], which is spoken rather
 * than executed.
 */
object ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val TOOL_NAME_KEYS = listOf("tool", "tool_name", "name", "action", "function")
    private val ARGUMENT_KEYS = listOf("arguments", "args", "parameters", "params", "input")
    private val PREAMBLE_KEYS = listOf("say", "speech", "reply", "message", "preamble")

    /** Strips ```json fences and surrounding chatter, then reads the first object. */
    fun parse(raw: String): LlmResponse {
        val text = raw.trim()
        if (text.isEmpty()) return LlmResponse.Text("")

        val candidate = extractJsonObject(text) ?: return LlmResponse.Text(stripFences(text))
        val element = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            ?: return LlmResponse.Text(stripFences(text))

        return fromJson(element) ?: LlmResponse.Text(stripFences(text))
    }

    /** Reads a tool call out of an already-parsed element, if there is one. */
    fun fromJson(element: JsonElement): LlmResponse? {
        val obj = when (element) {
            is JsonObject -> element
            // Some models emit a one-element array of calls. Take the first;
            // John runs one tool per turn by design.
            is JsonArray -> element.firstOrNull() as? JsonObject ?: return null
            else -> return null
        }

        // OpenAI-style nesting: {"function": {"name": ..., "arguments": "..."}}
        (obj["function"] as? JsonObject)?.let { nested ->
            readCall(nested, fallbackPreamble = obj.stringOrNull(PREAMBLE_KEYS))?.let { return it }
        }

        return readCall(obj, fallbackPreamble = null)
    }

    private fun readCall(obj: JsonObject, fallbackPreamble: String?): LlmResponse? {
        val nameElement = TOOL_NAME_KEYS.firstNotNullOfOrNull { obj[it] } ?: return null
        val name = when (nameElement) {
            is JsonPrimitive -> nameElement.contentOrEmpty()
            // {"tool": {"name": "..."}}
            is JsonObject -> nameElement.stringOrNull(listOf("name")) ?: return null
            else -> return null
        }
        if (name.isBlank()) return null

        val argumentsElement = ARGUMENT_KEYS.firstNotNullOfOrNull { obj[it] }
        val arguments = readArguments(argumentsElement)

        val preamble = obj.stringOrNull(PREAMBLE_KEYS) ?: fallbackPreamble

        return LlmResponse.ToolCall(
            toolName = name.trim(),
            arguments = arguments,
            preamble = preamble?.takeIf { it.isNotBlank() },
        )
    }

    private fun readArguments(element: JsonElement?): Map<String, Any?> = when (element) {
        null, JsonNull -> emptyMap()
        is JsonObject -> element.mapValues { (_, value) -> value.toKotlin() }
        // Double-encoded arguments: "{\"app_name\":\"YouTube\"}"
        is JsonPrimitive -> {
            val inner = element.contentOrEmpty().trim()
            if (inner.startsWith("{")) {
                runCatching { json.parseToJsonElement(inner) }
                    .getOrNull()
                    ?.let { (it as? JsonObject)?.mapValues { entry -> entry.value.toKotlin() } }
                    .orEmpty()
            } else {
                emptyMap()
            }
        }
        else -> emptyMap()
    }

    /**
     * Finds the first *balanced* JSON object, ignoring braces inside strings.
     *
     * A naive `indexOf('{') .. lastIndexOf('}')` breaks the moment a model says
     * something like: Sure! {"tool": "send_sms", "arguments": {"body": "see you at {8}"}}
     */
    internal fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val ch = text[index]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun stripFences(text: String): String = text
        .replace(FENCE_PATTERN, "")
        .trim()

    private val FENCE_PATTERN = Regex("```[a-zA-Z]*\\n?|```")

    private fun JsonObject.stringOrNull(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }

    private fun JsonPrimitive.contentOrEmpty(): String = if (this is JsonNull) "" else content

    /** Maps JSON scalars onto the Kotlin types [ToolParameters] validates against. */
    private fun JsonElement.toKotlin(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
        is JsonArray -> map { it.toKotlin() }
        is JsonObject -> mapValues { it.value.toKotlin() }
    }
}
