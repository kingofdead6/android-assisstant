package com.john.assistant.core.conversation

/** How an answer to a yes/no question came out. */
enum class Affirmation { YES, NO, UNCLEAR }

/**
 * Reads "yes" and "no" without spending an LLM turn on it.
 *
 * Confirmation answers are the highest-frequency, lowest-ambiguity input John
 * gets, and they gate side-effecting actions. Doing them deterministically
 * means a small model that is having a bad day can never turn "no, don't" into
 * a sent message — and it keeps the confirmation round-trip instant.
 *
 * Anything not clearly affirmative or negative is [Affirmation.UNCLEAR], which
 * the orchestrator treats as "not confirmed".
 */
object AffirmationDetector {

    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "affirmative", "correct",
        "confirm", "confirmed", "do it", "go ahead", "send it", "please do", "sounds good",
        "that's right", "thats right", "right", "aye", "yes please", "definitely",
    )

    private val NO = setOf(
        "no", "nope", "nah", "negative", "cancel", "stop", "don't", "dont", "do not",
        "never mind", "nevermind", "forget it", "abort", "no thanks", "no thank you",
        "wait", "hold on", "not now",
    )

    fun classify(text: String): Affirmation {
        val normalised = text.trim().lowercase().trim('.', '!', '?', ',', ' ')
        if (normalised.isEmpty()) return Affirmation.UNCLEAR

        if (normalised in NO) return Affirmation.NO
        if (normalised in YES) return Affirmation.YES

        // Negation wins on mixed input: "yes but no", "yeah don't do that".
        val words = normalised.split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        if (NO.any { it in normalised && it.contains(' ') }) return Affirmation.NO
        if (words.any { it in NO }) return Affirmation.NO
        if (YES.any { it in normalised && it.contains(' ') }) return Affirmation.YES
        if (words.firstOrNull() in YES) return Affirmation.YES

        return Affirmation.UNCLEAR
    }
}
