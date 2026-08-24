package com.john.assistant.core.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChoiceMatcherTest {

    private val numbers = listOf("Mobile", "Home", "Work")
    private val apps = listOf("YouTube", "YouTube Music")
    private val ambiguous = listOf("Work mobile", "Personal mobile")

    @Test
    fun `matches an exact label`() {
        assertEquals(1, ChoiceMatcher.match("Home", numbers))
    }

    @Test
    fun `matches a partial label with filler words`() {
        assertEquals(0, ChoiceMatcher.match("the mobile one", numbers))
        assertEquals(2, ChoiceMatcher.match("use work please", numbers))
    }

    @Test
    fun `matches ordinals and numbers`() {
        assertEquals(0, ChoiceMatcher.match("the first", numbers))
        assertEquals(1, ChoiceMatcher.match("second", numbers))
        assertEquals(2, ChoiceMatcher.match("number 3", numbers))
    }

    @Test
    fun `refuses to guess when the answer fits two options`() {
        // "mobile" describes both. Picking either could call the wrong number.
        assertNull(ChoiceMatcher.match("the mobile one", ambiguous))
        assertEquals(0, ChoiceMatcher.match("work", ambiguous))
    }

    @Test
    fun `an exact label wins even when another label contains it`() {
        // John asked "YouTube or YouTube Music?" — answering "YouTube" is a pick,
        // not an ambiguity, because it matches one label in full.
        assertEquals(0, ChoiceMatcher.match("YouTube", apps))
        assertEquals(1, ChoiceMatcher.match("the music one", apps))
    }

    @Test
    fun `an unrelated answer is not a choice`() {
        assertNull(ChoiceMatcher.match("what's the weather", numbers))
        assertNull(ChoiceMatcher.match("", numbers))
    }

    @Test
    fun `an ordinal beyond the option count is not matched`() {
        assertNull(ChoiceMatcher.match("the fourth", listOf("A", "B")))
    }
}
