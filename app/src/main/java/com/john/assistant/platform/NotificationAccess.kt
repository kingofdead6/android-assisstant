package com.john.assistant.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One notification currently on the status bar. */
data class ActiveNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val postedAtMillis: Long,
    val isOngoing: Boolean,
)

/**
 * The bridge between the notification listener service and everything else.
 *
 * The service is created and destroyed by the system, so it cannot be injected
 * and cannot own state that outlives it. It pushes snapshots here instead.
 *
 * Privacy shape, which the notification-access rationale promises the user:
 *
 *  - notifications are held **in memory only** and never written to the
 *    database, the log, or long-term memory;
 *  - [clear] wipes them the moment access is revoked or the service dies, so
 *    revoking permission actually removes what John already saw;
 *  - [summaryByApp] exists so the common request ("read my notifications") can
 *    be answered with counts per app rather than by reading private content
 *    aloud — the body is only spoken when the user asks for a specific app.
 */
@Singleton
class NotificationAccess @Inject constructor() {

    private val _notifications = MutableStateFlow<List<ActiveNotification>>(emptyList())

    val notifications: StateFlow<List<ActiveNotification>> = _notifications.asStateFlow()

    /** True once the listener service has connected and pushed a snapshot. */
    @Volatile
    var isConnected: Boolean = false
        private set

    fun onServiceConnected() {
        isConnected = true
    }

    fun onServiceDisconnected() {
        isConnected = false
        clear()
    }

    fun update(notifications: List<ActiveNotification>) {
        // Ongoing notifications are the music player, the navigation bar and
        // John's own listening notification. Nobody means those by "my
        // notifications", so they are dropped at the boundary.
        _notifications.value = notifications
            .filterNot { it.isOngoing }
            .sortedByDescending { it.postedAtMillis }
    }

    fun clear() {
        _notifications.value = emptyList()
    }

    fun current(): List<ActiveNotification> = _notifications.value

    /** Counts per app, newest app first — the shape of the spoken summary. */
    fun summaryByApp(): List<Pair<String, Int>> =
        current()
            .groupBy { it.appLabel }
            .map { (app, items) -> app to items.size }
            .sortedByDescending { it.second }

    fun forApp(appQuery: String): List<ActiveNotification> {
        val query = appQuery.trim().lowercase()
        return current().filter {
            it.appLabel.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
    }
}
