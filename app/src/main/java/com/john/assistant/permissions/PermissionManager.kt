package com.john.assistant.permissions

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.john.assistant.core.assistant.PermissionGate
import com.john.assistant.core.tool.PermissionKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that knows what John is allowed to do.
 *
 * Everything else — tools, the orchestrator, the dashboard — asks this class
 * rather than calling `checkSelfPermission` itself. That matters because two of
 * John's capabilities (notification access, accessibility) are not runtime
 * permissions at all, and code that assumes they are would report them wrong.
 *
 * Deliberately *not* a permission requester: requesting needs an Activity, and
 * this object outlives every Activity. It reports state and hands back the
 * Intent to open; `PermissionRequester` in the UI layer does the asking.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionGate {
    /**
     * Keys the user has been asked about and declined.
     *
     * Android cannot distinguish "never asked" from "denied forever" without an
     * Activity, so John remembers what it has asked so the dashboard can offer
     * the app-settings route instead of a dialog that will not appear.
     */
    private val asked = mutableSetOf<PermissionKey>()

    private val _states = MutableStateFlow(snapshot())

    /** Dashboard state. Call [refresh] after returning from a settings screen. */
    val states: StateFlow<List<PermissionState>> = _states.asStateFlow()

    override suspend fun isGranted(permission: PermissionKey): Boolean =
        statusOf(permission).isUsable

    fun statusOf(key: PermissionKey): PermissionStatus =
        when (val permission = PermissionCatalogue.forKey(key)) {
            is Permission.Implicit -> PermissionStatus.NOT_REQUIRED

            is Permission.Runtime -> when {
                permission.manifestPermissions.isEmpty() -> PermissionStatus.NOT_REQUIRED
                permission.manifestPermissions.all(::isRuntimeGranted) -> PermissionStatus.GRANTED
                key in asked -> PermissionStatus.PERMANENTLY_DENIED
                else -> PermissionStatus.DENIED
            }

            is Permission.SpecialAccess -> when {
                isSpecialAccessGranted(key) -> PermissionStatus.GRANTED
                else -> PermissionStatus.NEEDS_SETTINGS_VISIT
            }
        }

    fun stateOf(key: PermissionKey): PermissionState =
        PermissionState(key, statusOf(key), PermissionCatalogue.forKey(key))

    /** The Android permission strings to pass to a runtime request, if any. */
    fun manifestPermissionsFor(key: PermissionKey): List<String> =
        (PermissionCatalogue.forKey(key) as? Permission.Runtime)?.manifestPermissions.orEmpty()

    /** Record that the user has now been shown the dialog for [key]. */
    fun markAsked(key: PermissionKey) {
        asked += key
        refresh()
    }

    fun refresh() {
        _states.value = snapshot()
    }

    /**
     * The Intent that takes the user where they can grant [key].
     *
     * Special-access screens are global lists rather than per-app pages, so
     * John lands the user on the right list and the UI explains which row to
     * tap. `ACTION_NOTIFICATION_LISTENER_SETTINGS` accepts a component extra on
     * Android 11+ to highlight the right entry; older versions do not.
     */
    fun settingsIntentFor(key: PermissionKey): Intent {
        val permission = PermissionCatalogue.forKey(key)
        return when {
            permission is Permission.SpecialAccess -> Intent(permission.settingsAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (key == PermissionKey.NOTIFICATION_ACCESS &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ) {
                    putExtra(
                        EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        notificationListenerComponent().flattenToString(),
                    )
                }
                if (key == PermissionKey.EXACT_ALARM) {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }

            else -> appSettingsIntent()
        }
    }

    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * The system's assistant picker.
     *
     * There is no API for an app to make itself the default assistant; this is
     * as far as an ordinary app can go. See docs/android-limitations.md.
     */
    fun assistantSettingsIntent(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun isAccessibilityServiceEnabled(): Boolean =
        isComponentInSecureSetting(
            setting = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component = accessibilityServiceComponent(),
        )

    fun isNotificationAccessGranted(): Boolean =
        isComponentInSecureSetting(
            setting = SETTING_ENABLED_NOTIFICATION_LISTENERS,
            component = notificationListenerComponent(),
        )

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.canScheduleExactAlarms() == true
        }

    private fun snapshot(): List<PermissionState> = PermissionKey.entries.map(::stateOf)

    private fun isRuntimeGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isSpecialAccessGranted(key: PermissionKey): Boolean = when (key) {
        PermissionKey.NOTIFICATION_ACCESS -> isNotificationAccessGranted()
        PermissionKey.ACCESSIBILITY -> isAccessibilityServiceEnabled()
        PermissionKey.EXACT_ALARM -> canScheduleExactAlarms()
        else -> false
    }

    /**
     * Both settings are colon-separated component lists. Substring matching
     * would be wrong — `com.john.assistant.debug/...` contains the release
     * component's package as a prefix — so entries are compared as components.
     */
    private fun isComponentInSecureSetting(setting: String, component: ComponentName): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, setting)
        }.getOrNull().orEmpty()

        return raw.split(':')
            .asSequence()
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
            .any { it == component }
    }

    private fun accessibilityServiceComponent() =
        ComponentName(context, ACCESSIBILITY_SERVICE_CLASS)

    private fun notificationListenerComponent() =
        ComponentName(context, NOTIFICATION_LISTENER_CLASS)

    private companion object {
        const val SETTING_ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        const val EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME =
            "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"

        // Referenced by name so this class does not depend on the services package,
        // which in turn depends on nearly everything else.
        const val ACCESSIBILITY_SERVICE_CLASS =
            "com.john.assistant.services.JohnAccessibilityService"
        const val NOTIFICATION_LISTENER_CLASS =
            "com.john.assistant.services.JohnNotificationListenerService"
    }
}
