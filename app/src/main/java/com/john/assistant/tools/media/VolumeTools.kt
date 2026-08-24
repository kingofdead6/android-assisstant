package com.john.assistant.tools.media

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.platform.MediaManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Turn the volume up."
 *
 * Adjusts the music stream specifically, not the ringer. A user asking an
 * assistant to turn the volume up while music plays means the music — silencing
 * their ringtone instead would be a surprising and hard-to-notice mistake.
 */
@Singleton
class IncreaseVolumeTool @Inject constructor(
    private val mediaManager: MediaManager,
) : AssistantTool {
    override val name = "increase_volume"
    override val description = "Turn the media volume up a step."
    override val examples = listOf("turn the volume up", "louder")
    override fun describeAction(arguments: ToolArguments) = "turn the volume up"

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        if (mediaManager.adjustMusicVolume(raise = true)) {
            ToolResult.Success(
                message = "Volume is at ${mediaManager.musicVolumePercent()} percent.",
                spoken = false,
            )
        } else {
            ToolResult.Failure("I couldn't change the volume.")
        }
}

/** "Turn it down." */
@Singleton
class DecreaseVolumeTool @Inject constructor(
    private val mediaManager: MediaManager,
) : AssistantTool {
    override val name = "decrease_volume"
    override val description = "Turn the media volume down a step."
    override val examples = listOf("turn the volume down", "quieter")
    override fun describeAction(arguments: ToolArguments) = "turn the volume down"

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        if (mediaManager.adjustMusicVolume(raise = false)) {
            ToolResult.Success(
                message = "Volume is at ${mediaManager.musicVolumePercent()} percent.",
                spoken = false,
            )
        } else {
            ToolResult.Failure("I couldn't change the volume.")
        }
}

/** "Set the volume to 40 percent." */
@Singleton
class SetVolumeTool @Inject constructor(
    private val mediaManager: MediaManager,
) : AssistantTool {

    override val name = "set_volume"

    override val description = "Set the media volume to a percentage between 0 and 100."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "percent",
            type = ParameterType.INTEGER,
            description = "Volume level, 0 to 100.",
            required = true,
            min = 0.0,
            max = 100.0,
        ),
    )

    override val examples = listOf("set the volume to 40", "mute")

    override fun describeAction(arguments: ToolArguments): String =
        "set the volume to ${arguments.int("percent", 50)} percent"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val percent = arguments.int("percent") ?: return ToolResult.Failure("I need a volume level.")

        return if (mediaManager.setMusicVolumePercent(percent)) {
            ToolResult.Success(
                message = if (percent == 0) "Muted." else "Volume set to $percent percent.",
                data = mapOf("percent" to percent),
                spoken = false,
            )
        } else {
            ToolResult.Failure("I couldn't change the volume.")
        }
    }
}
