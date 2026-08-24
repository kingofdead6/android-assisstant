package com.john.assistant

import android.app.Application
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.data.preferences.SettingsRepository
import com.john.assistant.data.repository.ConversationRepository
import com.john.assistant.services.AssistantForegroundService
import com.john.assistant.session.AssistantSession
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup work is deliberately thin. Nothing here loads a language model, binds
 * a speech engine or opens the microphone: a cold start should put the orb on
 * screen, and anything the user has not asked for yet can wait until they do.
 *
 * The one thing that does run eagerly is history pruning, because a retention
 * setting the user chose should be honoured whether or not they open the
 * history screen to trigger it.
 */
@HiltAndroidApp
class JohnApplication : Application() {

    @Inject lateinit var conversationRepository: ConversationRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var session: AssistantSession

    @Inject lateinit var logger: AssistantLogger

    @Inject lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        applicationScope.launch {
            runCatching { conversationRepository.pruneExpired() }
                .onFailure { logger.warn(TAG, "Could not prune history", it) }

            val settings = runCatching { settingsRepository.current() }.getOrNull() ?: return@launch

            // Resume background listening only when the user asked for both the
            // wake word and background operation. Two switches, both required.
            if (settings.wakeWordEnabled && settings.backgroundOperationEnabled) {
                logger.info(TAG, "Resuming background wake-word listening")
                AssistantForegroundService.start(this@JohnApplication)
            }
        }
    }

    private companion object {
        const val TAG = "Application"
    }
}
