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

        // Current week and the following one, so the next week is already filled in on the weekend.
        val today = LocalDate.now(CalendarStore.ZONE)
        val end = WeekPlanner.monday(today).plusWeeks(2).minusDays(1)
        val dates = dates(today, end)
        val (real, holidays) = fetch(settings, dates)

        val templates = TemplateStore(context)
        val planner = WeekPlanner(templates.load())
        val planned = planner.plan(dates, real, holidays)
        val desired = withKeys(planned)

        val store = CalendarStore(context)
        var state = SyncState.load(context)
        if (state.calendarId != settings.calendarId) {
            // Calendar was changed in the settings: move everything to the new one.
            state.entries.values.forEach { store.delete(it.eventId) }
            state = SyncState(settings.calendarId, mutableMapOf())
        }

        var added = 0
        var changed = 0
        var removed = 0

        val window = dates.toSet()
        val obsolete = state.entries.filter { (key, e) -> e.date in window && key !in desired }
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

        // Runs on every sync, because Google may attach default notifications after the event was synced.
        state.entries.values.filter { !it.date.isBefore(today) }.forEach { store.removeReminders(it.eventId) }

        // Past lessons stay in the calendar, but are no longer tracked.
        state.entries.entries.removeIf { it.value.date.isBefore(today.minusDays(7)) }
        state.save(context)
        templates.save(planner.templates)

        val confirmedUntil = planned.filter { it.plannedWeek == null }.maxOfOrNull { it.date }
        val time = LocalDateTime.now(CalendarStore.ZONE).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
        val day = DateTimeFormatter.ofPattern("dd.MM.")
        return buildString {
            append("Zuletzt synchronisiert: $time Uhr\n")
            append("Diese Woche: ${planner.weekType(today)} Woche\n")
            if (confirmedUntil != null) append("Untis Daten bis ${confirmedUntil.format(day)}, danach Stundenplan\n")
            append("${desired.size} Einträge, $added neu, $changed geändert, $removed entfernt")
        }
    }

    /** One key per date and start time, so a planned entry turns into the real one in place. */
    private fun withKeys(lessons: List<Lesson>): Map<String, Lesson> {
        val result = linkedMapOf<String, Lesson>()
        for (lesson in lessons) {
            val base = "${lesson.date}T${lesson.start}"
            var key = base
            var n = 2
            while (key in result) key = "$base#${n++}"
            result[key] = lesson
        }
        return result
    }

    private fun fetch(settings: Settings, dates: List<LocalDate>): Pair<Map<LocalDate, List<Lesson>>, Set<LocalDate>> {
        val start = dates.first()
        val end = dates.last()
        val client = UntisClient(settings.server, settings.school)
        client.login(settings.username, settings.password)
        try {
            val holidays = try {
                client.holidays(start, end)
            } catch (_: UntisException) {
                emptySet()
            }
            val real = try {
                val lessons = Lesson.parse(client.timetable(start, end)).groupBy { it.date }
                dates.associateWith { lessons[it].orEmpty() }
            } catch (rangeError: UntisException) {
                // Some schools reject ranges reaching beyond the released days. Ask day by day instead.
                val days = mutableMapOf<LocalDate, List<Lesson>>()
                for (day in dates) {
                    try {
                        days[day] = Lesson.parse(client.timetable(day, day))
                    } catch (_: UntisException) {
                        // Day not released yet.
                    }
                }
                if (days.isEmpty()) throw rangeError
                days
            }
            return real to holidays
        } finally {
            client.logout()
        }
    }

    private fun dates(start: LocalDate, end: LocalDate) =
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
}

/** Persists the learned regular timetable. */
class TemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("template", Context.MODE_PRIVATE)

    fun load(): MutableMap<String, List<Slot>> =
        prefs.getString("templates", null)?.let { WeekPlanner.fromJson(it) }
            ?: DefaultTimetable.templates.toMutableMap()

    fun save(templates: Map<String, List<Slot>>) {
        prefs.edit().putString("templates", WeekPlanner.toJson(templates)).apply()
    }
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
