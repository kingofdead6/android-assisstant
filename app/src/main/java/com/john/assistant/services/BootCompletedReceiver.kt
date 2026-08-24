package com.john.assistant.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.john.assistant.data.preferences.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts wake-word listening after a reboot, but only if the user asked for it.
 *
 * The check matters. An assistant that starts listening again after every
 * reboot regardless of the setting is one the user cannot actually turn off,
 * and "I switched it off and it came back" is the complaint that gets an app
 * uninstalled.
 *
 * `goAsync` holds the broadcast open while DataStore is read, because a
 * BroadcastReceiver's process may be killed the instant `onReceive` returns.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val pendingResult = goAsync()
        val applicationContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.current()
                if (settings.wakeWordEnabled && settings.backgroundOperationEnabled) {
                    AssistantForegroundService.start(applicationContext)
                }
            } catch (error: Throwable) {
                // A failure here must not crash the boot broadcast.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
