package com.john.assistant.presentation.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.john.assistant.core.speech.TextToSpeechEngine
import com.john.assistant.core.speech.VoiceOption
import com.john.assistant.core.tool.AssistantTool
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolRegistry
import com.john.assistant.data.preferences.JohnSettings
import com.john.assistant.data.preferences.SettingsRepository
import com.john.assistant.data.repository.ConversationRepository
import com.john.assistant.integrations.huggingface.HuggingFaceAuth
import com.john.assistant.core.memory.MemoryEntry
import com.john.assistant.core.memory.MemoryStore
import com.john.assistant.permissions.PermissionManager
import com.john.assistant.session.AssistantSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val memoryStore: MemoryStore,
    private val toolRegistry: ToolRegistry,
    private val textToSpeech: TextToSpeechEngine,
    private val permissionManager: PermissionManager,
    private val session: AssistantSession,
    private val huggingFaceAuth: HuggingFaceAuth,
) : ViewModel() {

    val settings: StateFlow<JohnSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), JohnSettings())

    val memories: StateFlow<List<MemoryEntry>> = memoryStore.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    /** Every tool, so the user can see exactly what John is able to do. */
    val tools: List<AssistantTool> get() = toolRegistry.all()

    init {
        viewModelScope.launch { _voices.value = textToSpeech.availableVoices() }
    }

    // --- AI -------------------------------------------------------------

    fun setTemperature(value: Float) = update { it.copy(temperature = value) }

    fun setMaxTokens(value: Int) = update { it.copy(maxResponseTokens = value) }

    fun setSystemPrompt(value: String) = update { it.copy(systemPrompt = value) }

    fun setPhraseWithLlm(enabled: Boolean) = update { it.copy(phraseResultsWithLlm = enabled) }

    fun huggingFaceToken(): String = huggingFaceAuth.token().orEmpty()

    fun setHuggingFace(token: String, modelId: String) {
        huggingFaceAuth.setToken(token)
        update { it.copy(huggingFaceModelId = modelId.trim()) }
    }

    // --- voice ----------------------------------------------------------

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWakeWordEnabled(enabled)
            session.setWakeWordEnabled(enabled)
        }
    }

    fun setSpeechRate(value: Float) = update { it.copy(speechRate = value) }

    fun setSpeechPitch(value: Float) = update { it.copy(speechPitch = value) }

    fun setVoice(voiceId: String?) = update { it.copy(voiceId = voiceId) }

    fun setSpeakResponses(enabled: Boolean) = update { it.copy(speakResponses = enabled) }

    /** Speak a sample so the user can hear a voice change before committing. */
    fun previewVoice() {
        viewModelScope.launch {
            val current = settingsRepository.current()
            textToSpeech.speak("This is how I'll sound.", current.toSpeechSettings())
        }
    }

    // --- privacy --------------------------------------------------------

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMemoryEnabled(enabled) }
    }

    fun setHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHistoryEnabled(enabled) }
    }

    fun setHistoryRetentionDays(days: Int) = update { it.copy(historyRetentionDays = days) }

    fun forgetMemory(key: String) {
        viewModelScope.launch { memoryStore.forget(key) }
    }

    fun clearMemory() {
        viewModelScope.launch { memoryStore.clear() }
    }

    fun clearHistory() {
        viewModelScope.launch { conversationRepository.clear() }
    }

    // --- automation -----------------------------------------------------

    fun setConfirmFrom(level: RiskLevel) = update { it.copy(confirmFrom = level) }

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setToolEnabled(toolName, enabled) }
    }

    fun setBackgroundOperation(enabled: Boolean) =
        update { it.copy(backgroundOperationEnabled = enabled) }

    fun resetToDefaults() {
        viewModelScope.launch { settingsRepository.resetToDefaults() }
    }

    // --- system screens -------------------------------------------------

    fun accessibilityIntent(): Intent =
        permissionManager.settingsIntentFor(com.john.assistant.core.tool.PermissionKey.ACCESSIBILITY)

    fun notificationAccessIntent(): Intent =
        permissionManager.settingsIntentFor(com.john.assistant.core.tool.PermissionKey.NOTIFICATION_ACCESS)

    fun assistantSettingsIntent(): Intent = permissionManager.assistantSettingsIntent()

    fun isAccessibilityEnabled(): Boolean = permissionManager.isAccessibilityServiceEnabled()

    fun isNotificationAccessGranted(): Boolean = permissionManager.isNotificationAccessGranted()

    private fun update(transform: (JohnSettings) -> JohnSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
