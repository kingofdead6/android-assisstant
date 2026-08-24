package com.john.assistant.core.llm.rules

import com.john.assistant.core.util.TimeExpressionParser

/**
 * The phrase catalogue behind John's no-model mode.
 *
 * These cover the commands a phone assistant is asked for hundreds of times a
 * day. They exist for three reasons:
 *
 *  1. John is useful the moment it is installed, before any model is downloaded.
 *  2. They are the floor when a model is loaded but confused — a 1B model that
 *     returns nonsense for "pause" is worse than a regex that never does.
 *  3. They cost no battery. Running a transformer to recognise "pause the
 *     music" is not a good trade on a phone.
 *
 * This is *not* meant to replace the model. Anything with real language in it
 * — "tell Mom I'll be twenty minutes late because the bus broke down" — falls
 * through to the LLM, which is what it is for.
 */
object DefaultIntentPatterns {

    /** @param currentHour device clock hour, for resolving bare times. */
    fun catalogue(currentHour: Int = -1): List<IntentPattern> = buildList {

        // ---- media: specific before general ------------------------------
        add(IntentPattern("next_track", Regex("""\b(next|skip)( the)?( song| track)?\b""", IGNORE)))
        add(IntentPattern("previous_track", Regex("""\b(previous|last|go back a)( song| track)\b""", IGNORE)))
        add(IntentPattern("pause_media", Regex("""^\s*(pause|stop)( the)?( music| song| playback| media| video)?\s*$""", IGNORE)))
        add(IntentPattern("resume_media", Regex("""\b(resume|continue|unpause)( the)?( music| song| playback)?\b""", IGNORE)))

        add(
            IntentPattern(
                tool = "play_media",
                regex = Regex("""\bplay\s+(?<query>.+?)\s+on\s+(?<app>[\w\s]+)$""", IGNORE),
                extract = { match ->
                    buildMap {
                        match.groups["query"]?.value?.trim()?.let { put("query", it) }
                        match.groups["app"]?.value?.trim()?.let { put("app_name", it) }
                    }
                },
            ),
        )
        add(
            IntentPattern(
                tool = "play_media",
                regex = Regex("""\bplay\b(\s+(some|my)?\s*(?<query>.*))?""", IGNORE),
                extract = { match ->
                    val query = match.groups["query"]?.value?.trim().orEmpty()
                    // "play some music" carries no query — it means "just play".
                    if (query.isEmpty() || query in GENERIC_MUSIC) emptyMap()
                    else mapOf("query" to query)
                },
            ),
        )

        // ---- volume -------------------------------------------------------
        add(
            IntentPattern(
                tool = "set_volume",
                regex = Regex("""\b(set|change)?\s*(the )?volume (to|at) (?<level>\d{1,3})\b""", IGNORE),
                extract = { match ->
                    match.groups["level"]?.value?.toIntOrNull()
                        ?.let { mapOf("percent" to it.coerceIn(0, 100).toLong()) }
                        .orEmpty()
                },
            ),
        )
        add(IntentPattern("increase_volume", Regex("""\b(turn|volume) ?(the )?(volume )?(up|louder)\b|\b(increase|raise) (the )?volume\b""", IGNORE)))
        add(IntentPattern("decrease_volume", Regex("""\b(turn|volume) ?(the )?(volume )?(down|quieter)\b|\b(decrease|lower|reduce) (the )?volume\b""", IGNORE)))
        add(IntentPattern("set_volume", Regex("""\b(mute|silence)\b""", IGNORE), { mapOf("percent" to 0L) }))

        // ---- search and web ----------------------------------------------
        // Anchored at the start: an unanchored `\bgoogle\b` turns
        // "launch the Google Maps app" into a web search for "Maps app".
        add(pattern("search_google", """^(search|google|look up|look for)\s+(google\s+)?(for\s+)?(?<query>.+)$""", "query"))
        add(pattern("open_url", """^(open|go to|visit)\s+(?<url>(https?://|www\.)\S+)""", "url"))

        // ---- apps: after search so "search google for X" isn't "open google"
        add(pattern("open_app", """^(open|launch|start|run)\s+(the\s+)?(?<appname>[\w\s.&'-]+?)(\s+app)?\s*$""", "app_name"))

        // ---- communication -------------------------------------------------
        add(
            IntentPattern(
                tool = "send_message",
                regex = Regex(
                    """\b(send|write|text)\s+(?<contact>[\w\s'-]+?)\s+(an?\s+)?(?<channel>whatsapp|sms|text|telegram|message)\b.*?\b(saying|that says|that|:)\s+(?<body>.+)$""",
                    IGNORE,
                ),
                extract = { match ->
                    buildMap {
                        match.groups["contact"]?.value?.trim()?.let { put("contact", it) }
                        match.groups["body"]?.value?.trim()?.let { put("body", it) }
                        match.groups["channel"]?.value?.trim()?.lowercase()?.let {
                            put("channel", if (it == "text" || it == "message") "sms" else it)
                        }
                    }
                },
            ),
        )
        add(pattern("make_phone_call", """\b(call|phone|dial|ring)\s+(?<contact>[\w\s'-]+?)\s*$""", "contact"))

        // ---- alarms and reminders ------------------------------------------
        add(
            IntentPattern(
                tool = "create_reminder",
                regex = Regex("""\bremind me( to| about)?\s+(?<what>.+?)(\s+(at|by|around)\s+(?<when>.+))?$""", IGNORE),
                extract = { match ->
                    buildMap {
                        match.groups["what"]?.value?.trim()?.let { put("text", it) }
                        val whenText = match.groups["when"]?.value ?: match.value
                        TimeExpressionParser.parse(whenText, currentHour)?.let {
                            put("hour", it.hour.toLong())
                            put("minute", it.minute.toLong())
                        }
                    }
                },
            ),
        )
        add(
            IntentPattern(
                tool = "set_alarm",
                regex = Regex("""\b(set|create|make)?\s*(an?\s+)?alarm\b.*""", IGNORE),
                extract = { match ->
                    TimeExpressionParser.parse(match.value, currentHour)?.let {
                        mapOf("hour" to it.hour.toLong(), "minute" to it.minute.toLong())
                    }.orEmpty()
                },
            ),
        )

        // ---- system queries --------------------------------------------------
        add(IntentPattern("get_battery", Regex("""\bbattery\b|\bhow much (charge|power)\b""", IGNORE)))
        add(IntentPattern("get_time", Regex("""\bwhat('?s| is) the time\b|\bwhat time is it\b|\btell me the time\b""", IGNORE)))
        add(IntentPattern("get_date", Regex("""\bwhat('?s| is) (the |today'?s )?date\b|\bwhat day is it\b""", IGNORE)))
        add(IntentPattern("get_device_state", Regex("""\b(wifi|wi-fi|bluetooth|network|connection) (state|status)\b|\bam i (online|connected)\b""", IGNORE)))
        add(IntentPattern("read_notifications", Regex("""\b(read|check|what are|any)\b.*\bnotifications?\b""", IGNORE)))
        add(IntentPattern("toggle_flashlight", Regex("""\b(turn (on|off) the |)(flashlight|torch)\b""", IGNORE)))
        add(IntentPattern("open_camera", Regex("""\bopen (the )?camera\b""", IGNORE)))

        // ---- calendar ----------------------------------------------------------
        add(IntentPattern("read_calendar", Regex("""\b(what('?s| is)|anything) on my (calendar|schedule|agenda)\b|\bmy (schedule|agenda)\b""", IGNORE)))

        // ---- memory -------------------------------------------------------------
        add(
            IntentPattern(
                tool = "remember_fact",
                regex = Regex("""\bremember that\s+(?<statement>.+)$""", IGNORE),
                extract = { match ->
                    match.groups["statement"]?.value?.trim()?.let { mapOf("statement" to it) }.orEmpty()
                },
            ),
        )
    }

    private val IGNORE = RegexOption.IGNORE_CASE

    /** Phrases that mean "play anything", not a search query. */
    private val GENERIC_MUSIC = setOf(
        "music", "some music", "songs", "a song", "something", "tunes", "my music",
    )
}
