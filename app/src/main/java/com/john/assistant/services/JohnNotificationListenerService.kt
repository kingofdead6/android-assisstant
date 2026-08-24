package com.john.assistant.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.platform.ActiveNotification
import com.john.assistant.platform.NotificationAccess
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Reads the notification shade, when the user has allowed it.
 *
 * Notification access is the broadest permission John asks for — it can see
 * every message, code and alert on the phone — so the handling here is
 * deliberately minimal:
 *
 *  - the service keeps **no** state of its own and writes nothing to disk;
 *  - each snapshot replaces the last in [NotificationAccess], which holds them
 *    in memory only;
 *  - on disconnect the cache is cleared, so revoking access removes what John
 *    had already seen rather than leaving a stale copy behind;
 *  - nothing is logged. A notification body in logcat is readable by anything
 *    with the right tooling.
 *
 * It also unlocks media control: `MediaSessionManager.getActiveSessions`
 * requires an enabled notification listener, which is why "what's playing?"
 * depends on this permission.
 */
@AndroidEntryPoint
class JohnNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var notificationAccess: NotificationAccess

    @Inject lateinit var logger: AssistantLogger

    override fun onListenerConnected() {
        super.onListenerConnected()
        logger.info(TAG, "Notification access connected")
        notificationAccess.onServiceConnected()
        publishSnapshot()
    }

    override fun onListenerDisconnected() {
        logger.info(TAG, "Notification access disconnected")
        notificationAccess.onServiceDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        publishSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishSnapshot()
    }

    /**
     * Republish everything currently showing.
     *
     * Rebuilding the whole list on each change rather than maintaining a
     * running one avoids the classic listener bug where a missed removal leaves
     * John reading out a notification the user dismissed an hour ago.
     */
    private fun publishSnapshot() {
        val notifications = runCatching { activeNotifications }
            .getOrNull()
            .orEmpty()
            .mapNotNull(::toActiveNotification)

        notificationAccess.update(notifications)
    }

    private fun toActiveNotification(sbn: StatusBarNotification): ActiveNotification? {
        val extras = sbn.notification?.extras ?: return null

        return ActiveNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            postedAtMillis = sbn.postTime,
            isOngoing = sbn.isOngoing,
        )
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val TAG = "NotificationListener"
    }
}
