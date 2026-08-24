package com.john.assistant.permissions

import android.Manifest
import android.os.Build
import com.john.assistant.core.tool.PermissionKey

/**
 * How each of John's capabilities is actually granted on Android.
 *
 * Android has three different grant mechanisms and they behave nothing alike:
 * a runtime dialog, a special-access screen the user must navigate to by hand,
 * and permissions that are granted at install time. Modelling that difference
 * here — rather than in each caller — is what keeps the permissions dashboard
 * honest about what a given switch will actually do.
 */
sealed interface Permission {

    val key: PermissionKey

    /**
     * A permission granted by the standard runtime dialog.
     *
     * [manifestPermissions] can be empty on older API levels, which means the
     * capability is available without asking (POST_NOTIFICATIONS before
     * Android 13, BLUETOOTH_CONNECT before Android 12).
     */
    data class Runtime(
        override val key: PermissionKey,
        val manifestPermissions: List<String>,
    ) : Permission

    /**
     * A permission the user grants on a system settings screen.
     *
     * There is no API to request these: the app can only send the user to the
     * screen and check afterwards. The dashboard says so rather than pretending
     * a dialog will appear.
     */
    data class SpecialAccess(
        override val key: PermissionKey,
        val settingsAction: String,
    ) : Permission

    /** Available without asking on this device / API level. */
    data class Implicit(override val key: PermissionKey) : Permission
}

/**
 * The catalogue mapping John's vocabulary onto Android's.
 *
 * Built per device because the answer genuinely differs by API level.
 */
object PermissionCatalogue {

    fun forKey(key: PermissionKey, sdkInt: Int = Build.VERSION.SDK_INT): Permission = when (key) {
        PermissionKey.MICROPHONE ->
            Permission.Runtime(key, listOf(Manifest.permission.RECORD_AUDIO))

        PermissionKey.POST_NOTIFICATIONS ->
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Permission.Runtime(key, listOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                Permission.Implicit(key)
            }

        PermissionKey.NOTIFICATION_ACCESS ->
            Permission.SpecialAccess(key, ACTION_NOTIFICATION_LISTENER_SETTINGS)

        PermissionKey.PHONE_CALL ->
            Permission.Runtime(key, listOf(Manifest.permission.CALL_PHONE))

        PermissionKey.CONTACTS ->
            Permission.Runtime(key, listOf(Manifest.permission.READ_CONTACTS))

        PermissionKey.SMS ->
            Permission.Runtime(key, listOf(Manifest.permission.SEND_SMS))

        PermissionKey.CALENDAR_READ ->
            Permission.Runtime(key, listOf(Manifest.permission.READ_CALENDAR))

        PermissionKey.CALENDAR_WRITE ->
            Permission.Runtime(key, listOf(Manifest.permission.WRITE_CALENDAR))

        PermissionKey.CAMERA ->
            // John launches the camera app rather than opening the camera itself,
            // which needs no permission. The flashlight tool does need one, and
            // that is the only reason CAMERA appears here.
            Permission.Runtime(key, listOf(Manifest.permission.CAMERA))

        PermissionKey.BLUETOOTH ->
            if (sdkInt >= Build.VERSION_CODES.S) {
                Permission.Runtime(key, listOf(Manifest.permission.BLUETOOTH_CONNECT))
            } else {
                Permission.Implicit(key)
            }

        PermissionKey.EXACT_ALARM ->
            if (sdkInt >= Build.VERSION_CODES.S) {
                Permission.SpecialAccess(key, ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            } else {
                Permission.Implicit(key)
            }

        PermissionKey.ACCESSIBILITY ->
            Permission.SpecialAccess(key, ACTION_ACCESSIBILITY_SETTINGS)

        PermissionKey.NETWORK ->
            // INTERNET is install-time. Whether there is a *connection* is a
            // different question, answered by DeviceEnvironment.
            Permission.Implicit(key)
    }

    fun all(sdkInt: Int = Build.VERSION.SDK_INT): List<Permission> =
        PermissionKey.entries.map { forKey(it, sdkInt) }

    // Spelled out rather than referenced from android.provider.Settings so this
    // file stays readable next to the API-level branches above.
    const val ACTION_NOTIFICATION_LISTENER_SETTINGS =
        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
    const val ACTION_ACCESSIBILITY_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS"
    const val ACTION_REQUEST_SCHEDULE_EXACT_ALARM =
        "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"
}
