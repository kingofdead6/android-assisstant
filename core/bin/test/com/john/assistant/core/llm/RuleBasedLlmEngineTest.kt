package com.john.assistant.core.llm

import com.john.assistant.core.llm.rules.RuleBasedLlmEngine
import com.john.assistant.core.tool.ToolDefinition
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.util.TimeSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleBasedLlmEngineTest {

    private val toolNames = listOf(
        "open_app", "search_google", "play_media", "pause_media", "next_track",
        "increase_volume", "decrease_volume", "set_volume", "get_battery", "get_time",
        "get_date", "set_alarm", "create_reminder", "make_phone_call", "send_message",
        "read_notifications", "toggle_flashlight", "read_calendar", "remember_fact",
    )

    private val tools = toolNames.map { ToolDefinition(it, it, ToolParameters.NONE) }

    // Fixed at 09:00 UTC so bare-hour resolution is deterministic.
    private val engine = RuleBasedLlmEngine(TimeSource { 9 * 3_600_000L })

    private suspend fun ask(utterance: String): LlmResponse =
        engine.generate(listOf(ChatMessage.user(utterance)), tools)

    private suspend fun call(utterance: String): LlmResponse.ToolCall {
        val response = ask(utterance)
        assertTrue(response is LlmResponse.ToolCall) { "'$utterance' gave $response" }
        return response as LlmResponse.ToolCall
    }

    @Test
    fun `opens apps`() = runTest {
        assertEquals("YouTube", call("Hey John, open YouTube").arguments["app_name"])
        assertEquals("Spotify", call("open Spotify").arguments["app_name"])
        assertEquals("Google Maps", call("launch the Google Maps app").arguments["app_name"])
    }

    @Test
    fun `searches without being mistaken for opening Google`() = runTest {
        val search = call("search Google for the best restaurants in Batna")
        assertEquals("search_google", search.toolName)
        assertEquals("the best restaurants in Batna", search.arguments["query"])
    }

    @Test
    fun `controls media`() = runTest {
        assertEquals("pause_media", call("pause the music").toolName)
        assertEquals("next_track", call("play the next song").toolName)
        assertEquals("play_media", call("play some music").toolName)
        // "play some music" is a request to play, not a search for "music".
        assertTrue(call("play some music").arguments.isEmpty())
        assertEquals("Bohemian Rhapsody", call("play Bohemian Rhapsody").arguments["query"])
    }

    @Test
    fun `routes a media request naming an app`() = runTest {
        val call = call("play jazz on Spotify")
        assertEquals("play_media", call.toolName)
        assertEquals("jazz", call.arguments["query"])
        assertEquals("Spotify", call.arguments["app_name"])
    }

    @Test
    fun `controls volume`() = runTest {
        assertEquals("increase_volume", call("turn the volume up").toolName)
        assertEquals("decrease_volume", call("lower the volume").toolName)
        assertEquals(60L, call("set the volume to 60").arguments["percent"])
        assertEquals(0L, call("mute").arguments["percent"])
    }

    @Test
    fun `answers system questions`() = runTest {
        assertEquals("get_battery", call("what's my battery percentage").toolName)
        assertEquals("get_time", call("what time is it").toolName)
        assertEquals("get_date", call("what's the date").toolName)
        assertEquals("read_notifications", call("read my notifications").toolName)
    }

    @Test
    fun `sets an alarm with the spoken time`() = runTest {
        val alarm = call("set an alarm for 7 am")
        assertEquals("set_alarm", alarm.toolName)
        assertEquals(7L, alarm.arguments["hour"])
        assertEquals(0L, alarm.arguments["minute"])
    }

    @Test
    fun `creates a reminder with both the text and the time`() = runTest {
        val reminder = call("remind me to study at 8 pm")
        assertEquals("create_reminder", reminder.toolName)
        assertEquals("study", reminder.arguments["text"])
        assertEquals(20L, reminder.arguments["hour"])
    }

    @Test
    fun `places calls and sends messages`() = runTest {
        assertEquals("Mom", call("call Mom").arguments["contact"])

        val message = call("send Mom a WhatsApp message saying I'll be home soon")
        assertEquals("send_message", message.toolName)
        assertEquals("Mom", message.arguments["contact"])
        assertEquals("whatsapp", message.arguments["channel"])
        assertEquals("I'll be home soon", message.arguments["body"])
    }

    @Test
    fun `strips the wake word and politeness`() = runTest {
        assertEquals("get_battery", call("Hey John, could you tell me my battery").toolName)
        assertEquals("pause_media", call("Okay John, pause").toolName)
    }

    @Test
    fun `only offers tools that are registered`() = runTest {
        val response = engine.generate(
            listOf(ChatMessage.user("call Mom")),
            // make_phone_call is not on the list — the user disabled it.
            tools.filterNot { it.name == "make_phone_call" },
        )
        assertTrue(response is LlmResponse.Text)
    }

    @Test
    fun `an unrecognised phrase is not forced onto the nearest tool`() = runTest {
        val response = ask("tell my mother I'll be late because the bus broke down again")
        assertTrue(response is LlmResponse.Text) { "should not guess, got $response" }
        assertTrue((response as LlmResponse.Text).content.contains("language model"))
    }

    @Test
    fun `empty input is handled`() = runTest {
        assertTrue(ask("   ") is LlmResponse.Text)
    }
}
