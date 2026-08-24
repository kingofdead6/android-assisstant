package com.john.assistant.presentation.models

import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.john.assistant.ai.model.ModelState
import com.john.assistant.ai.model.ModelStatus
import com.john.assistant.data.preferences.LlmBackendChoice
import kotlinx.coroutines.launch

// .litertlm and .gguf have no registered MIME type, so the picker cannot filter
// on one without hiding the file the user came to select.
private const val ANY_MIME_TYPE = "*/*"

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
    val configuredHuggingFaceModel by viewModel.huggingFaceModelId.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val activeBackend by viewModel.activeBackend.collectAsStateWithLifecycle()
    val routesLocally = activeBackend != LlmBackendChoice.HUGGING_FACE
    var huggingFaceToken by remember { mutableStateOf(viewModel.huggingFaceToken()) }
    var huggingFaceModel by remember { mutableStateOf(configuredHuggingFaceModel) }
    LaunchedEffect(configuredHuggingFaceModel) {
        huggingFaceModel = configuredHuggingFaceModel
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                HuggingFaceCard(
                    token = huggingFaceToken,
                    modelId = huggingFaceModel,
                    isActive = !routesLocally,
                    onTokenChange = { huggingFaceToken = it },
                    onModelChange = { huggingFaceModel = it },
                    onSave = {
                        viewModel.saveHuggingFace(huggingFaceToken, huggingFaceModel)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                "John is now answering with $huggingFaceModel",
                            )
                        }
                    },
                )
            }

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
                    isImporting = isImporting,
                    routesLocally = routesLocally,
                    onDownload = { url -> viewModel.download(status, url) },
                    onImport = { uri -> viewModel.importFromUri(status, uri) },
                    onSelect = { viewModel.select(status) },
                    onDelete = { viewModel.delete(status) },
                )
            }
        }
    }
}

@Composable
private fun HuggingFaceCard(
    token: String,
    modelId: String,
    isActive: Boolean,
    onTokenChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    // The same two preconditions HuggingFaceLlmEngine enforces before it will
    // send a request. Checking them here means the button cannot save a
    // configuration that the engine would immediately refuse to use.
    val isConfigured = token.isNotBlank() && modelId.isNotBlank()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Mirrors the model rows: the card that is actually answering says
            // so, so the screen can never show two engines both looking chosen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hugging Face API", style = MaterialTheme.typography.titleLarge)
                if (isActive) {
                    Text(
                        text = "In use",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Use a hosted text-generation model instead of downloading weights to this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("API token (required)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = modelId,
                onValueChange = onModelChange,
                label = { Text("Model ID") },
                placeholder = { Text("owner/model-name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Name the missing half rather than just greying the button out.
            if (!isConfigured) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (token.isBlank()) {
                        "Add an API token to use a hosted model. The Inference API " +
                            "rejects anonymous requests."
                    } else {
                        "Add a model ID, for example HuggingFaceH4/zephyr-7b-beta."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (isActive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Prompts are sent to Hugging Face rather than staying on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSave,
                enabled = isConfigured,
            ) {
                Text(if (isActive) "Save changes" else "Use Hugging Face model")
            }
        }
    }
}

@Composable
private fun ModelCard(
    status: ModelStatus,
    isImporting: Boolean,
    routesLocally: Boolean,
    onDownload: (String) -> Unit,
    onImport: (Uri) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var urlEntry by remember { mutableStateOf(status.descriptor.downloadUrl) }
    var showUrlField by remember { mutableStateOf(false) }
    val model = status.descriptor

    // The system document picker. Launched with a wildcard MIME type
    // (ANY_MIME_TYPE) because .litertlm and .gguf have no registered type, and
    // providers that guess report application/octet-stream inconsistently —
    // filtering on that would hide the very file the user came here to pick.
    val pickModelFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showUrlField = true }) { Text("Get this model") }
                            OutlinedButton(
                                onClick = { pickModelFile.launch(arrayOf(ANY_MIME_TYPE)) },
                                enabled = !isImporting,
                            ) { Text("Import file") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Already downloaded ${model.fileName} on this phone? " +
                                "Import it instead of downloading it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

                is ModelState.Installed -> Column {
                    // Offered even when this model is already the active one, so
                    // long as answers are still going somewhere else. Without it
                    // a row reading "In use" next to a remote-routed assistant
                    // has no button that fixes the mismatch.
                    if (!status.isActive || !routesLocally) {
                        Button(onClick = onSelect) {
                            Text(if (status.isActive) "Answer with this model" else "Use this one")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (status.isActive && !routesLocally) {
                        Text(
                            text = "Installed and selected, but John is answering with the " +
                                "Hugging Face API right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(8.dp))
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showUrlField = true }) { Text("Try again") }
                        OutlinedButton(
                            onClick = { pickModelFile.launch(arrayOf(ANY_MIME_TYPE)) },
                            enabled = !isImporting,
                        ) { Text("Import file") }
                    }
                }
            }
        }
    }
}