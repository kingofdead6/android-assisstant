package com.john.assistant.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** A reminder John is holding for the user. */
data class ScheduledReminder(
    val id: Int,
    val text: String,
    val triggerAtMillis: Long,
)

/**
 * Alarms and reminders — two different mechanisms, on purpose.
 *
 * **Alarms** are handed to the user's clock app via `AlarmClock.ACTION_SET_ALARM`.
 * That is the documented interop route: it needs no permission, it lands in the
 * app the user already trusts with waking them up, and it survives John being
 * uninstalled. Writing an alarm clock inside an assistant would be worse in
 * every one of those respects.
 *
 * **Reminders** are John's own, because there is no equivalent system intent
 * for "remind me to study at eight". They use `AlarmManager` with an exact
 * trigger, which needs `USE_EXACT_ALARM` (or the user's grant of
 * `SCHEDULE_EXACT_ALARM` on Android 12) — a reminder that drifts by fifteen
 * minutes in Doze is not a reminder. [canScheduleExact] tells the caller
 * whether the exact path is available so it can say so rather than silently
 * scheduling something inexact.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
        else alarmManager?.canScheduleExactAlarms() == true

    /**
     * Hand an alarm to the clock app.
     *
     * `EXTRA_SKIP_UI` asks it not to open its own screen. Clock apps are
     * allowed to ignore that, so the caller must not promise the alarm was set
     * silently — only that it was set.
     */
    fun setAlarm(hour: Int, minute: Int, label: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // No clock app that handles the intent means the alarm cannot be set at
        // all — better to say so than to start an Activity that does not exist.
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Open the clock app's timer with [seconds] pre-filled. */
    fun setTimer(seconds: Int, label: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Schedule one of John's own reminders.
     *
     * @return the reminder, or null when it could not be scheduled.
     */
    fun scheduleReminder(text: String, triggerAtMillis: Long): ScheduledReminder? {
        val manager = alarmManager ?: return null
        val id = (triggerAtMillis / 1000L).toInt()

        val pendingIntent = reminderPendingIntent(id, text, triggerAtMillis, mutable = false)
            ?: return null

        return runCatching {
            if (canScheduleExact()) {
                // ...AndAllowWhileIdle is what gets it through Doze. Without it a
                // reminder set at night fires whenever the device next wakes.
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
            ScheduledReminder(id, text, triggerAtMillis)
        }.getOrNull()
    }

    fun cancelReminder(id: Int) {
        val pendingIntent = reminderPendingIntent(id, text = "", triggerAtMillis = 0, mutable = false)
        if (pendingIntent != null) {
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    /**
     * Turn a wall-clock time into the next epoch millisecond it occurs at.
     *
     * Rolls to tomorrow when the time has already passed today, which is what
     * "remind me at 8" means at nine in the evening.
     */
    fun nextOccurrenceOf(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= nowMillis) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun reminderPendingIntent(
        id: Int,
        text: String,
        triggerAtMillis: Long,
        mutable: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, Class.forName(REMINDER_RECEIVER)).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_REMINDER_ID, id)
            putExtra(EXTRA_REMINDER_TEXT, text)
            putExtra(EXTRA_REMINDER_TIME, triggerAtMillis)
        }

        // FLAG_IMMUTABLE is required from Android 12 and is correct regardless:
        // nothing outside John has any business rewriting a reminder's extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE

        return runCatching {
            PendingIntent.getBroadcast(context, id, intent, flags)
        }.getOrNull()
    }

    companion object {
        const val ACTION_REMINDER = "com.john.assistant.action.REMINDER"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        const val EXTRA_REMINDER_TIME = "reminder_time"

        private const val REMINDER_RECEIVER = "com.john.assistant.services.ReminderReceiver"
    }
}
