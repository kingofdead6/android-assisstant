package com.john.assistant.core.tool

/**
 * Type-safe view over validated tool arguments.
 *
 * Tools never touch a raw `Map<String, Any?>`: by the time they receive a
 * [ToolArguments] every value has already been checked and coerced by
 * [ToolParameters.validate], so these accessors cannot throw on bad model output.
 */
@JvmInline
value class ToolArguments(val values: Map<String, Any?> = emptyMap()) {

    fun has(name: String): Boolean = values[name] != null

    fun string(name: String): String? = values[name] as? String

    fun string(name: String, default: String): String = string(name) ?: default

    fun int(name: String): Int? = (values[name] as? Long)?.toInt()

    fun int(name: String, default: Int): Int = int(name) ?: default

    fun long(name: String): Long? = values[name] as? Long

    fun double(name: String): Double? = when (val value = values[name]) {
        is Double -> value
        is Long -> value.toDouble()
        else -> null
    }

    fun boolean(name: String): Boolean? = values[name] as? Boolean

    fun boolean(name: String, default: Boolean): Boolean = boolean(name) ?: default

    @Suppress("UNCHECKED_CAST")
    fun stringList(name: String): List<String> = (values[name] as? List<String>) ?: emptyList()

    override fun toString(): String =
        values.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }

    companion object {
        val EMPTY = ToolArguments()
    }
}
