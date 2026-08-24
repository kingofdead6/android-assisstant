package com.john.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.john.assistant.MainActivity
import com.john.assistant.R
import com.john.assistant.core.util.AssistantLogger
import com.john.assistant.session.AssistantSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps wake-word listening alive when John's window is not on screen.
 *
 * Android has no other way to hold a microphone in the background, and it is
 * strict about the terms: a visible notification, a declared service type, and
 * on Android 14 a documented reason for `specialUse`. All three are met in the
 * manifest, and the notification is deliberately *not* hidden or minimised —
 * something listening to a room should be visible in the shade the whole time
 * it is doing so.
 *
 * The service does not itself listen. It holds the process alive and starts the
 * wake-word engine through [AssistantSession]; everything else stays in the
 * singleton graph so a service restart does not lose conversation context.
 */
@AndroidEntryPoint
class AssistantForegroundService : Service() {

    @Inject lateinit var session: AssistantSession

    @Inject lateinit var logger: AssistantLogger

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                logger.info(TAG, "Stopping wake-word listening at the user's request")
                session.setWakeWordEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat()
        session.setWakeWordEnabled(true)

        // START_STICKY: if Android kills the process under memory pressure, the
        // user asked for an always-listening assistant and should get it back.
        return START_STICKY
    }

    override fun onDestroy() {
        session.setWakeWordEnabled(false)
        super.onDestroy()
    }

    /**
     * Android 14 requires the service type at `startForeground` time, and
     * rejects the call outright if it does not match the manifest.
     */
    private fun startForegroundCompat() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    },
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AssistantForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_john_notification)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.foreground_notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // The notification says John is listening; hiding its text on the
            // lock screen would defeat the point of showing it at all.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.assistant_channel_name),
            // LOW keeps it silent and un-intrusive while still always visible.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.assistant_channel_description)
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "john_assistant"
        const val ACTION_STOP = "com.john.assistant.action.STOP_LISTENING"

        private const val TAG = "ForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2

        fun start(context: Context) {
            val intent = Intent(context, AssistantForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantForegroundService::class.java))
        }
    }
}
