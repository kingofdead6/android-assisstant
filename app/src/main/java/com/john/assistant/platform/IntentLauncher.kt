package com.john.assistant.platform

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts an Activity from outside an Activity.
 *
 * John dispatches most of its work from a service or a coroutine, where there
 * is no Activity context, so every launch needs `FLAG_ACTIVITY_NEW_TASK` and a
 * check that something can actually handle the Intent. Doing that in one place
 * keeps "I couldn't open that" from becoming an uncaught
 * ActivityNotFoundException in a background thread.
 */
@Singleton
class IntentLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** @return false when nothing on the device can handle [intent]. */
    fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
