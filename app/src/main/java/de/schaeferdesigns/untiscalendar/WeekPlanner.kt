package de.schaeferdesigns.untiscalendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** One lesson of the regular timetable. */
data class Slot(
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val rooms: List<String>,
    val teachers: List<String>,
) {
    fun toLesson(date: LocalDate, week: Char) = Lesson(
        date = date, start = start, end = end, subject = subject,
        rooms = rooms, originalRooms = emptyList(), teachers = teachers, originalTeachers = emptyList(),
        cancelled = false, irregular = false, exam = false, notes = emptyList(), plannedWeek = week,
    )
}

/**
 * Decides what belongs in the calendar for every day: real Untis data where the school has
 * released it, otherwise the regular A/B timetable. A and B strictly alternate every week.
 * Also keeps the regular timetable up to date with what Untis delivers.
 */
class WeekPlanner(val templates: MutableMap<String, List<Slot>>) {

    fun weekType(date: LocalDate): Char {
        val weeks = ChronoUnit.WEEKS.between(REFERENCE_A_MONDAY, monday(date))
        return if (Math.floorMod(weeks, 2L) == 0L) 'A' else 'B'
    }

    /**
     * @param real lessons per day for every day Untis answered, including days without lessons
     * @param holidays school holidays, no regular lessons are planned there
     */
    fun plan(dates: List<LocalDate>, real: Map<LocalDate, List<Lesson>>, holidays: Set<LocalDate>): List<Lesson> {
        val merged = real.mapValues { Lesson.merge(it.value) }
        val lastDayWithLessons = merged.filterValues { it.isNotEmpty() }.keys.maxOrNull()

        // An empty answer only counts as "free" if it is a holiday or later days already have lessons.
        // Otherwise the day is most likely not released yet.
        val confirmed = dates.filter { d ->
            val lessons = merged[d] ?: return@filter false
            lessons.isNotEmpty() || d in holidays || (lastDayWithLessons != null && d < lastDayWithLessons)
        }.toSet()

        val schoolDays = confirmed.filter { isSchoolDay(it) && it !in holidays && merged[it]!!.isNotEmpty() }
        schoolDays.forEach { learn(it, merged[it]!!) }

        return dates.flatMap { d ->
            when {
                d in confirmed -> merged[d]!!
                d in holidays || !isSchoolDay(d) -> emptyList()
                else -> slots(weekType(d), d).map { it.toLesson(d, weekType(d)) }
            }
        }
    }

    private fun slots(week: Char, date: LocalDate) = templates[key(week, date)].orEmpty()

    /** Takes over the real day as new regular timetable, without one-off changes. */
    private fun learn(date: LocalDate, lessons: List<Lesson>) {
        val key = key(weekType(date), date)
        val old = templates[key].orEmpty()
        val oldByStart = old.associateBy { it.start }

        val normalized = lessons.mapNotNull { l ->
            val regular = l.copy(
                rooms = l.originalRooms.ifEmpty { l.rooms },
                teachers = l.originalTeachers.ifEmpty { l.teachers },
                originalRooms = emptyList(), originalTeachers = emptyList(),
                cancelled = false, irregular = false, exam = false, notes = emptyList(),
            )
            when {
                !l.irregular -> regular
                oldByStart[l.start] != null -> oldByStart[l.start]!!.toLesson(date, 'A')
                l.originalRooms.isNotEmpty() || l.originalTeachers.isNotEmpty() -> regular
                else -> null // one-off event
            }
        }
        val slots = Lesson.merge(normalized.map { it.copy(plannedWeek = null) })
            .map { Slot(it.start, it.end, it.subject, it.rooms, it.teachers) }
            .distinct()

        // A day with far fewer lessons than usual is a special day, not the new regular plan.
        if (old.isNotEmpty() && slots.size * 2 < old.size) return
        templates[key] = slots
    }

    companion object {
        /** Monday of a known A week (KW 40 / 2026). */
        val REFERENCE_A_MONDAY: LocalDate = LocalDate.of(2026, 9, 28)

        fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun isSchoolDay(date: LocalDate) = date.dayOfWeek.value <= 5

        fun key(week: Char, date: LocalDate) = "$week${date.dayOfWeek.value}"

        fun toJson(templates: Map<String, List<Slot>>): String {
            val json = JSONObject()
            templates.forEach { (key, slots) ->
                json.put(key, JSONArray(slots.map { s ->
                    JSONObject()
                        .put("start", s.start.toString())
                        .put("end", s.end.toString())
                        .put("subject", s.subject)
                        .put("rooms", JSONArray(s.rooms))
                        .put("teachers", JSONArray(s.teachers))
                }))
            }
            return json.toString()
        }

        fun fromJson(raw: String): MutableMap<String, List<Slot>> {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, List<Slot>>()
            for (key in json.keys()) {
                val array = json.getJSONArray(key)
                result[key] = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    Slot(
                        LocalTime.parse(o.getString("start")),
                        LocalTime.parse(o.getString("end")),
                        o.getString("subject"),
                        strings(o.getJSONArray("rooms")),
                        strings(o.getJSONArray("teachers")),
                    )
                }
            }
            return result
        }

        private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    }
}
