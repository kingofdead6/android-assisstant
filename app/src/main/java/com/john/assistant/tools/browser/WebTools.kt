package com.john.assistant.tools.browser

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.platform.WebLauncher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Search Google for the best restaurants in Batna."
 *
 * Hands the query to the user's search app through `ACTION_WEB_SEARCH`. John
 * does not fetch or scrape results: that would need network permission for a
 * task the browser already does better, and it would put John in the business
 * of parsing someone else's HTML.
 */
@Singleton
class SearchWebTool @Inject constructor(
    private val webLauncher: WebLauncher,
) : AssistantTool {

    override val name = "search_google"

    override val description = "Search the web for something and show the results."

    override val parameters = ToolParameters.of(
        ToolParameter("query", ParameterType.STRING, "What to search for.", required = true),
    )

    override val worksOffline = false

    override val examples = listOf(
        "search Google for quantum computing",
        "look up the weather in Batna",
    )

    override fun describeAction(arguments: ToolArguments): String =
        "search the web for ${arguments.string("query", "that")}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val query = arguments.string("query").orEmpty()

        return if (webLauncher.search(query)) {
            ToolResult.Success("Searching for $query.", mapOf("query" to query))
        } else {
            ToolResult.Failure("I couldn't open a browser to search.")
        }
    }
}

/** "Open github.com." */
@Singleton
class OpenUrlTool @Inject constructor(
    private val webLauncher: WebLauncher,
) : AssistantTool {

    override val name = "open_url"

    override val description = "Open a web address in the browser."

    override val parameters = ToolParameters.of(
        ToolParameter("url", ParameterType.STRING, "The address to open.", required = true),
    )

    override val worksOffline = false

    override val examples = listOf("open github.com", "go to youtube.com")

    override fun describeAction(arguments: ToolArguments): String =
        "open ${arguments.string("url", "that link")}"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val url = arguments.string("url").orEmpty()

        return if (webLauncher.openUrl(url)) {
            ToolResult.Success("Opening $url.", mapOf("url" to url))
        } else {
            ToolResult.Failure("That doesn't look like a web address I can open.", recoverable = false)
        }
    }
}

/** "Show me coffee shops near me." */
@Singleton
class SearchMapsTool @Inject constructor(
    private val webLauncher: WebLauncher,
) : AssistantTool {

    override val name = "search_maps"

    override val description = "Find a place or address on the map."

    override val parameters = ToolParameters.of(
        ToolParameter("query", ParameterType.STRING, "The place to find.", required = true),
    )

    override val riskLevel = RiskLevel.LOW

    override val worksOffline = false

    override val examples = listOf("find a pharmacy near me", "show me the university on the map")

    override fun describeAction(arguments: ToolArguments): String =
        "look up ${arguments.string("query", "that")} on the map"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val query = arguments.string("query").orEmpty()

        return if (webLauncher.openMaps(query)) {
            ToolResult.Success("Looking up $query on the map.", mapOf("query" to query))
        } else {
            ToolResult.Failure("I couldn't open a maps app.")
        }
    }
}
