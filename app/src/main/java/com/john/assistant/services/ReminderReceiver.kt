package com.john.assistant.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.john.assistant.MainActivity
import com.john.assistant.R
import com.john.assistant.platform.AlarmScheduler

/**
 * Fires when one of John's reminders comes due.
 *
 * A plain notification rather than a spoken announcement: a reminder can arrive
 * at any moment, including in a meeting or next to a sleeping person, and an
 * assistant that starts talking unprompted in those situations is one the user
 * turns off. Tapping the notification opens John, which is where the user can
 * ask for more.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_REMINDER) return

        val id = intent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_ID, DEFAULT_ID)
        val text = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?: return

        createChannel(context)

        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_john_notification)
            .setContentTitle(context.getString(R.string.reminder_channel_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // POST_NOTIFICATIONS may have been revoked between scheduling and now.
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(id, notification)
        }
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "john_reminders"
        const val DEFAULT_ID = 0
    }
}
