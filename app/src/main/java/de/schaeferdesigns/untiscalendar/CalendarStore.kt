package de.schaeferdesigns.untiscalendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.time.ZoneId
import java.time.ZonedDateTime

data class CalendarInfo(val id: Long, val name: String, val account: String) {
    override fun toString() = "$name ($account)"
}

/** Reads and writes events through the Android calendar provider (synced to Google by Android). */
class CalendarStore(context: Context) {

    private val resolver = context.contentResolver

    fun writableCalendars(): List<CalendarInfo> {
        val result = mutableListOf<CalendarInfo>()
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}"
        )?.use { c ->
            while (c.moveToNext()) {
                result += CalendarInfo(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "")
            }
        }
        return result
    }

    fun insert(calendarId: Long, lesson: Lesson): Long {
        val values = values(lesson).apply { put(Events.CALENDAR_ID, calendarId) }
        val uri = resolver.insert(Events.CONTENT_URI, values)
            ?: throw IllegalStateException("Kalendereintrag konnte nicht angelegt werden.")
        return ContentUris.parseId(uri)
    }

    /** Returns false if the event no longer exists (e.g. deleted by hand). */
    fun update(eventId: Long, lesson: Lesson): Boolean {
        if (!exists(eventId)) return false
        return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values(lesson), null, null) > 0
    }

    fun exists(eventId: Long): Boolean =
        resolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events._ID, Events.DELETED),
            null, null, null
        )?.use { c -> c.moveToFirst() && c.getInt(1) == 0 } ?: false

    /**
     * Google adds the calendar's default notifications to new events on its own.
     * Removing them keeps the lessons silent. Returns how many were removed.
     */
    fun removeReminders(eventId: Long): Int =
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))

    fun delete(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    private fun values(lesson: Lesson) = ContentValues().apply {
        put(Events.TITLE, lesson.title)
        put(Events.EVENT_LOCATION, lesson.location)
        put(Events.DESCRIPTION, lesson.description)
        put(Events.DTSTART, ZonedDateTime.of(lesson.date, lesson.start, ZONE).toInstant().toEpochMilli())
        put(Events.DTEND, ZonedDateTime.of(lesson.date, lesson.end, ZONE).toInstant().toEpochMilli())
        put(Events.EVENT_TIMEZONE, ZONE.id)
        put(Events.HAS_ALARM, 0)
        put(Events.AVAILABILITY, if (lesson.cancelled) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
