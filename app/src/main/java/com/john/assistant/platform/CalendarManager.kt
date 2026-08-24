package com.john.assistant.platform

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One occurrence of a calendar event. */
data class CalendarEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String?,
    val isAllDay: Boolean,
) {
    /** "Gym at 5 PM" — the phrasing John reads out. */
    fun spoken(): String {
        if (isAllDay) return "$title, all day"
        val time = TIME_FORMAT.format(Date(startMillis))
        return "$title at $time"
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.getDefault())
    }
}

/**
 * The calendar.
 *
 * Reading queries `CalendarContract.Instances`, not `Events`: an instance is a
 * single occurrence, so a weekly recurring meeting appears once on the right
 * day rather than as one Event row that the caller would have to expand itself.
 *
 * Writing goes through `ACTION_INSERT` rather than a direct provider insert.
 * That deliberately shows the user the event editor before anything is saved,
 * needs no WRITE_CALENDAR grant, and lets them pick which calendar it lands in
 * — all of which is better than John silently writing to whichever calendar it
 * guessed. [insertEventDirectly] exists for users who turn confirmation off.
 */
@Singleton
class CalendarManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Events between two instants, ordered by start time. Needs READ_CALENDAR. */
    suspend fun eventsBetween(startMillis: Long, endMillis: Long, limit: Int = 10): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            runCatching { queryInstances(startMillis, endMillis, limit) }.getOrDefault(emptyList())
        }

    suspend fun eventsToday(nowMillis: Long = System.currentTimeMillis()): List<CalendarEvent> {
        val start = startOfDay(nowMillis)
        return eventsBetween(start, start + DAY_MILLIS)
    }

    suspend fun eventsTomorrow(nowMillis: Long = System.currentTimeMillis()): List<CalendarEvent> {
        val start = startOfDay(nowMillis) + DAY_MILLIS
        return eventsBetween(start, start + DAY_MILLIS)
    }

    /** Open the calendar app's editor, pre-filled. Needs no permission. */
    fun composeEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        location: String? = null,
    ): Boolean {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        location?.takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }

        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Write straight to the provider. Needs WRITE_CALENDAR.
     *
     * @return true when a row was created. False covers both "no writable
     *   calendar exists" and a provider rejection — the caller says it could not
     *   create the event rather than guessing which.
     */
    suspend fun insertEventDirectly(
        title: String,
        startMillis: Long,
        endMillis: Long,
        location: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val calendarId = primaryWritableCalendarId() ?: return@withContext false

        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            location?.takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }

        runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        }.getOrDefault(false)
    }

    private fun queryInstances(startMillis: Long, endMillis: Long, limit: Int): List<CalendarEvent> {
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )

        // The instances table is addressed by appending the window to the URI;
        // there is no selection form of this query.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { builder ->
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)
            builder.build()
        }

        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: return emptyList()

        return cursor.use {
            buildList {
                while (it.moveToNext() && size < limit) {
                    add(
                        CalendarEvent(
                            title = it.getString(0)?.takeIf(String::isNotBlank) ?: "Untitled event",
                            startMillis = it.getLong(1),
                            endMillis = it.getLong(2),
                            location = it.getString(3),
                            isAllDay = it.getInt(4) == 1,
                        ),
                    )
                }
            }
        }
    }

    /** The first calendar the user can actually write to. */
    private fun primaryWritableCalendarId(): Long? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        ) ?: return null

        return cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
