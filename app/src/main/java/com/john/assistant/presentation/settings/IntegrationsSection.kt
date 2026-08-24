package com.john.assistant.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.john.assistant.presentation.components.ActionRow
import com.john.assistant.presentation.components.SettingsSection

/**
 * Connected accounts.
 *
 * GitHub sign-in uses the OAuth device flow, so the screen shows a short code
 * rather than opening a redirect — see `GitHubAuth` for why that is the right
 * trade for an app with no backend. The client ID field is here because John
 * ships without one: whoever builds the app registers their own OAuth app, and
 * baking one in would tie every install to it.
 */
@Composable
fun IntegrationsSection(viewModel: IntegrationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showClientIdDialog by remember { mutableStateOf(false) }
    var clientIdEntry by remember { mutableStateOf("") }

    SettingsSection(
        title = "Integrations",
        subtitle = "Optional, and off unless you connect them. These are the only " +
            "parts of John that need the internet.",
    ) {
        if (!state.secureStorageAvailable) {
            Text(
                text = "This device can't provide encrypted storage, so John won't hold " +
                    "account tokens on it. Connected accounts are disabled.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            ActionRow(
                title = "GitHub",
                description = when {
                    state.gitHubConnected -> "Connected. John can read your repositories and notifications."
                    state.gitHubClientId == null -> "Set an OAuth client ID to connect"
                    else -> "Not connected"
                },
                trailing = if (state.gitHubConnected) "Disconnect" else "Connect",
                onClick = {
                    when {
                        state.gitHubConnected -> viewModel.disconnectGitHub()
                        state.gitHubClientId == null -> showClientIdDialog = true
                        else -> viewModel.startGitHubSignIn()
                    }
                },
            )

            ActionRow(
                title = "GitHub OAuth client ID",
                description = state.gitHubClientId ?: "Not set",
                trailing = "Change",
                onClick = {
                    clientIdEntry = state.gitHubClientId.orEmpty()
                    showClientIdDialog = true
                },
            )

            state.pendingUserCode?.let { code ->
                Column(Modifier.padding(16.dp)) {
                    Text("Finish signing in", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Go to ${state.pendingVerificationUri} and enter this code:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(text = code, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(viewModel.verificationIntent())
                            }
                        },
                    ) { Text("Open GitHub") }
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showClientIdDialog) {
        AlertDialog(
            onDismissRequest = { showClientIdDialog = false },
            title = { Text("GitHub OAuth client ID") },
            text = {
                Column {
                    Text(
                        "Register an OAuth app on GitHub with device flow enabled, then " +
                            "paste its client ID here. It's a public identifier, not a secret.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = clientIdEntry,
                        onValueChange = { clientIdEntry = it },
                        singleLine = true,
                        label = { Text("Client ID") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setGitHubClientId(clientIdEntry)
                        showClientIdDialog = false
                    },
                    enabled = clientIdEntry.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showClientIdDialog = false }) { Text("Cancel") }
            },
        )
    }
}
