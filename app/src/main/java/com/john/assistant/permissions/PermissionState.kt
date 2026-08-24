package com.john.assistant.permissions

import com.john.assistant.core.tool.PermissionKey

/** Where a capability stands right now. */
enum class PermissionStatus {
    GRANTED,

    /** Not granted, and asking is still possible. */
    DENIED,

    /**
     * Denied twice, so Android will no longer show the dialog.
     *
     * The only route left is the app's settings page, and the dashboard says
     * that instead of firing a request that silently does nothing.
     */
    PERMANENTLY_DENIED,

    /** Granted on a settings screen John cannot open a dialog for. */
    NEEDS_SETTINGS_VISIT,

    /** Nothing to grant on this Android version. */
    NOT_REQUIRED,
    ;

    val isUsable: Boolean get() = this == GRANTED || this == NOT_REQUIRED
}

/** One row of the permissions dashboard. */
data class PermissionState(
    val key: PermissionKey,
    val status: PermissionStatus,
    val permission: Permission,
) {
    val label: String get() = key.label
    val rationale: String get() = key.rationale

    /** What the button on this row should do. */
    val action: PermissionAction
        get() = when {
            status.isUsable -> PermissionAction.NONE
            permission is Permission.SpecialAccess -> PermissionAction.OPEN_SETTINGS_SCREEN
            status == PermissionStatus.PERMANENTLY_DENIED -> PermissionAction.OPEN_APP_SETTINGS
            else -> PermissionAction.REQUEST
        }
}

enum class PermissionAction { NONE, REQUEST, OPEN_SETTINGS_SCREEN, OPEN_APP_SETTINGS }
