package com.john.assistant.presentation.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.john.assistant.permissions.PermissionAction
import com.john.assistant.permissions.PermissionState
import com.john.assistant.permissions.PermissionStatus

/**
 * The permissions dashboard.
 *
 * Every row states what the permission unlocks and how it is granted, because
 * Android's own dialogs say neither. It also distinguishes the three ways a
 * permission can be missing — never asked, permanently denied, or a settings
 * screen John cannot open a dialog for — and offers the button that will
 * actually work in each case, rather than one that silently does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val states by viewModel.states.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "John asks for these one at a time, when a feature first " +
                        "needs one. Nothing here is required to start using it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            items(states, key = { it.key.name }) { state ->
                PermissionCard(
                    state = state,
                    onAction = {
                        when (state.action) {
                            PermissionAction.REQUEST -> {
                                viewModel.markAsked(state.key)
                                requestLauncher.launch(
                                    viewModel.manifestPermissionsFor(state.key).toTypedArray(),
                                )
                            }

                            PermissionAction.OPEN_SETTINGS_SCREEN,
                            PermissionAction.OPEN_APP_SETTINGS,
                            -> runCatching {
                                context.startActivity(viewModel.settingsIntentFor(state.key))
                            }

                            PermissionAction.NONE -> Unit
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(state: PermissionState, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = state.label, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = state.status.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = state.status.tint(),
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = state.rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.action != PermissionAction.NONE) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.action.explanation(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction) { Text(state.action.buttonLabel()) }
            }
        }
    }
}

private fun PermissionStatus.label(): String = when (this) {
    PermissionStatus.GRANTED -> "Granted"
    PermissionStatus.NOT_REQUIRED -> "Not needed"
    PermissionStatus.DENIED -> "Not granted"
    PermissionStatus.PERMANENTLY_DENIED -> "Blocked"
    PermissionStatus.NEEDS_SETTINGS_VISIT -> "Off"
}

@Composable
private fun PermissionStatus.tint() = when (this) {
    PermissionStatus.GRANTED, PermissionStatus.NOT_REQUIRED -> MaterialTheme.colorScheme.secondary
    PermissionStatus.PERMANENTLY_DENIED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

private fun PermissionAction.buttonLabel(): String = when (this) {
    PermissionAction.REQUEST -> "Allow"
    PermissionAction.OPEN_SETTINGS_SCREEN -> "Open Android settings"
    PermissionAction.OPEN_APP_SETTINGS -> "Open app settings"
    PermissionAction.NONE -> ""
}

private fun PermissionAction.explanation(): String = when (this) {
    PermissionAction.REQUEST -> "Android will ask you to confirm."
    PermissionAction.OPEN_SETTINGS_SCREEN ->
        "Android has no dialog for this one — you turn it on from a settings " +
            "screen, then come back. Find John in the list."
    PermissionAction.OPEN_APP_SETTINGS ->
        "Android won't show the dialog again after two refusals. You can still " +
            "turn it on from John's app settings."
    PermissionAction.NONE -> ""
}
