package com.john.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.john.assistant.presentation.navigation.JohnApp
import com.john.assistant.presentation.theme.JohnTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The only Activity.
 *
 * It also answers `ACTION_ASSIST`, which is what puts John in Android's digital
 * assistant picker. When launched that way the user has already gestured for an
 * assistant and expects it to be listening, so the app opens straight into a
 * listening state rather than showing an idle screen with a button on it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val startListening = intent.isAssistLaunch()

        setContent {
            JohnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    JohnApp(startListeningImmediately = startListening)
                }
            }
        }
    }

    /**
     * `singleTask` means a second assistant gesture re-delivers here rather
     * than creating a new Activity, so the new intent has to be handled.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun Intent?.isAssistLaunch(): Boolean =
        this?.action == Intent.ACTION_ASSIST || this?.action == ACTION_VOICE_COMMAND

    private companion object {
        const val ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
    }
}
