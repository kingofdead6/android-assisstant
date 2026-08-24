package com.john.assistant.tools.notifications

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.platform.NotificationAccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Read my notifications."
 *
 * Answers with a *summary* by default — "five notifications: two from WhatsApp,
 * one from GitHub" — and only reads content when the user asks for a specific
 * app. That default is deliberate. Notifications routinely contain
 * verification codes, medical appointments and messages from other people, and
 * an assistant that reads all of it aloud the moment it is asked will
 * eventually do so in a room where it should not have.
 *
 * Nothing read here is stored: [NotificationAccess] holds notifications in
 * memory only, and they are dropped when access is revoked.
 */
@Singleton
class ReadNotificationsTool @Inject constructor(
    private val notificationAccess: NotificationAccess,
) : AssistantTool {

    override val name = "read_notifications"

    override val description =
        "Summarise the notifications on the phone, or read out the ones from a specific app."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "app_name",
            type = ParameterType.STRING,
            description = "Read notifications from this app in full. Omit for a summary.",
        ),
        ToolParameter(
            name = "detail",
            type = ParameterType.BOOLEAN,
            description = "True when the user explicitly asked to hear the contents.",
        ),
    )

    override val requiredPermissions = setOf(PermissionKey.NOTIFICATION_ACCESS)

    override val examples = listOf(
        "read my notifications",
        "what did WhatsApp say",
        "any new notifications",
    )

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        if (!notificationAccess.isConnected) {
            return ToolResult.RequiresPermission(PermissionKey.NOTIFICATION_ACCESS)
        }

        val appFilter = arguments.string("app_name")
        val wantsDetail = arguments.boolean("detail", default = appFilter != null)

        val notifications = if (appFilter != null) {
            notificationAccess.forApp(appFilter)
        } else {
            notificationAccess.current()
        }

        if (notifications.isEmpty()) {
            return ToolResult.Success(
                if (appFilter != null) "Nothing from $appFilter." else "You have no new notifications.",
            )
        }

        if (!wantsDetail) {
            val summary = notificationAccess.summaryByApp()
            val spoken = summary.joinToString(", ") { (app, count) ->
                if (count == 1) "one from $app" else "$count from $app"
            }
            return ToolResult.Success(
                message = "You have ${notifications.size} " +
                    (if (notifications.size == 1) "notification: " else "notifications: ") + spoken + ".",
                data = mapOf("count" to notifications.size),
            )
        }

        val spoken = notifications.take(MAX_READ_ALOUD).joinToString(". ") { notification ->
            listOfNotNull(notification.appLabel, notification.title, notification.text)
                .joinToString(": ")
        }

        return ToolResult.Success(
            message = spoken,
            // Deliberately not returned as conversational context — notification
            // bodies must not end up in the next prompt.
            data = mapOf("count" to notifications.size),
        )
    }

    private companion object {
        const val MAX_READ_ALOUD = 5
    }
}
