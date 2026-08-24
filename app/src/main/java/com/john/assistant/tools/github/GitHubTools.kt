package com.john.assistant.tools.github

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.integrations.github.GitHubClient
import com.john.assistant.integrations.github.GitHubResult
import javax.inject.Inject
import javax.inject.Singleton

private const val NOT_CONNECTED =
    "Your GitHub account isn't connected. You can link it in settings, under Integrations."

/** "What are my repositories?" */
@Singleton
class ListRepositoriesTool @Inject constructor(
    private val client: GitHubClient,
) : AssistantTool {

    override val name = "github_repositories"

    override val description = "List the user's GitHub repositories, most recently updated first."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "How many to list.",
            min = 1.0,
            max = 20.0,
        ),
    )

    override val worksOffline = false

    override val examples = listOf("what are my repos", "show my GitHub projects")

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        when (val result = client.repositories(arguments.int("limit", DEFAULT_LIMIT))) {
            GitHubResult.NotConnected -> ToolResult.Failure(NOT_CONNECTED, recoverable = false)

            is GitHubResult.Failure -> ToolResult.Failure(result.reason)

            is GitHubResult.Success -> {
                val repositories = result.value
                if (repositories.isEmpty()) {
                    ToolResult.Success("You don't have any repositories.")
                } else {
                    ToolResult.Success(
                        message = "You have ${repositories.size}: " +
                            repositories.joinToString(", ") { it.fullName.substringAfterLast('/') } + ".",
                        data = mapOf("count" to repositories.size),
                    )
                }
            }
        }

    private companion object {
        const val DEFAULT_LIMIT = 5
    }
}

/**
 * "Summarise my latest GitHub notifications."
 *
 * Summarises by repository rather than reading every title aloud, for the same
 * reason the notification tool does: a spoken list of fifteen items is not
 * something anyone listens to, and the titles can carry issue content the user
 * did not ask to have read out.
 */
@Singleton
class GitHubNotificationsTool @Inject constructor(
    private val client: GitHubClient,
) : AssistantTool {

    override val name = "github_notifications"

    override val description = "Summarise the user's unread GitHub notifications."

    override val parameters = ToolParameters.of(
        ToolParameter(
            name = "detail",
            type = ParameterType.BOOLEAN,
            description = "True when the user asked to hear the individual titles.",
        ),
    )

    override val worksOffline = false

    override val examples = listOf(
        "summarize my latest GitHub notifications",
        "any GitHub activity",
    )

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        when (val result = client.notifications()) {
            GitHubResult.NotConnected -> ToolResult.Failure(NOT_CONNECTED, recoverable = false)

            is GitHubResult.Failure -> ToolResult.Failure(result.reason)

            is GitHubResult.Success -> {
                val notifications = result.value
                when {
                    notifications.isEmpty() ->
                        ToolResult.Success("Nothing new on GitHub.")

                    arguments.boolean("detail", default = false) ->
                        ToolResult.Success(
                            notifications.take(MAX_SPOKEN).joinToString(". ") {
                                "${it.repository}: ${it.title}"
                            },
                        )

                    else -> {
                        val byRepository: List<Pair<String, Int>> = notifications
                            .groupingBy { it.repository }
                            .eachCount()
                            .toList()
                            .sortedByDescending { (_, count) -> count }

                        ToolResult.Success(
                            message = "You have ${notifications.size} GitHub notifications: " +
                                byRepository.joinToString(", ") { (repository, count) ->
                                    val name = repository.substringAfterLast('/')
                                    if (count == 1) "one from $name" else "$count from $name"
                                } + ".",
                            data = mapOf("count" to notifications.size),
                        )
                    }
                }
            }
        }

    private companion object {
        const val MAX_SPOKEN = 5
    }
}
