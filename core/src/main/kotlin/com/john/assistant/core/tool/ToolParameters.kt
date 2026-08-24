package com.john.assistant.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The value shapes a tool parameter is allowed to have. */
enum class ParameterType(val jsonType: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    STRING_LIST("array"),
}

/**
 * One declared input of a tool.
 *
 * The set of parameters a tool declares is the *only* thing the model is
 * allowed to influence. Anything the model sends that is not declared here is
 * dropped before the tool ever sees it (see [ToolParameters.validate]).
 */
data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = false,
    /** When non-empty, the value must be one of these (case-insensitive). */
    val allowedValues: List<String> = emptyList(),
    /** Inclusive bounds for [ParameterType.INTEGER] / [ParameterType.NUMBER]. */
    val min: Double? = null,
    val max: Double? = null,
)

/** The outcome of checking model-supplied arguments against a tool's schema. */
sealed interface ValidationResult {
    /** Arguments were accepted; [arguments] are coerced to their declared types. */
    data class Valid(val arguments: ToolArguments) : ValidationResult

    /** Arguments were rejected. [reason] is safe to speak back to the user. */
    data class Invalid(val reason: String) : ValidationResult
}

/**
 * A tool's full input schema, and the gate that model output must pass through.
 */
data class ToolParameters(val parameters: List<ToolParameter> = emptyList()) {

    operator fun get(name: String): ToolParameter? = parameters.firstOrNull { it.name == name }

    /**
     * Validate and coerce raw arguments produced by the model.
     *
     * Rules, in order:
     *  - unknown keys are discarded (never an error — small models add chatter);
     *  - every required parameter must be present and non-blank;
     *  - values are coerced to the declared type, and rejected if they cannot be;
     *  - [ToolParameter.allowedValues] and numeric bounds are enforced.
     */
    fun validate(raw: Map<String, Any?>): ValidationResult {
        val accepted = LinkedHashMap<String, Any?>(parameters.size)

        for (parameter in parameters) {
            val supplied = raw.entries
                .firstOrNull { it.key.equals(parameter.name, ignoreCase = true) }
                ?.value

            if (supplied == null || (supplied is String && supplied.isBlank())) {
                if (parameter.required) {
                    return ValidationResult.Invalid("I'm missing the ${parameter.name.humanise()}.")
                }
                continue
            }

            when (val coerced = coerce(parameter, supplied)) {
                is Coercion.Failed -> return ValidationResult.Invalid(coerced.reason)
                is Coercion.Ok -> accepted[parameter.name] = coerced.value
            }
        }

        return ValidationResult.Valid(ToolArguments(accepted))
    }

    private sealed interface Coercion {
        data class Ok(val value: Any?) : Coercion
        data class Failed(val reason: String) : Coercion
    }

    private fun coerce(parameter: ToolParameter, value: Any?): Coercion {
        val label = parameter.name.humanise()

        val coerced: Any? = when (parameter.type) {
            ParameterType.STRING -> value.toString().trim()

            ParameterType.INTEGER -> asNumber(value)?.let { number ->
                if (number != Math.floor(number) || number.isInfinite()) {
                    return Coercion.Failed("The $label needs to be a whole number.")
                }
                number.toLong()
            } ?: return Coercion.Failed("The $label needs to be a number.")

            ParameterType.NUMBER -> asNumber(value)
                ?: return Coercion.Failed("The $label needs to be a number.")

            ParameterType.BOOLEAN -> asBoolean(value)
                ?: return Coercion.Failed("The $label needs to be yes or no.")

            ParameterType.STRING_LIST -> when (value) {
                is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                else -> return Coercion.Failed("The $label needs to be a list.")
            }
        }

        if (parameter.allowedValues.isNotEmpty()) {
            val text = coerced.toString()
            val match = parameter.allowedValues.firstOrNull { it.equals(text, ignoreCase = true) }
                ?: return Coercion.Failed(
                    "The $label has to be one of: ${parameter.allowedValues.joinToString(", ")}.",
                )
            return Coercion.Ok(match)
        }

        val numeric = when (coerced) {
            is Long -> coerced.toDouble()
            is Double -> coerced
            else -> null
        }
        if (numeric != null) {
            parameter.min?.let { if (numeric < it) return Coercion.Failed("The $label can't be below ${it.trimZeros()}.") }
            parameter.max?.let { if (numeric > it) return Coercion.Failed("The $label can't be above ${it.trimZeros()}.") }
        }

        return Coercion.Ok(coerced)
    }

    private fun asNumber(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is Boolean -> null
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun asBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> null
        }
        else -> null
    }

    /** The JSON Schema fragment handed to the model for this tool. */
    fun toJsonSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                parameters.forEach { parameter ->
                    put(
                        parameter.name,
                        buildJsonObject {
                            put("type", parameter.type.jsonType)
                            put("description", parameter.description)
                            if (parameter.type == ParameterType.STRING_LIST) {
                                put("items", buildJsonObject { put("type", "string") })
                            }
                            if (parameter.allowedValues.isNotEmpty()) {
                                put(
                                    "enum",
                                    JsonArray(parameter.allowedValues.map { JsonPrimitive(it) }),
                                )
                            }
                            parameter.min?.let { put("minimum", it) }
                            parameter.max?.let { put("maximum", it) }
                        },
                    )
                }
            },
        )
        put(
            "required",
            buildJsonArray {
                parameters.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
            },
        )
    }

    companion object {
        val NONE = ToolParameters()

        fun of(vararg parameters: ToolParameter) = ToolParameters(parameters.toList())
    }
}

private fun String.humanise(): String = replace('_', ' ')

private fun Double.trimZeros(): String =
    if (this == Math.floor(this) && !isInfinite()) toLong().toString() else toString()
