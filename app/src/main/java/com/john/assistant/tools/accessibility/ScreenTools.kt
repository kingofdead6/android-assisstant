package com.john.assistant.tools.accessibility

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.platform.AccessibilityBridge
import com.john.assistant.platform.GlobalAction
import com.john.assistant.platform.ScreenAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "What's on the screen?"
 *
 * Only registered when the user has enabled the accessibility service, so the
 * model is not offered a capability that would fail. Reads on demand; nothing
 * is captured in the background and nothing is stored.
 */
@Singleton
class ReadScreenTool @Inject constructor(
    private val accessibility: AccessibilityBridge,
) : AssistantTool {

    override val name = "read_screen"

    override val description =
        "Read the text currently visible on screen. Only works when screen assistance is enabled."

    override val requiredPermissions = setOf(PermissionKey.ACCESSIBILITY)

    override val examples = listOf("what's on the screen", "read this page")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val lines = accessibility.readScreenText()
            ?: return ToolResult.RequiresPermission(PermissionKey.ACCESSIBILITY)

        if (lines.isEmpty()) {
            return ToolResult.Success("I can't see any text on the screen right now.")
        }

        return ToolResult.Success(lines.take(MAX_LINES).joinToString(". "))
    }

    private companion object {
        const val MAX_LINES = 20
    }
}

/**
 * "Tap Send."
 *
 * The most fragile thing John can do, and it says so. UI automation depends on
 * another app's layout, which changes without warning, so this tool reports
 * failure plainly rather than retrying blindly or claiming success. MEDIUM risk
 * because a tap can do anything the screen happens to be offering.
 */
@Singleton
class TapOnScreenTool @Inject constructor(
    private val accessibility: AccessibilityBridge,
) : AssistantTool {

    override val name = "tap_on_screen"

    override val description =
        "Tap a button or item on screen by its visible text. Last resort for apps with no other way in."

    override val parameters = ToolParameters.of(
        ToolParameter("text", ParameterType.STRING, "The visible label to tap.", required = true),
    )

    override val riskLevel = RiskLevel.MEDIUM

    override val requiredPermissions = setOf(PermissionKey.ACCESSIBILITY)

    override val examples = listOf("tap send", "press the play button")

    override fun describeAction(arguments: ToolArguments): String =
        "tap \"${arguments.string("text", "that")}\" on screen"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val text = arguments.string("text").orEmpty()

        return when (val outcome = accessibility.clickByText(text)) {
            ScreenAction.Done -> ToolResult.Success("Done.", spoken = false)
            ScreenAction.NotEnabled -> ToolResult.RequiresPermission(PermissionKey.ACCESSIBILITY)
            is ScreenAction.Failed -> ToolResult.Failure(outcome.reason)
        }
    }
}

/** "Go back." */
@Singleton
class NavigateScreenTool @Inject constructor(
    private val accessibility: AccessibilityBridge,
) : AssistantTool {

    override val name = "navigate_screen"

    override val description = "Press back, home, or open recent apps."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Which navigation action to perform.",
            required = true,
            allowedValues = listOf("back", "home", "recents", "notifications"),
        ),
    )

    override val requiredPermissions = setOf(PermissionKey.ACCESSIBILITY)

    override val examples = listOf("go back", "go home", "show recent apps")

    override fun describeAction(arguments: ToolArguments): String =
        "go ${arguments.string("action", "back")}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val action = when (arguments.string("action")) {
            "home" -> GlobalAction.HOME
            "recents" -> GlobalAction.RECENTS
            "notifications" -> GlobalAction.NOTIFICATIONS
            else -> GlobalAction.BACK
        }

        return when (val outcome = accessibility.performGlobalAction(action)) {
            ScreenAction.Done -> ToolResult.Success("Done.", spoken = false)
            ScreenAction.NotEnabled -> ToolResult.RequiresPermission(PermissionKey.ACCESSIBILITY)
            is ScreenAction.Failed -> ToolResult.Failure(outcome.reason)
        }
    }
}
