package com.john.assistant.core.assistant

/**
 * Works out which option the user picked, without spending an LLM turn.
 *
 * People answer "which one?" in a handful of predictable ways: they repeat part
 * of the label ("the mobile one"), they use an ordinal ("the second"), or they
 * give a number ("number 3"). Handling those deterministically keeps
 * disambiguation fast and, more importantly, keeps a small model from
 * confidently picking the *wrong* contact number.
 *
 * Returns the index of the chosen option, or null when the answer is not a
 * clear pick — in which case John asks again rather than guessing.
 */
object ChoiceMatcher {

    /**
     * Words that can only be ordinals.
     *
     * Bare numerals are deliberately absent: "the mobile **one**" is a
     * description, not a selection of the first option, and treating it as one
     * picks a phone number the user did not ask for. They are handled by
     * [matchNumeral], which requires the answer to actually look like a number.
     */
    private val ORDINALS = listOf(
        setOf("first", "1st", "former", "top"),
        setOf("second", "2nd", "latter"),
        setOf("third", "3rd"),
        setOf("fourth", "4th"),
        setOf("fifth", "5th"),
    )

    private val NUMERALS = listOf(
        setOf("one", "1"),
        setOf("two", "2"),
        setOf("three", "3"),
        setOf("four", "4"),
        setOf("five", "5"),
    )

    /** Words that carry no selective meaning. */
    private val NOISE = setOf(
        "the", "a", "an", "one", "please", "option", "that", "this", "just", "yes",
        "use", "pick", "choose", "go", "with", "let's", "lets", "do", "it", "number",
        "i", "want", "would", "like", "prefer",
    )

    private val NUMERAL_PREFIXES = setOf("number", "option", "choice")

    fun match(answer: String, options: List<String>): Int? {
        if (options.isEmpty()) return null

        val normalised = answer.lowercase().trim().trim('.', '!', '?', ',')
        if (normalised.isEmpty()) return null

        val words = normalised.split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }

        // 1. The answer is one of the labels, verbatim.
        options.indexOfFirst { it.equals(normalised, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.let { return it }

        // 2. An unambiguous ordinal, bounded by how many options exist.
        ORDINALS.take(options.size).forEachIndexed { index, aliases ->
            if (words.any { it in aliases }) return index
        }

        // 3. Content words that pick out exactly one label.
        matchByContent(words, options)?.let { return it }

        // 4. "number three", or a bare numeral as the whole answer.
        return matchNumeral(words, options.size)
    }

    private fun matchByContent(words: List<String>, options: List<String>): Int? {
        val content = words.filterNot { it in NOISE }
        if (content.isEmpty()) return null

        val matches = options.withIndex().filter { (_, label) ->
            val labelWords = label.lowercase()
                .split(Regex("[^a-z0-9']+"))
                .filter { it.isNotEmpty() && it !in NOISE }
            content.any { word -> labelWords.any { it.startsWith(word) || word.startsWith(it) } }
        }

        // Commit only when the answer singles one option out. "The mobile one"
        // against "Work mobile" and "Personal mobile" is a re-ask, not a coin flip.
        return matches.singleOrNull()?.index
    }

    private fun matchNumeral(words: List<String>, optionCount: Int): Int? {
        val prefixed = words.zipWithNext()
            .firstOrNull { (first, _) -> first in NUMERAL_PREFIXES }
            ?.second
        val candidate = prefixed ?: words.singleOrNull()  ?: return null

        NUMERALS.take(optionCount).forEachIndexed { index, aliases ->
            if (candidate in aliases) return index
        }
        return null
    }
}
