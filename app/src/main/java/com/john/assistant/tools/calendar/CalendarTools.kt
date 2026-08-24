package com.john.assistant.tools.calendar

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.core.util.TimeOfDay
import com.john.assistant.platform.CalendarEvent
import com.john.assistant.platform.CalendarManager
import javax.inject.Inject
import javax.inject.Singleton

/** "What's on my calendar tomorrow?" */
@Singleton
class ReadCalendarTool @Inject constructor(
    private val calendarManager: CalendarManager,
) : AssistantTool {

    override val name = "read_calendar"

    override val description = "Say what is on the user's calendar today or tomorrow."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "day",
            type = ParameterType.STRING,
            description = "Which day to read.",
            allowedValues = listOf("today", "tomorrow"),
        ),
    )

    override val requiredPermissions = setOf(PermissionKey.CALENDAR_READ)

    override val examples = listOf("what's on my calendar tomorrow", "what's my schedule today")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val day = arguments.string("day", "today")

        val events: List<CalendarEvent> = if (day == "tomorrow") {
            calendarManager.eventsTomorrow()
        } else {
            calendarManager.eventsToday()
        }

        if (events.isEmpty()) {
            return ToolResult.Success("Nothing on your calendar $day.")
        }

        val spoken = events.take(MAX_SPOKEN).joinToString(", ") { it.spoken() }
        val message = if (events.size == 1) {
            "One thing $day: $spoken."
        } else {
            "${events.size} things $day: $spoken."
        }

        return ToolResult.Success(message, mapOf("count" to events.size))
    }

    private companion object {
        const val MAX_SPOKEN = 5
    }
}

/**
 * "Create an event tomorrow at 5 PM called Gym."
 *
 * Opens the calendar app's editor pre-filled rather than writing to the
 * provider. The user sees what will be saved, picks which calendar it goes in,
 * and John needs no WRITE_CALENDAR grant to do it.
 */
@Singleton
class CreateCalendarEventTool @Inject constructor(
    private val calendarManager: CalendarManager,
) : AssistantTool {

    override val name = "create_calendar_event"

    override val description = "Create a calendar event at a given time."

    override val parameters = ToolParameters.of(
        ToolParameter("title", ParameterType.STRING, "What the event is called.", required = true),
        ToolParameter(
            name = "hour",
            type = ParameterType.INTEGER,
            description = "Start hour in 24-hour form.",
            required = true,
            min = 0.0,
            max = 23.0,
        ),
        ToolParameter("minute", ParameterType.INTEGER, "Start minute.", min = 0.0, max = 59.0),
        ToolParameter(
            name = "day",
            type = ParameterType.STRING,
            description = "Which day.",
            allowedValues = listOf("today", "tomorrow"),
        ),
        ToolParameter(
            name = "duration_minutes",
            type = ParameterType.INTEGER,
            description = "How long it lasts.",
            min = 5.0,
            max = 1440.0,
        ),
        ToolParameter("location", ParameterType.STRING, "Where it is."),
    )

    override val riskLevel = RiskLevel.MEDIUM

    override val examples = listOf(
        "create an event tomorrow at 5 pm called Gym",
        "put a meeting in my calendar at 10",
    )

    override fun describeAction(arguments: ToolArguments): String =
        "add \"${arguments.string("title", "an event")}\" to your calendar " +
            "${arguments.string("day", "today")} at " +
            TimeOfDay(arguments.int("hour", 9), arguments.int("minute", 0)).spoken()

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val title = arguments.string("title")?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("What should I call the event?")
        val hour = arguments.int("hour") ?: return ToolResult.Failure("What time is it at?")
        val minute = arguments.int("minute", 0)
        val durationMinutes = arguments.int("duration_minutes", DEFAULT_DURATION_MINUTES)

        val dayOffset = if (arguments.string("day") == "tomorrow") 1 else 0
        val start = startMillis(hour, minute, dayOffset)
        val end = start + durationMinutes * 60_000L

        return if (calendarManager.composeEvent(title, start, end, arguments.string("location"))) {
            ToolResult.Success(
                message = "I've opened $title at ${TimeOfDay(hour, minute).spoken()} in your calendar — save it when it looks right.",
                data = mapOf("event_title" to title),
            )
        } else {
            ToolResult.Failure("I couldn't find a calendar app to add that to.", recoverable = false)
        }
    }

    private fun startMillis(hour: Int, minute: Int, dayOffset: Int): Long =
        java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private companion object {
        const val DEFAULT_DURATION_MINUTES = 60
    }
}
