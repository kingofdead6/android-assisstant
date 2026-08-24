package com.john.assistant.presentation.permissions

import androidx.lifecycle.ViewModel
import com.john.assistant.core.tool.PermissionKey
import com.john.assistant.permissions.PermissionManager
import com.john.assistant.permissions.PermissionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val permissionManager: PermissionManager,
) : ViewModel() {

    val states: StateFlow<List<PermissionState>> = permissionManager.states

    init {
        // Permissions change outside the app — in Android settings, or when the
        // system revokes them for an unused app — so state is re-read on entry.
        permissionManager.refresh()
    }

    fun refresh() = permissionManager.refresh()

    fun markAsked(key: PermissionKey) = permissionManager.markAsked(key)

    fun manifestPermissionsFor(key: PermissionKey) = permissionManager.manifestPermissionsFor(key)

    fun settingsIntentFor(key: PermissionKey) = permissionManager.settingsIntentFor(key)
}
