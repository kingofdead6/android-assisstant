package com.john.assistant.presentation.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.john.assistant.ai.llm.LlmBackend
import com.john.assistant.ai.model.ModelManager
import com.john.assistant.ai.model.ModelStatus
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

    val hasRuntime: Boolean get() = backend.isSupported

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { _huggingFaceModelId.value = it.huggingFaceModelId }
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

    fun select(status: ModelStatus) {
        viewModelScope.launch {
            if (modelManager.selectModel(status.descriptor.id)) {
                settingsRepository.setActiveModel(status.descriptor.id)
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

    fun huggingFaceToken(): String = huggingFaceAuth.token().orEmpty()

    fun saveHuggingFace(token: String, modelId: String) {
        huggingFaceAuth.setToken(token)
        viewModelScope.launch {
            settingsRepository.update { it.copy(huggingFaceModelId = modelId.trim()) }
        }
    }
}
