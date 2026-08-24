package com.john.assistant.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TimeExpressionParserTest {

    @Test
    fun `reads explicit meridiem`() {
        assertEquals(TimeOfDay(7, 0), TimeExpressionParser.parse("set an alarm for 7 am"))
        assertEquals(TimeOfDay(20, 0), TimeExpressionParser.parse("remind me at 8 pm"))
        assertEquals(TimeOfDay(0, 0), TimeExpressionParser.parse("wake me at 12 am"))
        assertEquals(TimeOfDay(12, 0), TimeExpressionParser.parse("lunch at 12 pm"))
    }

    @Test
    fun `reads digit times`() {
        assertEquals(TimeOfDay(7, 30), TimeExpressionParser.parse("alarm for 7:30 am"))
        assertEquals(TimeOfDay(19, 45), TimeExpressionParser.parse("at 19:45"))
        assertEquals(TimeOfDay(6, 5), TimeExpressionParser.parse("6:05 am please"))
    }

    @Test
    fun `reads spoken numbers`() {
        assertEquals(TimeOfDay(7, 30), TimeExpressionParser.parse("half past seven in the morning"))
        assertEquals(TimeOfDay(19, 15), TimeExpressionParser.parse("quarter past seven in the evening"))
        assertEquals(TimeOfDay(7, 45), TimeExpressionParser.parse("quarter to eight in the morning"))
        assertEquals(TimeOfDay(9, 30), TimeExpressionParser.parse("nine thirty am"))
    }

    @Test
    fun `handles noon and midnight`() {
        assertEquals(TimeOfDay(12, 0), TimeExpressionParser.parse("remind me at noon"))
        assertEquals(TimeOfDay(0, 0), TimeExpressionParser.parse("at midnight"))
    }

    @Test
    fun `a bare hour resolves to the next time it comes round`() {
        // Said at 09:00, "at 7" means this evening, not eight hours ago.
        assertEquals(TimeOfDay(19, 0), TimeExpressionParser.parse("remind me at 7", currentHour = 9))
        // Said at 05:00, "at 7" means this morning.
        assertEquals(TimeOfDay(7, 0), TimeExpressionParser.parse("remind me at 7", currentHour = 5))
    }

    @Test
    fun `explicit meridiem beats the next-occurrence rule`() {
        assertEquals(TimeOfDay(7, 0), TimeExpressionParser.parse("alarm at 7 am", currentHour = 9))
    }

    @Test
    fun `rejects impossible and absent times`() {
        assertNull(TimeExpressionParser.parse("set an alarm"))
        assertNull(TimeExpressionParser.parse("at 25:99"))
        assertNull(TimeExpressionParser.parse("play some music"))
    }

    @Test
    fun `spoken form reads back correctly`() {
        assertEquals("7 AM", TimeOfDay(7, 0).spoken())
        assertEquals("7:05 PM", TimeOfDay(19, 5).spoken())
        assertEquals("12 AM", TimeOfDay(0, 0).spoken())
        assertEquals("12 PM", TimeOfDay(12, 0).spoken())
    }
}
