package de.schaeferdesigns.untiscalendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SyncRunner {

    /** How many days ahead are requested. The school decides how much it actually returns. */
    private const val DAYS_AHEAD = 13L

    private val mutex = Mutex()

    suspend fun sync(context: Context): String = mutex.withLock {
        withContext(Dispatchers.IO) { doSync(context.applicationContext) }
    }

    suspend fun removeAll(context: Context): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val store = CalendarStore(context)
            val state = SyncState.load(context)
            state.entries.values.forEach { store.delete(it.eventId) }
            val count = state.entries.size
            SyncState(state.calendarId, mutableMapOf()).save(context)
            count
        }
    }

    private fun doSync(context: Context): String {
        val settings = Settings(context)
        if (!settings.isComplete) throw IllegalStateException("Einstellungen unvollständig.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("Kalenderberechtigung fehlt. Bitte App öffnen.")
        }

        val today = LocalDate.now(CalendarStore.ZONE)
        val (lessons, fetchedDates) = fetch(settings, today, today.plusDays(DAYS_AHEAD))

        val store = CalendarStore(context)
        var state = SyncState.load(context)
        if (state.calendarId != settings.calendarId) {
            // Calendar was changed in the settings: move everything to the new one.
            state.entries.values.forEach { store.delete(it.eventId) }
            state = SyncState(settings.calendarId, mutableMapOf())
        }

        val desired = Lesson.merge(lessons).associateBy { it.key }
        var added = 0
        var changed = 0
        var removed = 0

        val obsolete = state.entries.filter { (key, e) -> e.date in fetchedDates && key !in desired }
        for ((key, entry) in obsolete) {
            store.delete(entry.eventId)
            state.entries.remove(key)
            removed++
        }

        for ((key, lesson) in desired) {
            val entry = state.entries[key]
            when {
                entry == null -> {
                    state.entries[key] = SyncEntry(store.insert(settings.calendarId, lesson), lesson.contentHash, lesson.date)
                    added++
                }
                entry.hash != lesson.contentHash || !store.exists(entry.eventId) -> {
                    val id = if (store.update(entry.eventId, lesson)) entry.eventId else store.insert(settings.calendarId, lesson)
                    state.entries[key] = SyncEntry(id, lesson.contentHash, lesson.date)
                    changed++
                }
            }
        }

        // Past lessons stay in the calendar, but are no longer tracked.
        state.entries.entries.removeIf { it.value.date.isBefore(today.minusDays(7)) }
        state.save(context)

        val time = LocalDateTime.now(CalendarStore.ZONE).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
        return "Zuletzt synchronisiert: $time Uhr\n${desired.size} Einträge, $added neu, $changed geändert, $removed entfernt"
    }

    private fun fetch(settings: Settings, start: LocalDate, end: LocalDate): Pair<List<Lesson>, Set<LocalDate>> {
        val client = UntisClient(settings.server, settings.school)
        client.login(settings.username, settings.password)
        try {
            return try {
                Lesson.parse(client.timetable(start, end)) to dates(start, end).toSet()
            } catch (rangeError: UntisException) {
                // Some schools reject ranges reaching beyond the released days. Ask day by day instead.
                val lessons = mutableListOf<Lesson>()
                val ok = mutableSetOf<LocalDate>()
                for (day in dates(start, end)) {
                    try {
                        lessons += Lesson.parse(client.timetable(day, day))
                        ok += day
                    } catch (_: UntisException) {
                        // Day not released yet.
                    }
                }
                if (ok.isEmpty()) throw rangeError
                lessons to ok
            }
        } finally {
            client.logout()
        }
    }

    private fun dates(start: LocalDate, end: LocalDate) =
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
}

data class SyncEntry(val eventId: Long, val hash: String, val date: LocalDate)

/** Remembers which calendar event belongs to which Untis lesson. */
class SyncState(val calendarId: Long, val entries: MutableMap<String, SyncEntry>) {

    fun save(context: Context) {
        val events = JSONObject()
        entries.forEach { (key, e) ->
            events.put(key, JSONArray().put(e.eventId).put(e.hash).put(e.date.toString()))
        }
        val json = JSONObject().put("calendarId", calendarId).put("events", events)
        context.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putString("state", json.toString()).apply()
    }

    companion object {
        fun load(context: Context): SyncState {
            val raw = context.getSharedPreferences("sync", Context.MODE_PRIVATE).getString("state", null)
                ?: return SyncState(-1, mutableMapOf())
            val json = JSONObject(raw)
            val events = json.getJSONObject("events")
            val entries = mutableMapOf<String, SyncEntry>()
            for (key in events.keys()) {
                val a = events.getJSONArray(key)
                entries[key] = SyncEntry(a.getLong(0), a.getString(1), LocalDate.parse(a.getString(2)))
            }
            return SyncState(json.getLong("calendarId"), entries)
        }
    }
}
