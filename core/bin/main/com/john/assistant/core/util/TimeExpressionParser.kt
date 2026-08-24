package com.john.assistant.core.util

/** A wall-clock time of day extracted from speech. */
data class TimeOfDay(val hour: Int, val minute: Int) {
    /** "7:05 AM" — how John reads a time back to the user. */
    fun spoken(): String {
        val suffix = if (hour < 12) "AM" else "PM"
        val display = when {
            hour % 12 == 0 -> 12
            else -> hour % 12
        }
        return if (minute == 0) "$display $suffix" else "$display:${minute.toString().padStart(2, '0')} $suffix"
    }
}

/**
 * Reads times out of spoken English.
 *
 * Speech recognisers hand back "seven thirty", "7:30", "half past seven" and
 * "19 30" for the same utterance, so alarm and reminder commands cannot rely on
 * a single format. Doing this in code rather than asking the model keeps
 * "set an alarm for 7" from ever becoming 7 PM — a class of mistake that a
 * 1B-parameter model makes often and that the user only discovers by oversleeping.
 *
 * Ambiguous bare hours resolve to the *next* occurrence, matching what the
 * platform clock app does.
 */
object TimeExpressionParser {

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "oh" to 0, "zero" to 0,
    )

    private val DIGIT_TIME = Regex("""\b(\d{1,2})[:.\s](\d{2})\s*(am|pm|a\.m\.|p\.m\.)?\b""")
    private val HOUR_ONLY = Regex("""\b(\d{1,2})\s*(am|pm|a\.m\.|p\.m\.|o'clock|oclock)\b""")
    private val BARE_HOUR = Regex("""\b(?:at|for)\s+(\d{1,2})\b""")

    /**
     * @param currentHour used only to disambiguate a bare hour (e.g. "at 7").
     *   Pass the device clock; -1 disables the next-occurrence rule.
     */
    fun parse(text: String, currentHour: Int = -1): TimeOfDay? {
        val lower = text.lowercase()

        if ("midnight" in lower) return TimeOfDay(0, 0)
        if ("noon" in lower || "midday" in lower) return TimeOfDay(12, 0)

        DIGIT_TIME.find(lower)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: return@let
            if (hour > 23 || minute > 59) return@let
            return TimeOfDay(applyMeridiem(hour, match.groupValues[3], lower, currentHour), minute)
        }

        HOUR_ONLY.find(lower)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            if (hour > 23) return@let
            return TimeOfDay(applyMeridiem(hour, match.groupValues[2], lower, currentHour), 0)
        }

        parseWords(lower, currentHour)?.let { return it }

        BARE_HOUR.find(lower)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            if (hour > 23) return@let
            return TimeOfDay(applyMeridiem(hour, "", lower, currentHour), 0)
        }

        return null
    }

    /** "half past seven", "quarter to eight", "seven thirty". */
    private fun parseWords(lower: String, currentHour: Int): TimeOfDay? {
        val words = lower.split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }

        val halfPast = words.indexOf("half")
        if (halfPast >= 0 && words.getOrNull(halfPast + 1) == "past") {
            val hour = NUMBER_WORDS[words.getOrNull(halfPast + 2)] ?: return null
            return TimeOfDay(applyMeridiem(hour, "", lower, currentHour), 30)
        }

        val quarter = words.indexOf("quarter")
        if (quarter >= 0) {
            val relation = words.getOrNull(quarter + 1)
            val hour = NUMBER_WORDS[words.getOrNull(quarter + 2)] ?: return null
            return when (relation) {
                "past" -> TimeOfDay(applyMeridiem(hour, "", lower, currentHour), 15)
                "to" -> {
                    val adjusted = if (hour == 1) 0 else hour - 1
                    TimeOfDay(applyMeridiem(adjusted, "", lower, currentHour), 45)
                }
                else -> null
            }
        }

        // "seven thirty" / "seven oh five"
        for (index in words.indices) {
            val hour = NUMBER_WORDS[words[index]] ?: continue
            if (hour > 12) continue
            val next = words.getOrNull(index + 1)
            val minute = when {
                next == null -> continue
                next == "thirty" -> 30
                next == "fifteen" -> 15
                next == "forty" -> if (words.getOrNull(index + 2) == "five") 45 else 40
                next == "oh" -> NUMBER_WORDS[words.getOrNull(index + 2)] ?: continue
                else -> continue
            }
            return TimeOfDay(applyMeridiem(hour, "", lower, currentHour), minute)
        }

        return null
    }

    private fun applyMeridiem(hour: Int, marker: String, fullText: String, currentHour: Int): Int {
        val explicit = when {
            marker.startsWith("p") -> "pm"
            marker.startsWith("a") -> "am"
            "in the evening" in fullText || "tonight" in fullText || " pm" in fullText -> "pm"
            "in the morning" in fullText || " am" in fullText -> "am"
            "in the afternoon" in fullText -> "pm"
            else -> null
        }

        return when (explicit) {
            "pm" -> if (hour == 12) 12 else (hour % 12) + 12
            "am" -> if (hour == 12) 0 else hour
            else -> disambiguate(hour, currentHour)
        }
    }

    /** No AM/PM given: choose the next time that hour comes round. */
    private fun disambiguate(hour: Int, currentHour: Int): Int {
        if (hour > 12) return hour
        if (currentHour < 0) return hour
        val pmVariant = if (hour == 12) 12 else hour + 12
        return if (hour > currentHour) hour else pmVariant.coerceAtMost(23)
    }
}
