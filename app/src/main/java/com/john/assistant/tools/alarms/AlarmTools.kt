package com.john.assistant.tools.alarms

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.core.util.TimeOfDay
import com.john.assistant.platform.AlarmScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Set an alarm for 7 AM."
 *
 * Delegates to the user's clock app rather than implementing an alarm. That is
 * the documented interop route, needs no permission, and means the alarm
 * survives John being uninstalled — which matters for something whose whole job
 * is to go off tomorrow morning.
 */
@Singleton
class SetAlarmTool @Inject constructor(
    private val alarmScheduler: AlarmScheduler,
) : AssistantTool {

    override val name = "set_alarm"

    override val description = "Set an alarm in the phone's clock app at a given hour and minute."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "hour",
            type = ParameterType.INTEGER,
            description = "Hour in 24-hour form, 0 to 23.",
            required = true,
            min = 0.0,
            max = 23.0,
        ),
        ToolParameter(
            name = "minute",
            type = ParameterType.INTEGER,
            description = "Minute, 0 to 59.",
            min = 0.0,
            max = 59.0,
        ),
        ToolParameter("label", ParameterType.STRING, "What the alarm is for."),
    )

    override val riskLevel = RiskLevel.LOW

    override val examples = listOf("set an alarm for 7 am", "wake me at half past six")

    override fun describeAction(arguments: ToolArguments): String =
        "set an alarm for ${spokenTime(arguments)}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val hour = arguments.int("hour") ?: return ToolResult.Failure("What time should I set it for?")
        val minute = arguments.int("minute", 0)
        val label = arguments.string("label")

        return if (alarmScheduler.setAlarm(hour, minute, label)) {
            ToolResult.Success(
                message = "Alarm set for ${TimeOfDay(hour, minute).spoken()}.",
                data = mapOf("alarm_time" to TimeOfDay(hour, minute).spoken()),
            )
        } else {
            ToolResult.Failure(
                message = "I couldn't find a clock app to set the alarm in.",
                recoverable = false,
            )
        }
    }

    private fun spokenTime(arguments: ToolArguments): String =
        TimeOfDay(arguments.int("hour", 7), arguments.int("minute", 0)).spoken()
}

/**
 * "Remind me to study at 8."
 *
 * John's own, because Android has no "remind me" intent to hand off to. Uses an
 * exact alarm so the reminder arrives when asked rather than whenever Doze next
 * lets the device wake — and says so when the exact permission is missing,
 * instead of quietly scheduling something that drifts.
 */
@Singleton
class CreateReminderTool @Inject constructor(
    private val alarmScheduler: AlarmScheduler,
) : AssistantTool {

    override val name = "create_reminder"

    override val description = "Remind the user about something at a given time."

    override val parameters = ToolParameters.of(
        ToolParameter("text", ParameterType.STRING, "What to remind them about.", required = true),
        ToolParameter(
            name = "hour",
            type = ParameterType.INTEGER,
            description = "Hour in 24-hour form.",
            required = true,
            min = 0.0,
            max = 23.0,
        ),
        ToolParameter(
            name = "minute",
            type = ParameterType.INTEGER,
            description = "Minute.",
            min = 0.0,
            max = 59.0,
        ),
    )

    override val requiredPermissions = setOf(PermissionKey.POST_NOTIFICATIONS)

    override val examples = listOf("remind me to study at 8 pm", "remind me to call the bank at 10")

    override fun describeAction(arguments: ToolArguments): String =
        "remind you to ${arguments.string("text", "that")} at " +
            TimeOfDay(arguments.int("hour", 9), arguments.int("minute", 0)).spoken()

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val text = arguments.string("text")?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("What should I remind you about?")
        val hour = arguments.int("hour") ?: return ToolResult.Failure("When should I remind you?")
        val minute = arguments.int("minute", 0)

        val triggerAt = alarmScheduler.nextOccurrenceOf(hour, minute)
        val reminder = alarmScheduler.scheduleReminder(text, triggerAt)
            ?: return ToolResult.Failure("I couldn't schedule that reminder.")

        val time = TimeOfDay(hour, minute).spoken()
        val caveat = if (alarmScheduler.canScheduleExact()) {
            ""
        } else {
            " It might be a few minutes late — Android needs the alarms " +
                "permission for exact timing, which you can grant in settings."
        }

        return ToolResult.Success(
            message = "I'll remind you to $text at $time.$caveat",
            data = mapOf("alarm_time" to time, "reminder_id" to reminder.id),
        )
    }
}

/** "Set a timer for ten minutes." */
@Singleton
class SetTimerTool @Inject constructor(
    private val alarmScheduler: AlarmScheduler,
) : AssistantTool {

    override val name = "set_timer"

    override val description = "Start a countdown timer in the phone's clock app."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "seconds",
            type = ParameterType.INTEGER,
            description = "How long the timer should run, in seconds.",
            required = true,
            min = 1.0,
            max = 86_400.0,
        ),
        ToolParameter("label", ParameterType.STRING, "What the timer is for."),
    )

    override val examples = listOf("set a timer for ten minutes", "timer for 30 seconds")

    override fun describeAction(arguments: ToolArguments): String =
        "set a timer for ${spokenDuration(arguments.int("seconds", 60))}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val seconds = arguments.int("seconds") ?: return ToolResult.Failure("How long for?")

        return if (alarmScheduler.setTimer(seconds, arguments.string("label"))) {
            ToolResult.Success("Timer set for ${spokenDuration(seconds)}.")
        } else {
            ToolResult.Failure("I couldn't find a clock app to set the timer in.", recoverable = false)
        }
    }

    private fun spokenDuration(seconds: Int): String = when {
        seconds < 60 -> "$seconds seconds"
        seconds % 3600 == 0 -> "${seconds / 3600} hour${plural(seconds / 3600)}"
        seconds % 60 == 0 -> "${seconds / 60} minute${plural(seconds / 60)}"
        else -> "${seconds / 60} minutes and ${seconds % 60} seconds"
    }

    private fun plural(value: Int) = if (value == 1) "" else "s"
}
