package com.john.assistant.core.assistant

import com.john.assistant.core.conversation.Affirmation
import com.john.assistant.core.conversation.AffirmationDetector
import com.john.assistant.core.conversation.ConversationContextManager
import com.john.assistant.core.conversation.ConversationTurn
import com.john.assistant.core.conversation.PendingAction
import com.john.assistant.core.conversation.TurnOutcome
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.llm.LlmResponse
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.core.prompt.PromptBuilder
import com.john.assistant.core.prompt.PromptContext
import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolLookup
import com.john.assistant.core.tool.ToolRegistry
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.core.tool.ValidationResult
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.core.util.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one path from an utterance to an action.
 *
 * ```
 *  transcript
 *      -> pending question? resolve it deterministically
 *      -> LLM: pick a tool
 *      -> registry lookup      (unregistered name == does not exist)
 *      -> schema validation    (undeclared arguments are dropped)
 *      -> offline check
 *      -> permission check
 *      -> confirmation policy
 *      -> execute
 *      -> reply
 * ```
 *
 * Every gate is enforced here, in code. The system prompt asks the model to
 * behave; this class is what makes it irrelevant whether it does. A model that
 * hallucinates `delete_everything`, invents arguments, or tries to skip a
 * confirmation gets the same treatment as one that behaves: the name is not in
 * the registry, the arguments are not in the schema, and the policy is not the
 * model's to read.
 *
 * Turns are serialised by [turnLock] — John does one thing at a time, which is
 * also what makes "yes" unambiguous.
 */
