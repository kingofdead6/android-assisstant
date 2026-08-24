package com.john.assistant.core.llm.rules

/**
 * A phrase shape mapped to a tool call.
 *
 * Patterns are matched against the *whole* normalised utterance. Ordering in
 * the catalogue is significant: the first match wins, so specific shapes
 * ("play the next song") must be listed before general ones ("play …").
 */
data class IntentPattern(
    val tool: String,
    val regex: Regex,
    /** Turns capture groups into tool arguments. */
    val extract: (MatchResult) -> Map<String, Any?> = { emptyMap() },
) {
    fun match(utterance: String): Map<String, Any?>? =
        regex.find(utterance)?.let(extract)
}

/**
 * Builds a pattern whose named groups become tool arguments.
 *
 * [captures] are *argument* names, which are snake_case. Java's regex engine
 * only accepts alphanumeric group names, so `app_name` is written in the
 * expression as `(?<appname>…)` and mapped back here.
 */
fun pattern(tool: String, expression: String, vararg captures: String): IntentPattern =
    IntentPattern(
        tool = tool,
        regex = Regex(expression, RegexOption.IGNORE_CASE),
        extract = { match ->
            captures.mapNotNull { name ->
                match.groups[name.groupName()]
                    ?.value
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { name to it }
            }.toMap()
        },
    )

/** `app_name` -> `appname`, the form a Java regex group name may take. */
internal fun String.groupName(): String = replace("_", "")
