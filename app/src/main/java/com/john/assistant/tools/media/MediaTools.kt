package com.john.assistant.tools.media

import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.ParameterType
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.core.tool.ToolArguments
import com.john.assistant.core.tool.ToolParameter
import com.john.assistant.core.tool.ToolParameters
import com.john.assistant.core.tool.ToolResult
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.platform.AppMatch
import com.john.assistant.platform.AndroidAppManager
import com.john.assistant.platform.MediaManager
import com.john.assistant.platform.WebLauncher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Play some music." / "Play jazz on Spotify."
 *
 * Three cases, in order of what the user actually meant:
 *
 *  1. A named app — use it.
 *  2. A remembered preference ("my music app is Spotify") — use that. This is
 *     the payoff for John's long-term memory: the bare command does the right
 *     thing without asking again.
 *  3. Nothing named and nothing remembered — resume whatever media session is
 *     already there, which is what "play" means to every headset button ever
 *     made.
 */
@Singleton
class PlayMediaTool @Inject constructor(
    private val mediaManager: MediaManager,
    private val appManager: AndroidAppManager,
    private val webLauncher: WebLauncher,
    private val memory: MemoryStore,
) : AssistantTool {

    override val name = "play_media"

    override val description =
        "Start or resume playback. Optionally play a specific song, artist or playlist, " +
            "optionally in a specific app."

    override val parameters = ToolParameters.of(
        ToolParameter("query", ParameterType.STRING, "What to play. Omit to just resume."),
        ToolParameter("app_name", ParameterType.STRING, "Which app to play it in."),
    )

    override val examples = listOf("play some music", "play Bohemian Rhapsody on Spotify")

    override fun describeAction(arguments: ToolArguments): String =
        arguments.string("query")?.let { "play $it" } ?: "start playing"

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val query = arguments.string("query")
        val requestedApp = arguments.string("app_name") ?: preferredMusicApp()

        if (query == null && requestedApp == null) {
            return if (mediaManager.play()) {
                val playing = mediaManager.nowPlaying()
                ToolResult.Success(
                    message = playing?.title?.let { "Playing $it." } ?: "Playing.",
                    data = buildMap { playing?.title?.let { put("track", it) } },
                )
            } else {
                ToolResult.Failure("I couldn't find anything to play.")
            }
        }

        val app = requestedApp?.let { appManager.resolve(it) }
        val target = (app as? AppMatch.Exact)?.app

        if (requestedApp != null && target == null) {
            return ToolResult.Failure(
                message = "$requestedApp isn't installed on this phone.",
                recoverable = false,
            )
        }

        // With a query and a target app, the deep link is the only route that
        // actually starts *that* content. Without one, opening the app and
        // pressing play is the honest best effort.
        if (query != null && target != null) {
            val template = WebLauncher.SITE_SEARCH_TEMPLATES[target.packageName]
            if (template != null && webLauncher.searchOnSite(template, query)) {
                return ToolResult.Success(
                    message = "Looking for $query in ${target.label}.",
                    data = mapOf("query" to query, "app_label" to target.label, "package" to target.packageName),
                )
            }
        }

        if (target != null && appManager.launch(target.packageName)) {
            return ToolResult.Success(
                message = if (query != null) {
                    "I've opened ${target.label}. I can't search inside it from here, " +
                        "so you'll need to pick $query yourself."
                } else {
                    "${target.label} is open."
                },
                data = mapOf("app_label" to target.label, "package" to target.packageName),
            )
        }

        return if (mediaManager.play()) {
            ToolResult.Success("Playing.")
        } else {
            ToolResult.Failure("I couldn't start playback.")
        }
    }

    private suspend fun preferredMusicApp(): String? =
        memory.get(PREFERRED_MUSIC_APP_KEY)?.value

    companion object {
        const val PREFERRED_MUSIC_APP_KEY = "music_app"
    }
}

/** "Pause." */
@Singleton
class PauseMediaTool @Inject constructor(
    private val mediaManager: MediaManager,
) : AssistantTool {
    override val name = "pause_media"
    override val description = "Pause whatever is playing."
    override val examples = listOf("pause", "stop the music")
    override fun describeAction(arguments: ToolArguments) = "pause the music"

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        if (mediaManager.pause()) {
            ToolResult.Success("Paused.", spoken = false)
        } else {
            ToolResult.Failure("Nothing seems to be playing.")
        }
}

/** "Resume." */
@Singleton
class ResumeMediaTool @Inject constructor(
    private val mediaManager: MediaManager,
) : AssistantTool {
    override val name = "resume_media"
    override val description = "Resume playback that was paused."
    override val examples = listOf("resume", "carry on playing")
    override fun describeAction(arguments: ToolArguments) = "resume playback"

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        if (mediaManager.play()) {
            ToolResult.Success("Playing.", spoken = false)
        } else {
            ToolResult.Failure("I couldn't resume playback.")
        }
}

/** "Next song." */
@Singleton
class NextTrackTool @Inject constructor(
    private val mediaManager: MediaManager,
) : AssistantTool {
    override val name = "next_track"
    override val description = "Skip to the next track."
    override val examples = listOf("next song", "skip this")
    override fun describeAction(arguments: ToolArguments) = "skip to the next track"

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        if (mediaManager.next()) {
            val playing = mediaManager.nowPlaying()
            ToolResult.Success(
                message = playing?.title?.let { "Playing $it." } ?: "Skipped.",
                data = buildMap { playing?.title?.let { put("track", it) } },
                spoken = playing?.title != null,
            )
        } else {
            ToolResult.Failure("Nothing seems to be playing.")
        }
}

/** "Go back a song." */
@Singleton
class PreviousTrackTool @Inject constructor(
    private val mediaManager: MediaManager,
) : AssistantTool {
    override val name = "previous_track"
    override val description = "Go back to the previous track."
    override val examples = listOf("previous song", "go back a track")
    override fun describeAction(arguments: ToolArguments) = "go back a track"

    override suspend fun execute(arguments: ToolArguments): ToolResult =
        if (mediaManager.previous()) {
            ToolResult.Success("Going back.", spoken = false)
        } else {
            ToolResult.Failure("Nothing seems to be playing.")
        }
}

/** "What's playing?" — needs notification access to answer. */
@Singleton
class NowPlayingTool @Inject constructor(
    private val mediaManager: MediaManager,
    private val appManager: AndroidAppManager,
) : AssistantTool {

    override val name = "get_now_playing"
    override val description = "Say what is currently playing."
    override val requiredPermissions = setOf(PermissionKey.NOTIFICATION_ACCESS)
    override val examples = listOf("what's playing", "what song is this")

    override suspend fun execute(arguments: ToolArguments): ToolResult {
        val playing = mediaManager.nowPlaying()
            ?: return ToolResult.Success("Nothing is playing right now.")

        val app = appManager.labelFor(playing.appPackage)
        val title = playing.title ?: return ToolResult.Success("Something is playing in $app.")
        val artist = playing.artist

        return ToolResult.Success(
            message = if (artist != null) "$title by $artist, on $app." else "$title, on $app.",
            data = buildMap {
                put("track", title)
                artist?.let { put("artist", it) }
                put("app_label", app)
            },
        )
    }
}