class AssistantOrchestrator(
    private val registry: ToolRegistry,
    private val llm: LlmEngine,
    private val context: ConversationContextManager,
    private val configProvider: AssistantConfigProvider,
    private val permissions: PermissionGate = PermissionGate.ALLOW_ALL,
    private val environment: DeviceEnvironment = DeviceEnvironment.OFFLINE_UNKNOWN,
    private val memory: MemoryStore = MemoryStore.DISABLED,
    private val timeSource: TimeSource = TimeSource.SYSTEM,
    private val logger: AssistantLogger = AssistantLogger.NONE,
) {

    private val turnLock = Mutex()

    /** Handle one utterance. The flow always ends with [AssistantEvent.Done]. */
    fun handle(utterance: String): Flow<AssistantEvent> = flow {
        turnLock.withLock {
            val text = utterance.trim()
            if (text.isEmpty()) {
                emit(AssistantEvent.Done)
                return@withLock
            }

            emit(AssistantEvent.Heard(text))
            val config = configProvider.current()

            try {
                when (val pending = context.pending) {
                    null -> runNewRequest(text, config)
                    is PendingAction.Confirmation -> resolveConfirmation(text, pending, config)
                    is PendingAction.Clarification -> resolveClarification(text, pending, config)
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                logger.error(TAG, "Turn failed unexpectedly", error)
                reply(text, FALLBACK_ERROR, TurnOutcome.FAILED, toolName = null)
            }

            emit(AssistantEvent.Done)
        }
    }

    /** Drop any half-finished question, e.g. when the user taps "cancel". */
    fun cancelPending() {
        context.clearPending()
    }

    // ---------------------------------------------------------------- new request

    private suspend fun FlowCollector.runNewRequest(text: String, config: AssistantConfig) {
        emit(AssistantEvent.Thinking)

        val response = infer(text, config)
        when (response) {
            is LlmResponse.Error -> {
                logger.warn(TAG, "Inference failed: ${response.message}", response.cause)
                reply(text, FALLBACK_ERROR, TurnOutcome.FAILED, toolName = null)
            }

            is LlmResponse.Text -> {
                val spoken = response.content.ifBlank { FALLBACK_UNDERSTOOD }
                reply(text, spoken, TurnOutcome.SPOKEN, toolName = null)
            }

            is LlmResponse.ToolCall -> handleToolCall(text, response, config)
        }
    }

    private suspend fun infer(text: String, config: AssistantConfig): LlmResponse {
        val promptContext = PromptContext(
            toolDefinitions = registry.definitions(),
            focus = context.focus,
            memoryLines = if (config.useMemory) memory.promptLines() else emptyList(),
            recentTurns = context.toChatMessages(config.historyTurns),
            isOnline = environment.isOnline(),
            accessibilityEnabled = environment.isAccessibilityEnabled(),
            deviceFacts = environment.facts(),
        )

        val messages = PromptBuilder(config.systemPrompt, config.historyTurns)
            .build(text, promptContext)

        // A local model that stalls must not hang the assistant; a timeout here
        // degrades to a spoken apology instead of a frozen orb.
        return withTimeoutOrNull(config.llmOptions.timeoutMillis) {
            runCatching { llm.generate(messages, promptContext.toolDefinitions, config.llmOptions) }
                .getOrElse { LlmResponse.Error("Inference threw", it) }
        } ?: LlmResponse.Error("Inference timed out after ${config.llmOptions.timeoutMillis} ms")
    }

    private suspend fun FlowCollector.handleToolCall(
        text: String,
        call: LlmResponse.ToolCall,
        config: AssistantConfig,
    ) {
        // `answer` is not a registered tool: it is how the model says "just talk".
        if (call.toolName.equals(ANSWER_TOOL, ignoreCase = true)) {
            val spoken = call.arguments["text"]?.toString()?.takeIf { it.isNotBlank() }
                ?: call.preamble
                ?: FALLBACK_UNDERSTOOD
            reply(text, spoken, TurnOutcome.SPOKEN, toolName = null)
            return
        }

        val tool = when (val lookup = registry.resolve(call.toolName)) {
            is ToolLookup.Found -> lookup.tool

            is ToolLookup.Disabled -> {
                logger.info(TAG, "Model asked for disabled tool '${call.toolName}'")
                reply(
                    text,
                    "That's switched off in your settings.",
                    TurnOutcome.DECLINED,
                    call.toolName,
                )
                return
            }

            is ToolLookup.Unknown -> {
                // The single most common small-model failure. Never guess a
                // near-match: executing the wrong action is worse than admitting it.
                logger.warn(TAG, "Model invented tool '${lookup.requestedName}'")
                reply(text, FALLBACK_UNSUPPORTED, TurnOutcome.FAILED, toolName = null)
                return
            }
        }

        when (val validation = tool.parameters.validate(call.arguments)) {
            is ValidationResult.Invalid -> {
                logger.info(TAG, "Rejected arguments for ${tool.name}: ${validation.reason}")
                reply(text, validation.reason, TurnOutcome.FAILED, tool.name)
            }

            is ValidationResult.Valid ->
                gateAndExecute(text, tool, validation.arguments, config, confirmed = false)
        }
    }

    // -------------------------------------------------------------------- gates

    private suspend fun FlowCollector.gateAndExecute(
        text: String,
        tool: AssistantTool,
        arguments: ToolArguments,
        config: AssistantConfig,
        confirmed: Boolean,
    ) {
        if (!tool.worksOffline && !environment.isOnline()) {
            reply(
                text,
                "${tool.name.replace('_', ' ').replaceFirstChar { it.uppercase() }} " +
                    "isn't available because you're offline.",
                TurnOutcome.FAILED,
                tool.name,
            )
            return
        }

        val missing = tool.requiredPermissions.firstOrNull { !permissions.isGranted(it) }
        if (missing != null) {
            emit(AssistantEvent.PermissionNeeded(missing, missing.rationale))
            record(text, missing.rationale, TurnOutcome.PERMISSION_NEEDED, tool.name)
            return
        }

        if (!confirmed && config.confirmationPolicy.requiresConfirmation(tool)) {
            askForConfirmation(text, tool, arguments, "Do you want me to ${tool.describeAction(arguments)}?")
            return
        }

        execute(text, tool, arguments, config, confirmed, attempt = 1)
    }

    private suspend fun FlowCollector.execute(
        text: String,
        tool: AssistantTool,
        arguments: ToolArguments,
        config: AssistantConfig,
        confirmed: Boolean,
        attempt: Int,
    ) {
        emit(AssistantEvent.Executing(tool.name, tool.describeAction(arguments)))
        logger.info(TAG, "Executing ${tool.name} $arguments")

        val result = runCatching { tool.execute(arguments) }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // A tool that throws is a bug in that tool, not a reason for
                // John to die. Report it and keep the session alive.
                logger.error(TAG, "Tool ${tool.name} threw", error)
                ToolResult.Failure("Something went wrong while I was doing that.", cause = error)
            }

        when (result) {
            is ToolResult.Success -> {
                context.noteToolSuccess(tool.name, result)
                val spoken = phrase(text, tool, result.message, config)
                reply(text, spoken, TurnOutcome.EXECUTED, tool.name, spokenAloud = result.spoken)
            }

            is ToolResult.Failure -> {
                context.noteToolFailure(tool.name)
                logger.warn(TAG, "Tool ${tool.name} failed: ${result.message}", result.cause)
                reply(text, result.message, TurnOutcome.FAILED, tool.name)
            }

            is ToolResult.RequiresPermission -> {
                emit(AssistantEvent.PermissionNeeded(result.permission, result.message))
                record(text, result.message, TurnOutcome.PERMISSION_NEEDED, tool.name)
            }

            is ToolResult.RequiresConfirmation -> {
                // Honour a tool's own confirmation request once. A tool that keeps
                // asking after a yes would otherwise loop the user forever.
                if (confirmed && attempt >= MAX_CONFIRMATION_ATTEMPTS) {
                    logger.warn(TAG, "Tool ${tool.name} re-requested confirmation after a yes")
                    reply(text, "I couldn't complete that.", TurnOutcome.FAILED, tool.name)
                } else {
                    askForConfirmation(text, tool, result.retryArguments, result.confirmationMessage)
                }
            }

            is ToolResult.NeedsClarification -> {
                context.awaiting(
                    PendingAction.Clarification(
                        toolName = tool.name,
                        question = result.question,
                        options = result.options,
                        originalUtterance = text,
                    ),
                )
                emit(AssistantEvent.AwaitingChoice(result.question, result.options.map { it.label }))
                record(text, result.question, TurnOutcome.SPOKEN, tool.name)
            }
        }
    }

    /** Optional second pass that rewrites a tool outcome as speech. */
    private suspend fun phrase(
        utterance: String,
        tool: AssistantTool,
        outcome: String,
        config: AssistantConfig,
    ): String {
        if (!config.phraseResultsWithLlm) return outcome

        val messages = PromptBuilder(config.systemPrompt, config.historyTurns)
            .buildPhrasing(utterance, tool.name, outcome)

        val response = withTimeoutOrNull(config.llmOptions.timeoutMillis) {
            runCatching { llm.generate(messages, emptyList(), config.llmOptions) }.getOrNull()
        }

        // Fall back to the tool's own wording rather than risk speaking nothing.
        return (response as? LlmResponse.Text)?.content?.trim()?.takeIf { it.isNotBlank() } ?: outcome
    }

    // --------------------------------------------------------- pending questions

    private suspend fun FlowCollector.askForConfirmation(
        text: String,
        tool: AssistantTool,
        arguments: ToolArguments,
        question: String,
    ) {
        context.awaiting(
            PendingAction.Confirmation(
                toolName = tool.name,
                arguments = arguments,
                question = question,
                originalUtterance = text,
            ),
        )
        emit(AssistantEvent.AwaitingConfirmation(question))
        record(text, question, TurnOutcome.SPOKEN, tool.name)
    }

    private suspend fun FlowCollector.resolveConfirmation(
        text: String,
        pending: PendingAction.Confirmation,
        config: AssistantConfig,
    ) {
        when (AffirmationDetector.classify(text)) {
            Affirmation.YES -> {
                context.clearPending()
                when (val lookup = registry.resolve(pending.toolName)) {
                    is ToolLookup.Found ->
                        execute(
                            text = pending.originalUtterance,
                            tool = lookup.tool,
                            arguments = pending.arguments,
                            config = config,
                            confirmed = true,
                            attempt = MAX_CONFIRMATION_ATTEMPTS,
                        )
                    // The tool was disabled between the question and the answer.
                    else -> reply(text, FALLBACK_UNSUPPORTED, TurnOutcome.FAILED, pending.toolName)
                }
            }

            Affirmation.NO -> {
                context.clearPending()
                reply(text, "Okay, I won't.", TurnOutcome.DECLINED, pending.toolName)
            }

            // Silence, noise or a mis-transcription must never count as consent.
            Affirmation.UNCLEAR -> {
                emit(AssistantEvent.AwaitingConfirmation(pending.question))
                record(text, pending.question, TurnOutcome.SPOKEN, pending.toolName)
            }
        }
    }

    private suspend fun FlowCollector.resolveClarification(
        text: String,
        pending: PendingAction.Clarification,
        config: AssistantConfig,
    ) {
        val choice = ChoiceMatcher.match(text, pending.options.map { it.label })
        if (choice == null) {
            emit(AssistantEvent.AwaitingChoice(pending.question, pending.options.map { it.label }))
            record(text, pending.question, TurnOutcome.SPOKEN, pending.toolName)
            return
        }

        context.clearPending()
        val option = pending.options[choice]
        when (val lookup = registry.resolve(pending.toolName)) {
            is ToolLookup.Found ->
                gateAndExecute(
                    text = pending.originalUtterance,
                    tool = lookup.tool,
                    arguments = option.arguments,
                    config = config,
                    // Picking from a list John offered is a choice, not consent to
                    // the underlying action — a MEDIUM-risk tool still confirms.
                    confirmed = false,
                )
            else -> reply(text, FALLBACK_UNSUPPORTED, TurnOutcome.FAILED, pending.toolName)
        }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun FlowCollector.reply(
        utterance: String,
        spoken: String,
        outcome: TurnOutcome,
        toolName: String?,
        spokenAloud: Boolean = true,
    ) {
        emit(AssistantEvent.Reply(spoken, spokenAloud))
        record(utterance, spoken, outcome, toolName)
    }

    private fun record(utterance: String, spoken: String, outcome: TurnOutcome, toolName: String?) {
        context.record(
            ConversationTurn(
                timestampMillis = timeSource.nowMillis(),
                userText = utterance,
                assistantText = spoken,
                toolName = toolName,
                outcome = outcome,
            ),
        )
    }

    private companion object {
        const val TAG = "Orchestrator"
        const val ANSWER_TOOL = "answer"
        const val MAX_CONFIRMATION_ATTEMPTS = 2
        const val FALLBACK_ERROR = "I'm having trouble processing that request."
        const val FALLBACK_UNSUPPORTED = "I can't do that one yet."
        const val FALLBACK_UNDERSTOOD = "Okay."
    }
}

private typealias FlowCollector = kotlinx.coroutines.flow.FlowCollector<AssistantEvent>
