package com.john.assistant.presentation.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.john.assistant.core.assistant.AssistantState
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.presentation.components.ListeningOrb
import com.john.assistant.session.AssistantSideEffect

/**
 * The assistant screen.
 *
 * Structured around one idea: the orb is the interface, and everything else is
 * subordinate to it. There is no chat log here — that lives in history — because
 * a voice assistant that shows a scrolling transcript invites the user to read
 * instead of listen, and then it is a chat app with a microphone button.
 *
 * @param startListeningImmediately true when launched by the assistant gesture,
 *   where the user has already asked to be heard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    startListeningImmediately: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val assistant by viewModel.assistantState.collectAsStateWithLifecycle()
    val screen by viewModel.screenState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPermissionResult()
        if (granted) viewModel.onMicrophoneTapped()
    }

    // Permission requests raised mid-conversation ("I need phone permission to
    // make calls") arrive here as side effects rather than as tool failures.
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AssistantSideEffect.RequestPermission -> {
                    val manifestPermissions = viewModel.manifestPermissionsFor(effect.permission)
                    if (manifestPermissions.isNotEmpty()) {
                        viewModel.markPermissionAsked(effect.permission)
                        microphoneLauncher.launch(manifestPermissions.first())
                    } else {
                        runCatching {
                            context.startActivity(viewModel.settingsIntentFor(effect.permission))
                        }
                    }
                }

                is AssistantSideEffect.ShowError ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    LaunchedEffect(startListeningImmediately) {
        if (startListeningImmediately && screen.microphoneGranted) {
            viewModel.onMicrophoneTapped()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "JOHN",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(24.dp))

            Box(contentAlignment = Alignment.Center) {
                ListeningOrb(
                    state = assistant.state,
                    micLevel = assistant.micLevel,
                    size = 220.dp,
                    primary = MaterialTheme.colorScheme.primary,
                    secondary = MaterialTheme.colorScheme.secondary,
                    errorColor = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(28.dp))

            StatusLine(state = assistant.state, actionLabel = assistant.actionLabel)

            Spacer(Modifier.height(20.dp))

            // The transcript is quoted, the reply is not: the visual difference
            // makes it obvious at a glance which words are the user's own.
            AnimatedVisibility(
                visible = assistant.transcript.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = "“${assistant.transcript}”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AnimatedVisibility(
                visible = assistant.reply.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = assistant.reply,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }

            // Offering the choices as chips means a disambiguation can be
            // answered by tapping when speaking is awkward.
            AnimatedVisibility(visible = assistant.choices.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    assistant.choices.forEach { choice ->
                        AssistChip(
                            onClick = { viewModel.onChoiceSelected(choice) },
                            label = { Text(choice) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            MicrophoneButton(
                state = assistant.state,
                microphoneGranted = screen.microphoneGranted,
                onTap = {
                    if (screen.microphoneGranted) {
                        viewModel.onMicrophoneTapped()
                    } else {
                        viewModel.markPermissionAsked(PermissionKey.MICROPHONE)
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onCancel = viewModel::onCancel,
            )

            Spacer(Modifier.height(20.dp))

            EngineFooter(
                engineName = screen.engineName,
                wakeWordEnabled = screen.wakeWordEnabled,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusLine(state: AssistantState, actionLabel: String?) {
    val text = actionLabel?.takeIf { state == AssistantState.EXECUTING }
        ?: when (state) {
            AssistantState.IDLE -> "Tap the mic, or say “Hey John”"
            AssistantState.LISTENING -> "Listening…"
            AssistantState.THINKING -> "Thinking…"
            AssistantState.EXECUTING -> "Working on it…"
            AssistantState.SPEAKING -> "Speaking"
            AssistantState.AWAITING_INPUT -> "Waiting for your answer"
            AssistantState.ERROR -> "Something went wrong"
        }

    Crossfade(targetState = text, label = "status") { value ->
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MicrophoneButton(
    state: AssistantState,
    microphoneGranted: Boolean,
    onTap: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = state == AssistantState.THINKING || state == AssistantState.EXECUTING

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LargeFloatingActionButton(onClick = onTap) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                !microphoneGranted -> Icon(
                    Icons.Default.MicOff,
                    contentDescription = "Grant microphone permission",
                    modifier = Modifier.size(32.dp),
                )

                else -> Icon(
                    Icons.Default.Mic,
                    contentDescription = if (state == AssistantState.LISTENING) {
                        "Stop listening"
                    } else {
                        "Start listening"
                    },
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // A cancel button that only exists while there is something to cancel.
        AnimatedVisibility(
            visible = state != AssistantState.IDLE,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            FilledTonalIconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Stop")
            }
        }
    }
}

@Composable
private fun EngineFooter(engineName: String, wakeWordEnabled: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        Text(
            text = engineName.ifBlank { "No model loaded" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (wakeWordEnabled) "Wake word on" else "Wake word off",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
