package com.john.assistant.presentation.models

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.john.assistant.ai.model.ModelState
import com.john.assistant.ai.model.ModelStatus

/**
 * The model manager.
 *
 * Written to make the cost of a download obvious *before* it starts: size, RAM
 * requirement, licence, and a plain warning when the device does not have the
 * memory to run it. A gigabyte on mobile data is a real expense in a lot of the
 * world, and an assistant that starts one without being explicit is being rude
 * with someone else's money.
 *
 * The catalogue ships without download URLs on purpose — model repositories
 * move, and a dead link mid-download is worse than asking once — so each row
 * takes an address, or a file the user already has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val deviceRam by viewModel.deviceRamMb.collectAsStateWithLifecycle()
    val runtimeName by viewModel.runtimeName.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI models") },
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
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("This device", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${deviceRam} MB of RAM · inference runtime: $runtimeName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!viewModel.hasRuntime) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "This build has no on-device inference runtime, so " +
                                    "models can be downloaded but not run. John still works " +
                                    "with its built-in commands. See docs/local-ai.md.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }

            items(statuses, key = { it.descriptor.id }) { status ->
                ModelCard(
                    status = status,
                    onDownload = { url -> viewModel.download(status, url) },
                    onSelect = { viewModel.select(status) },
                    onDelete = { viewModel.delete(status) },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    status: ModelStatus,
    onDownload: (String) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var urlEntry by remember { mutableStateOf(status.descriptor.downloadUrl) }
    var showUrlField by remember { mutableStateOf(false) }
    val model = status.descriptor

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(model.displayName, style = MaterialTheme.typography.titleLarge)
                if (status.isActive) {
                    Text(
                        text = "In use",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "${model.sizeMb} MB download · needs about ${model.requiredRamMb} MB RAM · " +
                    model.licence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (model.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = model.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!status.fitsThisDevice) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "This phone probably doesn't have the memory to run this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))

            when (val state = status.state) {
                ModelState.NotInstalled -> {
                    if (showUrlField) {
                        OutlinedTextField(
                            value = urlEntry,
                            onValueChange = { urlEntry = it },
                            label = { Text("Download address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onDownload(urlEntry) },
                                enabled = urlEntry.isNotBlank(),
                            ) { Text("Download") }
                            TextButton(onClick = { showUrlField = false }) { Text("Cancel") }
                        }
                    } else {
                        Button(onClick = { showUrlField = true }) { Text("Get this model") }
                    }
                }

                is ModelState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${state.progressPercent}% · ${state.downloadedMb} MB",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                is ModelState.Installed -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!status.isActive) {
                        Button(onClick = onSelect) { Text("Use this one") }
                    }
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }

                is ModelState.Failed -> Column {
                    Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showUrlField = true }) { Text("Try again") }
                }
            }
        }
    }
}
