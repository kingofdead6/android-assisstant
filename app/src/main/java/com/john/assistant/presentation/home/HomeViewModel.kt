package com.john.assistant.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.john.assistant.ai.model.ModelManager
import com.john.assistant.core.llm.LlmEngine
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.data.preferences.SettingsRepository
import com.john.assistant.permissions.PermissionManager
import com.john.assistant.session.AssistantSession
import com.john.assistant.session.AssistantSideEffect
import com.john.assistant.session.AssistantUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the home screen needs beyond the live session state. */
data class HomeScreenState(
    val wakeWordEnabled: Boolean = false,
    val microphoneGranted: Boolean = true,
    val engineName: String = "",
    val modelInstalled: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val session: AssistantSession,
    private val settingsRepository: SettingsRepository,
    private val permissionManager: PermissionManager,
    private val modelManager: ModelManager,
    private val llmEngine: LlmEngine,
) : ViewModel() {

    val assistantState: StateFlow<AssistantUiState> = session.uiState

    val effects: Flow<AssistantSideEffect> = session.effects

    private val _screenState = MutableStateFlow(HomeScreenState())
    val screenState: StateFlow<HomeScreenState> = _screenState.asStateFlow()

    val wakeWordEnabled: Flow<Boolean> = settingsRepository.settings.map { it.wakeWordEnabled }

    init {
        refresh()
    }

    /** Re-read state that can change while the app is backgrounded. */
    fun refresh() {
        permissionManager.refresh()
        modelManager.refresh()

        viewModelScope.launch {
            val settings = settingsRepository.current()
            _screenState.value = HomeScreenState(
                wakeWordEnabled = settings.wakeWordEnabled,
                microphoneGranted = permissionManager.isGranted(PermissionKey.MICROPHONE),
                engineName = llmEngine.displayName,
                modelInstalled = modelManager.activeModel() != null,
            )
        }
    }

    fun onMicrophoneTapped() {
        val state = assistantState.value.state
        if (state == com.john.assistant.core.assistant.AssistantState.LISTENING) {
            session.stopListening()
        } else {
            session.startListening()
        }
    }

    fun onTextSubmitted(text: String) {
        if (text.isBlank()) return
        session.submitText(text)
    }

    /** Tapping one of the offered options answers the same as saying it. */
    fun onChoiceSelected(label: String) {
        session.submitText(label)
    }

    fun onCancel() {
        session.cancelEverything()
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWakeWordEnabled(enabled)
            session.setWakeWordEnabled(enabled)
            refresh()
        }
    }

    fun onPermissionResult() {
        permissionManager.refresh()
        refresh()
    }

    fun markPermissionAsked(permission: PermissionKey) {
        permissionManager.markAsked(permission)
    }

    fun manifestPermissionsFor(permission: PermissionKey): List<String> =
        permissionManager.manifestPermissionsFor(permission)

    fun settingsIntentFor(permission: PermissionKey) =
        permissionManager.settingsIntentFor(permission)
}
