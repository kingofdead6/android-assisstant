package com.john.assistant.tools.app

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ClarificationOption
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.platform.AppMatch
import com.john.assistant.platform.AndroidAppManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Open YouTube."
 *
 * Resolves the spoken name against what is actually installed rather than
 * against a hardcoded table, so it works for apps this code has never heard of.
 * When several apps fit, it asks instead of guessing — opening the wrong app is
 * a small annoyance, but the same guess in a messaging tool would not be.
 */
@Singleton
class OpenAppTool @Inject constructor(
    private val appManager: AndroidAppManager,
) : AssistantTool {

    override val name = "open_app"

    override val description =
        "Open an installed Android application by its name, e.g. YouTube, Spotify, Settings."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "app_name",
            type = ParameterType.STRING,
            description = "The app's name as the user said it.",
            required = true,
        ),
    )

    override val riskLevel = RiskLevel.LOW

    override val examples = listOf("open YouTube", "launch Spotify", "start the camera app")

    override fun describeAction(arguments: ToolArguments): String =
        "open ${arguments.string("app_name", "that app")}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val requested = arguments.string("app_name").orEmpty()

        return when (val match = appManager.resolve(requested)) {
            is AppMatch.Exact -> {
                if (appManager.launch(match.app.packageName)) {
                    ToolResult.Success(
                        message = "${match.app.label} is open.",
                        data = mapOf(
                            "app_label" to match.app.label,
                            "package" to match.app.packageName,
                        ),
                    )
                } else {
                    // Resolvable but not launchable: the app has no launcher
                    // activity, which is a real state for system components.
                    ToolResult.Failure("I found ${match.app.label} but couldn't open it.")
                }
            }

            is AppMatch.Ambiguous -> ToolResult.NeedsClarification(
                question = "I found a few. Which one — ${match.candidates.joinToString(", ") { it.label }}?",
                options = match.candidates.map { app ->
                    ClarificationOption(
                        label = app.label,
                        arguments = ToolArguments(mapOf("app_name" to app.packageName)),
                    )
                },
            )

            AppMatch.None -> ToolResult.Failure(
                message = "I couldn't find an app called $requested on this phone.",
                recoverable = false,
            )
        }
    }
}

/**
 * "What apps do I have?"
 *
 * Also the tool the model consults when a user asks for something John cannot
 * find, so it can say what *is* installed rather than only what is not.
 */
@Singleton
class ListAppsTool @Inject constructor(
    private val appManager: AndroidAppManager,
) : AssistantTool {

    override val name = "list_apps"

    override val description = "List the applications installed on this phone."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "filter",
            type = ParameterType.STRING,
            description = "Optional word to filter the list by.",
        ),
    )

    override val examples = listOf("what apps do I have", "which music apps are installed")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val filter = arguments.string("filter")?.lowercase().orEmpty()

        val apps = appManager.installedApps()
            .filter { filter.isEmpty() || it.normalisedLabel.contains(filter) }

        if (apps.isEmpty()) {
            return ToolResult.Success("I couldn't find any matching apps.")
        }

        val named = apps.take(MAX_SPOKEN).joinToString(", ") { it.label }
        val message = if (apps.size > MAX_SPOKEN) {
            "You have ${apps.size} apps. The first few are $named."
        } else {
            "You have $named."
        }

        return ToolResult.Success(message, mapOf("count" to apps.size))
    }

    private companion object {
        // A spoken list longer than this stops being useful.
        const val MAX_SPOKEN = 8
    }
}
