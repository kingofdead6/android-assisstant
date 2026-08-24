package com.john.assistant.core.assistant

import com.john.assistant.core.conversation.ConversationContextManager
import com.john.assistant.core.conversation.TurnOutcome
import com.john.assistant.core.fake.FakeEnvironment
import com.john.assistant.core.fake.FakeLlmEngine
import com.john.assistant.core.fake.FakeMemoryStore
import com.john.assistant.core.fake.FakePermissionGate
import com.john.assistant.core.fake.FakeTool
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.memory.MemoryEntry
import com.john.assistant.core.policy.ConfirmationPolicy
import com.john.assistant.core.tool.ClarificationOption
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolRegistry
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.core.util.TimeSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssistantOrchestratorTest {

    private val llm = FakeLlmEngine()
    private val context = ConversationContextManager()
    private val permissions = FakePermissionGate()
    private val environment = FakeEnvironment()
    private val memory = FakeMemoryStore()

    private fun orchestrator(
        tools: List<FakeTool>,
        config: AssistantConfig = AssistantConfig(),
    ) = AssistantOrchestrator(
        registry = ToolRegistry(tools),
        llm = llm,
        context = context,
        configProvider = AssistantConfigProvider.fixed(config),
        permissions = permissions,
        environment = environment,
        memory = memory,
        timeSource = TimeSource { 1_700_000_000_000 },
    )

    // ------------------------------------------------------------ happy path

    @Test
    fun `a low risk tool call runs without asking`() = runTest {
        val tool = FakeTool(
            name = "open_app",
            parameters = ToolParameters.of(ToolParameter("app_name", ParameterType.STRING, "App", required = true)),
            result = ToolResult.Success("YouTube is open."),
        )
        llm.enqueueToolCall("open_app", mapOf("app_name" to "YouTube"))

        val events = orchestrator(listOf(tool)).handle("open YouTube").toList()

        assertEquals("YouTube", tool.invocations.single().string("app_name"))
        assertTrue(events.any { it is AssistantEvent.Executing })
        assertEquals("YouTube is open.", events.reply())
        assertEquals(AssistantEvent.Done, events.last())
    }

    @Test
    fun `the answer pseudo tool speaks without touching the registry`() = runTest {
        val tool = FakeTool("open_app")
        llm.enqueueToolCall("answer", mapOf("text" to "It's a lovely evening."))

        val events = orchestrator(listOf(tool)).handle("how are you").toList()

        assertEquals("It's a lovely evening.", events.reply())
        assertTrue(tool.invocations.isEmpty())
    }

    // ------------------------------------------------------------- the gates

    @Test
    fun `a hallucinated tool name is refused, not fuzzy matched`() = runTest {
        val tool = FakeTool("open_app")
        llm.enqueueToolCall("wipe_device", mapOf("confirm" to true))

        val events = orchestrator(listOf(tool)).handle("do the thing").toList()

        assertTrue(tool.invocations.isEmpty())
        assertTrue(events.none { it is AssistantEvent.Executing })
        assertEquals("I can't do that one yet.", events.reply())
    }

    @Test
    fun `a disabled tool is not executed`() = runTest {
        val tool = FakeTool("send_sms", riskLevel = RiskLevel.LOW)
        val registry = ToolRegistry(listOf(tool)).apply { setEnabled("send_sms", false) }
        llm.enqueueToolCall("send_sms", mapOf("body" to "hi"))

        val subject = AssistantOrchestrator(
            registry = registry,
            llm = llm,
            context = context,
            configProvider = AssistantConfigProvider.fixed(),
        )
        val events = subject.handle("text mom").toList()

        assertTrue(tool.invocations.isEmpty())
        assertTrue(events.reply()!!.contains("switched off"))
    }

    @Test
    fun `invalid arguments stop the call before execution`() = runTest {
        val tool = FakeTool(
            name = "set_alarm",
            parameters = ToolParameters.of(
                ToolParameter("hour", ParameterType.INTEGER, "Hour", required = true, min = 0.0, max = 23.0),
            ),
        )
        llm.enqueueToolCall("set_alarm", mapOf("hour" to 99))

        val events = orchestrator(listOf(tool)).handle("set an alarm").toList()

        assertTrue(tool.invocations.isEmpty())
        assertTrue(events.reply()!!.contains("can't be above 23"))
    }

    @Test
    fun `a missing permission halts the tool and reports which one`() = runTest {
        val tool = FakeTool("make_phone_call", requiredPermissions = setOf(PermissionKey.PHONE_CALL))
        llm.enqueueToolCall("make_phone_call", mapOf("contact" to "Mom"))

        val events = orchestrator(listOf(tool)).handle("call Mom").toList()

        assertTrue(tool.invocations.isEmpty())
        val needed = events.filterIsInstance<AssistantEvent.PermissionNeeded>().single()
        assertEquals(PermissionKey.PHONE_CALL, needed.permission)
        assertEquals(TurnOutcome.PERMISSION_NEEDED, context.history.last().outcome)
    }

    @Test
    fun `an online-only tool fails cleanly when offline`() = runTest {
        environment.online = false
        val tool = FakeTool("github_notifications", worksOffline = false)
        llm.enqueueToolCall("github_notifications")

        val events = orchestrator(listOf(tool)).handle("check github").toList()

        assertTrue(tool.invocations.isEmpty())
        assertTrue(events.reply()!!.contains("offline"))
    }

    @Test
    fun `inference failure degrades to an apology rather than an exception`() = runTest {
        llm.enqueue(LlmResponse.Error("model exploded"))

        val events = orchestrator(listOf(FakeTool("open_app"))).handle("open maps").toList()

        assertEquals("I'm having trouble processing that request.", events.reply())
        assertEquals(TurnOutcome.FAILED, context.history.last().outcome)
    }

    @Test
    fun `a tool that throws does not take the session down`() = runTest {
        val tool = FakeTool("open_app").apply { throwOnExecute = IllegalStateException("boom") }
        llm.enqueueToolCall("open_app")

        val events = orchestrator(listOf(tool)).handle("open maps").toList()

        assertEquals("Something went wrong while I was doing that.", events.reply())
        assertEquals(AssistantEvent.Done, events.last())
    }

    // ---------------------------------------------------------- confirmation

    @Test
    fun `a medium risk tool asks first and runs only after yes`() = runTest {
        val tool = FakeTool(
            name = "send_message",
            riskLevel = RiskLevel.MEDIUM,
            action = "send Mom a message saying I'll be home soon",
            result = ToolResult.Success("Sent."),
        )
        llm.enqueueToolCall("send_message", mapOf("body" to "I'll be home soon"))
        val subject = orchestrator(listOf(tool))

        val asked = subject.handle("tell mom I'll be home soon").toList()
        assertTrue(tool.invocations.isEmpty())
        val question = asked.filterIsInstance<AssistantEvent.AwaitingConfirmation>().single().question
        assertEquals("Do you want me to send Mom a message saying I'll be home soon?", question)

        val confirmed = subject.handle("yes").toList()
        assertEquals(1, tool.invocations.size)
        assertEquals("Sent.", confirmed.reply())
        assertNull(context.pending)
    }

    @Test
    fun `no cancels the pending action`() = runTest {
        val tool = FakeTool("send_message", riskLevel = RiskLevel.MEDIUM)
        llm.enqueueToolCall("send_message", mapOf("body" to "hi"))
        val subject = orchestrator(listOf(tool))

        subject.handle("text mom hi").toList()
        val declined = subject.handle("no, cancel that").toList()

        assertTrue(tool.invocations.isEmpty())
        assertEquals("Okay, I won't.", declined.reply())
        assertEquals(TurnOutcome.DECLINED, context.history.last().outcome)
        assertNull(context.pending)
    }

    @Test
    fun `an unclear answer re-asks and never counts as consent`() = runTest {
        val tool = FakeTool("send_message", riskLevel = RiskLevel.MEDIUM)
        llm.enqueueToolCall("send_message", mapOf("body" to "hi"))
        val subject = orchestrator(listOf(tool))

        subject.handle("text mom hi").toList()
        // A mis-transcription, background speech, or a half-heard word.
        val events = subject.handle("uhh the weather is nice").toList()

        assertTrue(tool.invocations.isEmpty())
        assertTrue(events.any { it is AssistantEvent.AwaitingConfirmation })
        assertNotNull(context.pending)
    }

    @Test
    fun `high risk always confirms even when the policy says never`() = runTest {
        val tool = FakeTool("make_payment", riskLevel = RiskLevel.HIGH)
        val relaxed = AssistantConfig(
            confirmationPolicy = ConfirmationPolicy(
                confirmFrom = RiskLevel.HIGH,
                neverConfirm = setOf("make_payment"),
            ),
        )
        llm.enqueueToolCall("make_payment", mapOf("amount" to 500))

        val events = orchestrator(listOf(tool), relaxed).handle("pay the bill").toList()

        assertTrue(tool.invocations.isEmpty())
        assertTrue(events.any { it is AssistantEvent.AwaitingConfirmation })
    }

    @Test
    fun `a relaxed policy lets a medium risk tool through`() = runTest {
        val tool = FakeTool("send_message", riskLevel = RiskLevel.MEDIUM, result = ToolResult.Success("Sent."))
        llm.enqueueToolCall("send_message", mapOf("body" to "hi"))

        val config = AssistantConfig(confirmationPolicy = ConfirmationPolicy.RELAXED)
        val events = orchestrator(listOf(tool), config).handle("text mom hi").toList()

        assertEquals(1, tool.invocations.size)
        assertEquals("Sent.", events.reply())
    }

    @Test
    fun `a tool can demand confirmation the policy would have skipped`() = runTest {
        val tool = FakeTool("delete_file", riskLevel = RiskLevel.LOW)
        tool.result = ToolResult.RequiresConfirmation(
            confirmationMessage = "That deletes 300 photos. Are you sure?",
            retryArguments = ToolArguments(mapOf("path" to "/DCIM")),
        )
        llm.enqueueToolCall("delete_file", mapOf("path" to "/DCIM"))
        val subject = orchestrator(listOf(tool))

        val asked = subject.handle("clear my camera folder").toList()
        assertEquals(
            "That deletes 300 photos. Are you sure?",
            asked.filterIsInstance<AssistantEvent.AwaitingConfirmation>().single().question,
        )

        tool.result = ToolResult.Success("Deleted.")
        val confirmed = subject.handle("go ahead").toList()
        assertEquals("Deleted.", confirmed.reply())
        assertEquals("/DCIM", tool.invocations.last().string("path"))
    }

    // ---------------------------------------------------------- clarification

    @Test
    fun `a clarification is resolved from the spoken answer`() = runTest {
        val tool = FakeTool("make_phone_call", requiredPermissions = emptySet())
        tool.result = ToolResult.NeedsClarification(
            question = "Mom has two numbers. Which one?",
            options = listOf(
                ClarificationOption("Mobile", ToolArguments(mapOf("number" to "0600"))),
                ClarificationOption("Home", ToolArguments(mapOf("number" to "0311"))),
            ),
        )
        llm.enqueueToolCall("make_phone_call", mapOf("contact" to "Mom"))
        val subject = orchestrator(listOf(tool))

        val asked = subject.handle("call Mom").toList()
        assertEquals(
            listOf("Mobile", "Home"),
            asked.filterIsInstance<AssistantEvent.AwaitingChoice>().single().options,
        )

        tool.result = ToolResult.Success("Calling Mom.")
        val chosen = subject.handle("the mobile one").toList()

        assertEquals("0600", tool.invocations.last().string("number"))
        assertEquals("Calling Mom.", chosen.reply())
    }

    @Test
    fun `an unmatched answer re-asks the choice`() = runTest {
        val tool = FakeTool("make_phone_call")
        tool.result = ToolResult.NeedsClarification(
            question = "Which one?",
            options = listOf(
                ClarificationOption("Mobile", ToolArguments(mapOf("number" to "0600"))),
                ClarificationOption("Home", ToolArguments(mapOf("number" to "0311"))),
            ),
        )
        llm.enqueueToolCall("make_phone_call", mapOf("contact" to "Mom"))
        val subject = orchestrator(listOf(tool))

        subject.handle("call Mom").toList()
        val events = subject.handle("what's the weather").toList()

        assertEquals(1, tool.invocations.size)
        assertTrue(events.any { it is AssistantEvent.AwaitingChoice })
    }

    // ----------------------------------------------------------- prompt input

    @Test
    fun `the prompt carries memory, context and device facts`() = runTest {
        memory.remember(MemoryEntry(key = "music_app", value = "Spotify"))
        environment.facts = listOf("It is Sunday, 21:40.")
        llm.enqueueText("ok")

        orchestrator(listOf(FakeTool("play_media"))).handle("play something").toList()

        val system = llm.prompts.last().first().content
        assertTrue(system.contains("music app: Spotify"))
        assertTrue(system.contains("It is Sunday, 21:40."))
        assertTrue(system.contains("play_media"))
    }

    @Test
    fun `memory is withheld when the user turns it off`() = runTest {
        memory.remember(MemoryEntry(key = "music_app", value = "Spotify"))
        llm.enqueueText("ok")

        orchestrator(listOf(FakeTool("play_media")), AssistantConfig(useMemory = false))
            .handle("play something")
            .toList()

        assertTrue(!llm.prompts.last().first().content.contains("Spotify"))
    }

    @Test
    fun `only enabled tools are offered to the model`() = runTest {
        val registry = ToolRegistry(listOf(FakeTool("open_app"), FakeTool("send_sms")))
        registry.setEnabled("send_sms", false)
        llm.enqueueText("ok")

        AssistantOrchestrator(
            registry = registry,
            llm = llm,
            context = context,
            configProvider = AssistantConfigProvider.fixed(),
        ).handle("hello").toList()

        assertEquals(listOf("open_app"), llm.lastOfferedTools.map { it.name })
    }

    @Test
    fun `blank input ends the turn immediately`() = runTest {
        val events = orchestrator(listOf(FakeTool("open_app"))).handle("   ").toList()
        assertEquals(listOf(AssistantEvent.Done), events)
    }

    private fun List<AssistantEvent>.reply(): String? =
        filterIsInstance<AssistantEvent.Reply>().lastOrNull()?.text
}
