package com.john.assistant.tools.messaging

import com.john.assistant.core.assistant.PermissionGate
import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ClarificationOption
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.platform.ContactMatch
import com.john.assistant.platform.ContactResolver
import com.john.assistant.platform.MessageOutcome
import com.john.assistant.platform.MessagingChannel
import com.john.assistant.platform.MessagingManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Send Mom a WhatsApp message saying I'll be home soon."
 *
 * The most consequential tool John has, and the one where being honest matters
 * most. Two things it will not do:
 *
 *  - **Claim a send it did not make.** Only SMS with SEND_SMS granted actually
 *    sends. Every other channel opens the app with the message ready, and the
 *    spoken reply says so — "it's ready in WhatsApp, tap send". A user who
 *    believes a message went when it did not will find out at the worst
 *    possible moment.
 *  - **Guess the recipient.** An ambiguous name asks, exactly as calling does.
 *
 * Risk is MEDIUM, so the confirmation policy makes John read the message back
 * before anything happens. That is where mis-transcriptions get caught.
 */
@Singleton
class SendMessageTool @Inject constructor(
    private val messagingManager: MessagingManager,
    private val contactResolver: ContactResolver,
    private val permissions: PermissionGate,
) : AssistantTool {

    override val name = "send_message"

    override val description =
        "Send a message to a contact by SMS, WhatsApp, Telegram, Messenger or Signal."

    override val parameters = ToolParameters.of(
        ToolParameter("contact", ParameterType.STRING, "Who to send it to.", required = true),
        ToolParameter("body", ParameterType.STRING, "The message text.", required = true),
        ToolParameter(
            name = "channel",
            type = ParameterType.STRING,
            description = "Which app to send it with. Defaults to SMS.",
            allowedValues = MessagingChannel.ids,
        ),
        ToolParameter("number", ParameterType.STRING, "A resolved phone number, if known."),
    )

    override val riskLevel = RiskLevel.MEDIUM

    override val requiredPermissions = setOf(PermissionKey.CONTACTS)

    override val examples = listOf(
        "send Mom a WhatsApp message saying I'll be home soon",
        "text Ali that I'm running late",
    )

    override fun describeAction(arguments: ToolArguments): String {
        val channel = channelOf(arguments)
        val who = arguments.string("contact", "them")
        val body = arguments.string("body", "")
        return "send $who a ${channel.displayName} saying \"$body\""
    }

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val channel = channelOf(arguments)
        val body = arguments.string("body")?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("What should the message say?")

        if (!messagingManager.isAvailable(channel)) {
            val alternatives = messagingManager.availableChannels()
                .filterNot { it == channel }
                .joinToString(" or ") { it.displayName }

            return ToolResult.Failure(
                message = if (alternatives.isEmpty()) {
                    "${channel.displayName.replaceFirstChar { it.uppercase() }} isn't available on this phone."
                } else {
                    "${channel.displayName.replaceFirstChar { it.uppercase() }} isn't installed. " +
                        "I could use $alternatives instead."
                },
                recoverable = false,
            )
        }

        // A number already resolved by an earlier clarification turn.
        arguments.string("number")?.let { number ->
            return dispatch(channel, number, arguments.string("contact", "them"), body)
        }

        val contactName = arguments.string("contact").orEmpty()

        return when (val match = contactResolver.resolve(contactName)) {
            is ContactMatch.Single ->
                dispatch(channel, match.contact.number, match.contact.displayName, body)

            is ContactMatch.MultipleNumbers -> clarify(
                question = "${match.displayName} has ${match.numbers.size} numbers. Which one?",
                labels = match.numbers.map { it.typeLabel },
                numbers = match.numbers.map { it.number },
                displayName = match.displayName,
                channel = channel,
                body = body,
            )

            is ContactMatch.MultiplePeople -> clarify(
                question = "Which one — ${match.candidates.joinToString(", ") { it.displayName }}?",
                labels = match.candidates.map { it.displayName },
                numbers = match.candidates.map { it.number },
                displayName = null,
                channel = channel,
                body = body,
            )

            ContactMatch.None -> {
                // Chat apps can still open with the text ready even without a
                // number; the user picks the chat. SMS genuinely cannot.
                if (channel == MessagingChannel.SMS) {
                    ToolResult.Failure(
                        message = "I couldn't find anyone called $contactName in your contacts.",
                        recoverable = false,
                    )
                } else {
                    dispatch(channel, number = null, displayName = contactName, body = body)
                }
            }
        }
    }

    private suspend fun dispatch(
        channel: MessagingChannel,
        number: String?,
        displayName: String,
        body: String,
    ): ToolResult {
        // Direct SMS sending needs SEND_SMS. Without it John still composes,
        // which is strictly better than refusing.
        val canSendDirectly = channel == MessagingChannel.SMS &&
            permissions.isGranted(PermissionKey.SMS)

        return when (val outcome = messagingManager.send(channel, number, body, canSendDirectly)) {
            MessageOutcome.Sent -> ToolResult.Success(
                message = "Sent to $displayName.",
                data = mapOf("contact_name" to displayName),
            )

            is MessageOutcome.Composed -> ToolResult.Success(
                message = "It's ready in ${outcome.channel.displayName} for $displayName — " +
                    "tap send when you're happy with it.",
                data = mapOf("contact_name" to displayName),
            )

            is MessageOutcome.Failed -> ToolResult.Failure(outcome.reason)
        }
    }

    private fun clarify(
        question: String,
        labels: List<String>,
        numbers: List<String>,
        displayName: String?,
        channel: MessagingChannel,
        body: String,
    ): ToolResult = ToolResult.NeedsClarification(
        question = question,
        options = labels.zip(numbers).map { (label, number) ->
            ClarificationOption(
                label = label,
                arguments = ToolArguments(
                    mapOf(
                        "number" to number,
                        "contact" to (displayName ?: label),
                        "channel" to channel.id,
                        "body" to body,
                    ),
                ),
            )
        },
    )

    private fun channelOf(arguments: ToolArguments): MessagingChannel =
        arguments.string("channel")
            ?.let(MessagingChannel::fromId)
            ?: MessagingChannel.SMS
}
