package com.john.assistant.presentation.settings

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.john.assistant.integrations.SecureTokenStore
import com.john.assistant.integrations.github.AuthResult
import com.john.assistant.integrations.github.GitHubAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IntegrationsState(
    val secureStorageAvailable: Boolean = true,
    val gitHubConnected: Boolean = false,
    val gitHubClientId: String? = null,
    /** Set while a device-flow sign-in is waiting on the user. */
    val pendingUserCode: String? = null,
    val pendingVerificationUri: String? = null,
    val message: String? = null,
)

@HiltViewModel
class IntegrationsViewModel @Inject constructor(
    private val gitHubAuth: GitHubAuth,
    private val tokenStore: SecureTokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<IntegrationsState> = _state.asStateFlow()

    fun setGitHubClientId(clientId: String) {
        gitHubAuth.setClientId(clientId)
        _state.value = snapshot()
    }

    /**
     * Start the device flow and poll until it resolves.
     *
     * The poll runs for as long as GitHub says the code is valid — up to about
     * fifteen minutes — which is fine on a viewModelScope: leaving the screen
     * cancels it, and the user has to redo a sign-in they walked away from
     * anyway.
     */
    fun startGitHubSignIn() {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = "Asking GitHub for a code…")

            val grant = gitHubAuth.requestDeviceCode().getOrElse { error ->
                _state.value = _state.value.copy(
                    message = error.message ?: "Couldn't start GitHub sign-in.",
                )
                return@launch
            }

            _state.value = _state.value.copy(
                pendingUserCode = grant.userCode,
                pendingVerificationUri = grant.verificationUri,
                message = null,
            )

            val result = gitHubAuth.pollForToken(grant)

            _state.value = snapshot().copy(
                message = when (result) {
                    AuthResult.Success -> "GitHub connected."
                    AuthResult.Cancelled -> "Sign-in cancelled."
                    is AuthResult.Failed -> result.reason
                },
            )
        }
    }

    fun disconnectGitHub() {
        gitHubAuth.disconnect()
        _state.value = snapshot().copy(message = "GitHub disconnected.")
    }

    fun verificationIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(_state.value.pendingVerificationUri ?: DEFAULT_VERIFICATION_URI),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun snapshot() = IntegrationsState(
        secureStorageAvailable = tokenStore.isAvailable,
        gitHubConnected = gitHubAuth.isConnected,
        gitHubClientId = gitHubAuth.clientId(),
    )

    private companion object {
        const val DEFAULT_VERIFICATION_URI = "https://github.com/login/device"
    }
}
