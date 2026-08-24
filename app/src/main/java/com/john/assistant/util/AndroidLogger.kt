package com.john.assistant.util

import android.util.Log
import com.john.assistant.BuildConfig
import com.john.assistant.core.util.AssistantLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes the core's logging to logcat.
 *
 * Every tag is prefixed so a developer can filter John's own output out of a
 * busy device log with `adb logcat -s John:*`. Debug logs are dropped in
 * release builds: they carry utterances and tool arguments, which is exactly
 * the sort of thing that should not sit in a shipped device's log buffer.
 */
@Singleton
class AndroidLogger @Inject constructor() : AssistantLogger {

    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(prefix(tag), message)
    }

    override fun info(tag: String, message: String) {
        Log.i(prefix(tag), message)
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        Log.w(prefix(tag), message, error)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        Log.e(prefix(tag), message, error)
    }

    private fun prefix(tag: String) = "John/$tag".take(MAX_TAG_LENGTH)

    private companion object {
        /** Logcat truncates longer tags on older API levels. */
        const val MAX_TAG_LENGTH = 23
    }
}
