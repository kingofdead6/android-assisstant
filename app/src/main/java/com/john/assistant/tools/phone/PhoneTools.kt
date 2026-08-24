package com.john.assistant.tools.phone

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
import com.john.assistant.platform.PhoneManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Call Mom."
 *
 * The interesting part is what happens when the answer is not obvious. A
 * contact with three numbers, or two contacts named Ali, produces a
 * [ToolResult.NeedsClarification] rather than a guess — because the failure
 * mode of guessing here is calling the wrong person, which the user only
 * discovers when someone picks up.
 *
 * The resolved number is passed back through the clarification arguments, so
 * the second turn dials exactly what John offered rather than re-running a
 * lookup that might now sort differently.
 */
@Singleton
class MakePhoneCallTool @Inject constructor(
    private val contactResolver: ContactResolver,
    private val phoneManager: PhoneManager,
) : AssistantTool {

    override val name = "make_phone_call"

    override val description = "Call a contact by name, or call a phone number directly."

    override val parameters = ToolParameters.of(
        ToolParameter("contact", ParameterType.STRING, "The contact's name."),
        ToolParameter("number", ParameterType.STRING, "A phone number, if the user gave one."),
    )

    // A call is loud, obvious and one tap to cancel, so it does not need a
    // spoken confirmation the way a message does.
    override val riskLevel = RiskLevel.MEDIUM

    override val requiredPermissions = setOf(PermissionKey.PHONE_CALL, PermissionKey.CONTACTS)

    override val examples = listOf("call Mom", "phone Ali", "call 0550 12 34 56")

    override fun describeAction(arguments: ToolArguments): String {
        val who = arguments.string("contact") ?: arguments.string("number") ?: "that number"
        return "call $who"
    }

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        if (!phoneManager.hasTelephony()) {
            return ToolResult.Failure("This device can't make phone calls.", recoverable = false)
        }

        // An explicit number skips contact resolution entirely.
        arguments.string("number")?.let { number ->
            return placeCall(number, arguments.string("contact") ?: number)
        }

        val name = arguments.string("contact")
            ?: return ToolResult.Failure("Who should I call?")

        return when (val match = contactResolver.resolve(name)) {
            is ContactMatch.Single -> placeCall(match.contact.number, match.contact.displayName)

            is ContactMatch.MultipleNumbers -> ToolResult.NeedsClarification(
                question = "${match.displayName} has ${match.numbers.size} numbers. " +
                    "Which one — ${match.numbers.joinToString(", ") { it.typeLabel }}?",
                options = match.numbers.map { number ->
                    ClarificationOption(
                        label = number.typeLabel,
                        arguments = ToolArguments(
                            mapOf("number" to number.number, "contact" to number.displayName),
                        ),
                    )
                },
            )

            is ContactMatch.MultiplePeople -> ToolResult.NeedsClarification(
                question = "I found a few people. Which one — " +
                    match.candidates.joinToString(", ") { it.displayName } + "?",
                options = match.candidates.map { contact ->
                    ClarificationOption(
                        label = contact.displayName,
                        arguments = ToolArguments(
                            mapOf("number" to contact.number, "contact" to contact.displayName),
                        ),
                    )
                },
            )

            ContactMatch.None -> ToolResult.Failure(
                message = "I couldn't find anyone called $name in your contacts.",
                recoverable = false,
            )
        }
    }

    private fun placeCall(number: String, displayName: String): ToolResult =
        if (phoneManager.call(number)) {
            ToolResult.Success(
                message = "Calling $displayName.",
                data = mapOf("contact_name" to displayName),
            )
        } else {
            // Falling back to the dialler means the user still gets somewhere,
            // and the message says plainly that they have to press call.
            if (phoneManager.dial(number)) {
                ToolResult.Success("I've put $displayName in the dialler — press call.")
            } else {
                ToolResult.Failure("I couldn't start the call.")
            }
        }
}

/** "Look up Ali's number." */
@Singleton
class FindContactTool @Inject constructor(
    private val contactResolver: ContactResolver,
) : AssistantTool {

    override val name = "find_contact"

    override val description = "Look a contact up without calling them."

    override val parameters = ToolParameters.of(
        ToolParameter("contact", ParameterType.STRING, "The contact's name.", required = true),
    )

    override val requiredPermissions = setOf(PermissionKey.CONTACTS)

    override val examples = listOf("what's Ali's number", "do I have Mom's mobile")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val name = arguments.string("contact").orEmpty()

        return when (val match = contactResolver.resolve(name)) {
            is ContactMatch.Single -> ToolResult.Success(
                message = "${match.contact.displayName}, ${match.contact.typeLabel}: " +
                    match.contact.number,
                data = mapOf("contact_name" to match.contact.displayName),
            )

            is ContactMatch.MultipleNumbers -> ToolResult.Success(
                message = "${match.displayName} has ${match.numbers.size} numbers: " +
                    match.numbers.joinToString(", ") { "${it.typeLabel}, ${it.number}" },
                data = mapOf("contact_name" to match.displayName),
            )

            is ContactMatch.MultiplePeople -> ToolResult.Success(
                "I found ${match.candidates.size} people matching $name: " +
                    match.candidates.joinToString(", ") { it.displayName } + ".",
            )

            ContactMatch.None -> ToolResult.Failure(
                message = "I couldn't find anyone called $name.",
                recoverable = false,
            )
        }
    }
}
