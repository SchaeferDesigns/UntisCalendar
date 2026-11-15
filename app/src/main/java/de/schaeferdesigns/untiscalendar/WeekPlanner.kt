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

/** From [monday] on, weeks alternate starting with [type]. */
data class WeekAnchor(val monday: LocalDate, val type: Char)

/**
 * Decides what belongs in the calendar for every day: real Untis data where the school has
 * released it, otherwise the regular A/B timetable. A and B alternate every week, starting
 * from the latest [WeekAnchor]; a correction by the user adds a new anchor.
 * Also keeps the regular timetable up to date with what Untis delivers.
 */
class WeekPlanner(
    val templates: MutableMap<String, List<Slot>>,
    /** When each template was last taken over from Untis. Missing for templates of older app versions. */
    val learned: MutableMap<String, LocalDate> = mutableMapOf(),
    val anchors: MutableList<WeekAnchor> = mutableListOf(DEFAULT_ANCHOR),
) {

    fun weekType(date: LocalDate): Char = weekType(date, anchors)

    /**
     * The user says the week starting [monday] is [type]. Alternation is restarted at the first
     * Monday after the last school holidays before it, since holidays are what breaks the order.
     * Timetables learned since then were stored under the wrong week and are moved over.
     */
    fun correctWeekType(monday: LocalDate, type: Char, holidays: Set<LocalDate>) {
        if (weekType(monday) == type) return
        val start = restartMonday(monday, holidays)
        val startType = if (Math.floorMod(ChronoUnit.WEEKS.between(start, monday), 2L) == 0L) type else other(type)
        val oldAnchors = anchors.toList()
        anchors.removeAll { !it.monday.isBefore(start) }
        anchors += WeekAnchor(start, startType)
        anchors.sortBy { it.monday }
        relabel(start, oldAnchors)
    }

    private fun relabel(start: LocalDate, oldAnchors: List<WeekAnchor>) {
        data class Move(val from: String, val to: String, val slots: List<Slot>, val date: LocalDate)

        val moves = templates.keys.mapNotNull { key ->
            val date = learned[key]
            when {
                date != null && !date.isBefore(start) && weekType(date, oldAnchors) != weekType(date) ->
                    Move(key, "${weekType(date)}${key.substring(1)}", templates.getValue(key), date)
                // Learned by an older version without a date: most likely since the holidays, so flip it.
                date == null && templates[key] != DefaultTimetable.templates[key] -> {
                    val target = "${other(key[0])}${key.substring(1)}"
                    val dated = listOf(start, start.plusWeeks(1)).first { weekType(it) == target[0] }
                    Move(key, target, templates.getValue(key), dated.plusDays(key[1].digitToInt() - 1L))
                }
                else -> null
            }
        }
        moves.forEach { templates.remove(it.from); learned.remove(it.from) }
        for (move in moves) {
            val existing = learned[move.to]
            if (move.to !in templates || existing == null || existing.isBefore(move.date)) {
                templates[move.to] = move.slots
                learned[move.to] = move.date
            }
        }
        moves.forEach { m -> if (m.from !in templates) DefaultTimetable.templates[m.from]?.let { templates[m.from] = it } }
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
        learned[key] = date
    }

    companion object {
        /** KW 40 / 2026 was an A week. */
        val DEFAULT_ANCHOR = WeekAnchor(LocalDate.of(2026, 9, 28), 'A')

        fun other(type: Char) = if (type == 'A') 'B' else 'A'

        fun weekType(date: LocalDate, anchors: List<WeekAnchor>): Char {
            val m = monday(date)
            val anchor = anchors.filter { !it.monday.isAfter(m) }.maxByOrNull { it.monday }
                ?: anchors.minByOrNull { it.monday }
                ?: DEFAULT_ANCHOR
            val weeks = ChronoUnit.WEEKS.between(anchor.monday, m)
            return if (Math.floorMod(weeks, 2L) == 0L) anchor.type else other(anchor.type)
        }

        /** First Monday after the last holidays of at least a full week before [monday], else [monday]. */
        fun restartMonday(monday: LocalDate, holidays: Set<LocalDate>): LocalDate {
            var day = monday.minusDays(1)
            val limit = monday.minusWeeks(MAX_LOOKBACK_WEEKS)
            while (day.isAfter(limit)) {
                if (day in holidays && isSchoolDay(day)) {
                    // Count the weekday holidays in the block ending here.
                    var count = 0
                    var d = day
                    while (d in holidays || !isSchoolDay(d)) {
                        if (isSchoolDay(d)) count++
                        d = d.minusDays(1)
                    }
                    if (count >= 5) return monday(day).plusWeeks(1)
                    day = d
                } else {
                    day = day.minusDays(1)
                }
            }
            return monday
        }

        private const val MAX_LOOKBACK_WEEKS = 16L

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
