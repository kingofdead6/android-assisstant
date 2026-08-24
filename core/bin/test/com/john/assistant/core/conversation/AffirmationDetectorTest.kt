package com.john.assistant.core.conversation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AffirmationDetectorTest {

    @Test
    fun `recognises affirmatives`() {
        listOf("yes", "Yeah", "sure", "ok", "go ahead", "send it", "yes please", "do it")
            .forEach { assertEquals(Affirmation.YES, AffirmationDetector.classify(it), it) }
    }

    @Test
    fun `recognises negatives`() {
        listOf("no", "nope", "cancel", "never mind", "don't", "stop", "no thanks", "forget it")
            .forEach { assertEquals(Affirmation.NO, AffirmationDetector.classify(it), it) }
    }

    @Test
    fun `negation wins over a stray yes`() {
        // "yeah don't" must never send the message.
        assertEquals(Affirmation.NO, AffirmationDetector.classify("yeah, don't do that"))
        assertEquals(Affirmation.NO, AffirmationDetector.classify("yes but no"))
    }

    @Test
    fun `unrelated speech is unclear rather than assumed`() {
        listOf("what's the weather", "", "hmm", "play some music")
            .forEach { assertEquals(Affirmation.UNCLEAR, AffirmationDetector.classify(it), it) }
    }

    @Test
    fun `ignores punctuation and case`() {
        assertEquals(Affirmation.YES, AffirmationDetector.classify("  YES!  "))
        assertEquals(Affirmation.NO, AffirmationDetector.classify("No."))
    }
}
