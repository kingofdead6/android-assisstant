package com.john.assistant.presentation.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.john.assistant.ai.llm.LlmBackend
import com.john.assistant.ai.model.ModelManager
import com.john.assistant.ai.model.ModelStatus
import com.john.assistant.data.preferences.LlmBackendChoice
import com.john.assistant.data.preferences.SettingsRepository
import com.john.assistant.integrations.huggingface.HuggingFaceAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val settingsRepository: SettingsRepository,
    private val backend: LlmBackend,
    private val huggingFaceAuth: HuggingFaceAuth,
) : ViewModel() {

    private val _statuses = MutableStateFlow(modelManager.statuses())
    val statuses: StateFlow<List<ModelStatus>> = _statuses.asStateFlow()

    private val _deviceRamMb = MutableStateFlow(modelManager.deviceRamMb())
    val deviceRamMb: StateFlow<Long> = _deviceRamMb.asStateFlow()

    private val _runtimeName = MutableStateFlow(backend.name)
    val runtimeName: StateFlow<String> = _runtimeName.asStateFlow()

    private val _huggingFaceModelId = MutableStateFlow("")
    val huggingFaceModelId: StateFlow<String> = _huggingFaceModelId.asStateFlow()

    private val _activeBackend = MutableStateFlow(LlmBackendChoice.DEFAULT)
    val activeBackend: StateFlow<LlmBackendChoice> = _activeBackend.asStateFlow()

    /** Set while a picked file is being copied, so the UI can block a second pick. */
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    val hasRuntime: Boolean get() = backend.isSupported

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect {
                _huggingFaceModelId.value = it.huggingFaceModelId
                _activeBackend.value = it.llmBackend
            }
        }
        // Download progress lives in the manager, so mirror its state rather
        // than duplicating it here.
        viewModelScope.launch {
            modelManager.states.collect { _statuses.value = modelManager.statuses() }
        }
        viewModelScope.launch {
            modelManager.activeModelId.collect { _statuses.value = modelManager.statuses() }
        }
    }

    fun download(status: ModelStatus, url: String) {
        viewModelScope.launch {
            modelManager.download(status.descriptor, url)
            modelManager.refresh()
            _statuses.value = modelManager.statuses()
        }
    }

    /**
     * Use an installed on-device model.
     *
     * Also routes back to [LlmBackendChoice.LOCAL]. Tapping "Use this one" on a
     * downloaded model is an unambiguous request for that model, and leaving
     * the backend pinned to Hugging Face would keep answering remotely while
     * the row said "In use" — exactly the contradiction the home screen showed.
     */
    fun select(status: ModelStatus) {
        viewModelScope.launch {
            if (modelManager.selectModel(status.descriptor.id)) {
                settingsRepository.update {
                    it.copy(
                        activeModelId = status.descriptor.id,
                        llmBackend = LlmBackendChoice.LOCAL,
                    )
                }
            }
            _statuses.value = modelManager.statuses()
        }
    }

    fun delete(status: ModelStatus) {
        viewModelScope.launch {
            modelManager.delete(status.descriptor)
            if (modelManager.activeModel() == null) settingsRepository.setActiveModel(null)
            _statuses.value = modelManager.statuses()
        }
    }

    /**
     * Copy a model the user picked with the document picker.
     *
     * The descriptor decides the destination file name, so a bundle saved under
     * any name in Downloads lands where the loader expects to find it.
     */
    fun importFromUri(status: ModelStatus, uri: Uri) {
        if (_isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            try {
                modelManager.importFromUri(status.descriptor, uri)
                _statuses.value = modelManager.statuses()
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun selectBackend(choice: LlmBackendChoice) {
        viewModelScope.launch { settingsRepository.setActiveBackend(choice) }
    }

    fun huggingFaceToken(): String = huggingFaceAuth.token().orEmpty()

    /**
     * Save the Hugging Face token and model, and route to it.
     *
     * Selecting the backend here is what makes the button do what it says:
     * "Use Hugging Face model" actually changes which engine answers, rather
     * than depending on the local model being absent.
     *
     * Routing switches only when both halves are present. Pinning the backend
     * to an unusable configuration is what produced the state in the bug
     * report — every turn failing remotely while an installed local model sat
     * unused — so an incomplete config saves its fields and leaves routing
     * alone.
     */
    fun saveHuggingFace(token: String, modelId: String) {
        val trimmedToken = token.trim()
        val trimmedModel = modelId.trim()
        huggingFaceAuth.setToken(trimmedToken)

        viewModelScope.launch {
            val usable = trimmedToken.isNotBlank() && trimmedModel.isNotBlank()
            settingsRepository.update {
                it.copy(
                    huggingFaceModelId = trimmedModel,
                    llmBackend = if (usable) LlmBackendChoice.HUGGING_FACE else it.llmBackend,
                )
            }
        }
    }

    /** Whether a token is actually stored, so the card can say what is missing. */
    fun hasHuggingFaceToken(): Boolean = !huggingFaceAuth.token().isNullOrBlank()
}
