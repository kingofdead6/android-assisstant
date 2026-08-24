package com.john.assistant.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.presentation.components.ActionRow
import com.john.assistant.presentation.components.SettingsSection
import com.john.assistant.presentation.components.SliderRow
import com.john.assistant.presentation.components.SwitchRow

/**
 * Settings.
 *
 * Grouped by what the user is trying to change rather than by which subsystem
 * owns it — Voice, Privacy, Automation — and each destructive control says what
 * it will destroy before it does. The privacy section in particular is written
 * to be readable by someone deciding whether to trust this app with a
 * microphone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenModels: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var confirmDialog by remember { mutableStateOf<ConfirmAction?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                SettingsSection(
                    title = "AI",
                    subtitle = "John works without a model. One makes it understand phrasing " +
                        "the built-in commands don't cover.",
                ) {
                    ActionRow(
                        title = "Models",
                        description = "Download and choose an on-device model",
                        trailing = "Manage",
                        onClick = onOpenModels,
                    )
                    SliderRow(
                        title = "Creativity",
                        value = settings.temperature,
                        valueRange = 0f..1f,
                        valueLabel = String.format("%.1f", settings.temperature),
                        onValueChange = viewModel::setTemperature,
                    )
                    SliderRow(
                        title = "Maximum response length",
                        value = settings.maxResponseTokens.toFloat(),
                        valueRange = 64f..1024f,
                        valueLabel = "${settings.maxResponseTokens} tokens",
                        steps = 14,
                        onValueChange = { viewModel.setMaxTokens(it.toInt()) },
                    )
                    SwitchRow(
                        title = "Rephrase results with the model",
                        description = "Slower, and the model can embellish an outcome it " +
                            "didn't observe. Off by default.",
                        checked = settings.phraseResultsWithLlm,
                        onCheckedChange = viewModel::setPhraseWithLlm,
                    )
                }
            }

            item {
                SettingsSection(title = "Voice") {
                    SwitchRow(
                        title = "Wake word",
                        description = "Listen for “Hey John”. This uses noticeably more " +
                            "battery than tapping the mic.",
                        checked = settings.wakeWordEnabled,
                        onCheckedChange = viewModel::setWakeWordEnabled,
                    )
                    SwitchRow(
                        title = "Speak replies",
                        description = "Turn off to read John's answers instead",
                        checked = settings.speakResponses,
                        onCheckedChange = viewModel::setSpeakResponses,
                    )
                    SliderRow(
                        title = "Speech speed",
                        value = settings.speechRate,
                        valueRange = 0.5f..2f,
                        valueLabel = String.format("%.1f×", settings.speechRate),
                        onValueChange = viewModel::setSpeechRate,
                    )
                    SliderRow(
                        title = "Pitch",
                        value = settings.speechPitch,
                        valueRange = 0.5f..2f,
                        valueLabel = String.format("%.1f", settings.speechPitch),
                        onValueChange = viewModel::setSpeechPitch,
                    )
                    ActionRow(
                        title = "Hear a sample",
                        trailing = "Play",
                        onClick = viewModel::previewVoice,
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Privacy",
                    subtitle = "Everything below is stored on this phone and excluded from " +
                        "cloud backup.",
                ) {
                    SwitchRow(
                        title = "Memory",
                        description = "Let John remember things you explicitly ask it to",
                        checked = settings.memoryEnabled,
                        onCheckedChange = viewModel::setMemoryEnabled,
                    )
                    ActionRow(
                        title = "What John remembers",
                        description = if (memories.isEmpty()) {
                            "Nothing stored"
                        } else {
                            memories.take(3).joinToString(", ") { it.key.replace('_', ' ') }
                        },
                        trailing = if (memories.isEmpty()) null else "Clear",
                        enabled = memories.isNotEmpty(),
                        onClick = { confirmDialog = ConfirmAction.CLEAR_MEMORY },
                    )
                    SwitchRow(
                        title = "Conversation history",
                        description = "Keep a record of what you asked and what John did",
                        checked = settings.historyEnabled,
                        onCheckedChange = viewModel::setHistoryEnabled,
                    )
                    SliderRow(
                        title = "Delete history after",
                        value = settings.historyRetentionDays.toFloat(),
                        valueRange = 0f..90f,
                        valueLabel = if (settings.historyRetentionDays == 0) {
                            "Never"
                        } else {
                            "${settings.historyRetentionDays} days"
                        },
                        steps = 17,
                        onValueChange = { viewModel.setHistoryRetentionDays(it.toInt()) },
                    )
                    ActionRow(
                        title = "Delete all history",
                        trailing = "Delete",
                        onClick = { confirmDialog = ConfirmAction.CLEAR_HISTORY },
                    )
                    ActionRow(
                        title = "Permissions",
                        description = "What John can access, and why",
                        trailing = "Review",
                        onClick = onOpenPermissions,
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Automation",
                    subtitle = "How much John does without asking first.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Ask before", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RiskLevel.entries.forEach { level ->
                                FilterChip(
                                    selected = settings.confirmFrom == level,
                                    onClick = { viewModel.setConfirmFrom(level) },
                                    label = { Text(level.settingLabel()) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = settings.confirmFrom.explanation(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SwitchRow(
                        title = "Keep listening in the background",
                        description = "Runs a visible notification so the wake word works " +
                            "when John isn't on screen",
                        checked = settings.backgroundOperationEnabled,
                        onCheckedChange = viewModel::setBackgroundOperation,
                    )
                    ActionRow(
                        title = "Screen assistance",
                        description = "Optional. Lets John read the screen and tap for you " +
                            "in apps with no other way in.",
                        trailing = if (viewModel.isAccessibilityEnabled()) "On" else "Off",
                        onClick = {
                            runCatching { context.startActivity(viewModel.accessibilityIntent()) }
                        },
                    )
                    ActionRow(
                        title = "Notification access",
                        description = "Needed to read notifications and to name what's playing",
                        trailing = if (viewModel.isNotificationAccessGranted()) "On" else "Off",
                        onClick = {
                            runCatching { context.startActivity(viewModel.notificationAccessIntent()) }
                        },
                    )
                    ActionRow(
                        title = "Make John your assistant",
                        description = "Opens Android's assistant picker. Android decides " +
                            "what a third-party assistant is allowed to replace.",
                        trailing = "Open",
                        onClick = {
                            runCatching { context.startActivity(viewModel.assistantSettingsIntent()) }
                        },
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Tools",
                    subtitle = "Switching one off removes it from John entirely — it stops " +
                        "being offered to the model too.",
                ) {
                    viewModel.tools.forEach { tool ->
                        SwitchRow(
                            title = tool.name.replace('_', ' ')
                                .replaceFirstChar { it.uppercase() },
                            description = tool.description,
                            checked = tool.name !in settings.disabledTools,
                            onCheckedChange = { viewModel.setToolEnabled(tool.name, it) },
                        )
                    }
                }
            }

            item { IntegrationsSection() }

            item {
                SettingsSection(title = "Reset") {
                    ActionRow(
                        title = "Reset all settings",
                        description = "Doesn't touch history or memory",
                        trailing = "Reset",
                        onClick = { confirmDialog = ConfirmAction.RESET_SETTINGS },
                    )
                }
            }
        }
    }

    confirmDialog?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmDialog = null },
            title = { Text(action.title) },
            text = { Text(action.body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            ConfirmAction.CLEAR_MEMORY -> viewModel.clearMemory()
                            ConfirmAction.CLEAR_HISTORY -> viewModel.clearHistory()
                            ConfirmAction.RESET_SETTINGS -> viewModel.resetToDefaults()
                        }
                        confirmDialog = null
                    },
                ) { Text(action.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDialog = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class ConfirmAction(
    val title: String,
    val body: String,
    val confirmLabel: String,
) {
    CLEAR_MEMORY(
        title = "Forget everything?",
        body = "John will lose every preference and fact you asked it to remember.",
        confirmLabel = "Forget it all",
    ),
    CLEAR_HISTORY(
        title = "Delete all history?",
        body = "Every conversation is removed from this phone. This can't be undone.",
        confirmLabel = "Delete",
    ),
    RESET_SETTINGS(
        title = "Reset settings?",
        body = "Everything goes back to its default. Your history and memory are kept.",
        confirmLabel = "Reset",
    ),
}

private fun RiskLevel.settingLabel(): String = when (this) {
    RiskLevel.LOW -> "Everything"
    RiskLevel.MEDIUM -> "Balanced"
    RiskLevel.HIGH -> "Rarely"
}

private fun RiskLevel.explanation(): String = when (this) {
    RiskLevel.LOW -> "John asks before doing anything at all, even opening an app."
    RiskLevel.MEDIUM ->
        "John asks before anything other people can see — messages, calendar " +
            "events, calls. Opening apps and controlling music happen straight away."
    RiskLevel.HIGH ->
        "John only asks about things it must. Messages send without a check. " +
            "Payments and irreversible actions always ask, whatever this is set to."
}
