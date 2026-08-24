package com.john.assistant.core.conversation

import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationContextManagerTest {

    private fun turn(index: Int) = ConversationTurn(
        timestampMillis = index * 1000L,
        userText = "user $index",
        assistantText = "john $index",
    )

    @Test
    fun `keeps a bounded window of turns`() {
        val context = ConversationContextManager(maxTurns = 3)
        (1..5).forEach { context.record(turn(it)) }

        assertEquals(3, context.history.size)
        assertEquals("user 3", context.history.first().userText)
        assertEquals("user 5", context.history.last().userText)
    }

    @Test
    fun `carries the opened app forward as a referent`() {
        val context = ConversationContextManager()
        context.noteToolSuccess(
            "open_app",
            ToolResult.Success(
                "YouTube is open.",
                mapOf("app_label" to "YouTube", "package" to "com.google.android.youtube"),
            ),
        )

        assertEquals("YouTube", context.focus.lastAppLabel)
        assertEquals("com.google.android.youtube", context.focus.lastAppPackage)
        assertTrue(context.focus.describe().any { it.contains("YouTube") })
    }

    @Test
    fun `does not carry sensitive result data into the next prompt`() {
        val context = ConversationContextManager()
        context.noteToolSuccess(
            "read_notifications",
            ToolResult.Success(
                "You have one notification.",
                mapOf(
                    "notification_body" to "Your verification code is 448211",
                    "phone_number" to "+213600000000",
                    "track" to "Bohemian Rhapsody",
                ),
            ),
        )

        val described = context.focus.describe().joinToString(" ")
        assertFalse(described.contains("448211"))
        assertFalse(described.contains("+213600000000"))
        // The allow-listed referent still comes through.
        assertTrue(described.contains("Bohemian Rhapsody"))
    }

    @Test
    fun `converts history into alternating chat messages`() {
        val context = ConversationContextManager()
        (1..2).forEach { context.record(turn(it)) }

        val messages = context.toChatMessages()
        assertEquals(4, messages.size)
        assertEquals("user 1", messages[0].content)
        assertEquals("john 1", messages[1].content)
    }

    @Test
    fun `tracks and clears a pending action`() {
        val context = ConversationContextManager()
        context.awaiting(
            PendingAction.Confirmation("send_sms", ToolArguments.EMPTY, "Send it?", "text mom"),
        )
        assertTrue(context.pending is PendingAction.Confirmation)

        context.clearPending()
        assertNull(context.pending)
    }

    @Test
    fun `clear wipes turns focus and pending state`() {
        val context = ConversationContextManager()
        context.record(turn(1))
        context.noteToolSuccess("open_app", ToolResult.Success("ok", mapOf("app_label" to "Maps")))
        context.awaiting(PendingAction.Confirmation("x", ToolArguments.EMPTY, "?", "u"))

        context.clear()

        assertTrue(context.history.isEmpty())
        assertTrue(context.focus.isEmpty)
        assertNull(context.pending)
    }

    @Test
    fun `expire drops turns older than the retention window`() {
        val context = ConversationContextManager()
        context.record(ConversationTurn(timestampMillis = 0, userText = "old", assistantText = ""))
        context.record(ConversationTurn(timestampMillis = 9_000, userText = "new", assistantText = ""))

        context.expire(nowMillis = 10_000, olderThanMillis = 5_000)

        assertEquals(listOf("new"), context.history.map { it.userText })
    }
}
