package de.schaeferdesigns.untiscalendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

data class Lesson(
    val ids: List<Long>,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val rooms: List<String>,
    val originalRooms: List<String>,
    val teachers: List<String>,
    val originalTeachers: List<String>,
    val cancelled: Boolean,
    val irregular: Boolean,
    val exam: Boolean,
    val notes: List<String>,
) {
    /** Stable key used to find the calendar event belonging to this lesson. */
    val key: String get() = ids.joinToString("+")

    val title: String
        get() = when {
            cancelled -> "ENTFALL: $subject"
            exam -> "Prüfung: $subject"
            else -> subject
        }

    val location: String get() = rooms.joinToString(", ")

    val description: String
        get() = buildList {
            if (teachers.isNotEmpty()) add("Lehrer: " + teachers.joinToString(", "))
            when {
                cancelled -> add("Status: Entfall")
                irregular -> add("Status: Vertretung / Änderung")
            }
            if (originalTeachers.isNotEmpty()) add("Statt Lehrer: " + originalTeachers.joinToString(", "))
            if (originalRooms.isNotEmpty()) add("Statt Raum: " + originalRooms.joinToString(", "))
            addAll(notes)
            add("")
            add("Automatisch aus Untis übernommen")
        }.joinToString("\n")

    /** Changes whenever anything visible in the calendar entry changes. */
    val contentHash: String
        get() = listOf(title, location, description, start, end, date).joinToString("|").hashCode().toString()

    private fun canMergeWith(next: Lesson): Boolean {
        val gap = Duration.between(end, next.start).toMinutes()
        return date == next.date && gap in 0..MAX_MERGE_GAP_MINUTES &&
            subject == next.subject && rooms == next.rooms && teachers == next.teachers &&
            originalRooms == next.originalRooms && originalTeachers == next.originalTeachers &&
            cancelled == next.cancelled && irregular == next.irregular && exam == next.exam &&
            notes == next.notes
    }

    companion object {
        private const val MAX_MERGE_GAP_MINUTES = 5L

        fun parse(array: JSONArray): List<Lesson> =
            (0 until array.length()).mapNotNull { parseOne(array.getJSONObject(it)) }

        private fun parseOne(o: JSONObject): Lesson? {
            val date = parseDate(o.optInt("date"))
            val start = parseTime(o.optInt("startTime"))
            val end = parseTime(o.optInt("endTime"))
            if (date == null || start == null || end == null || !end.isAfter(start)) return null

            val subjects = names(o.optJSONArray("su"), preferLong = true)
            val subject = subjects.firstOrNull()
                ?: o.optString("lstext").takeIf { it.isNotBlank() }
                ?: o.optString("activityType").takeIf { it.isNotBlank() }
                ?: "Unterricht"

            val notes = listOf("substText", "info", "lstext")
                .map { o.optString(it).trim() }
                .filter { it.isNotEmpty() && it != subject }
                .distinct()

            return Lesson(
                ids = listOf(o.optLong("id")),
                date = date,
                start = start,
                end = end,
                subject = subject,
                rooms = names(o.optJSONArray("ro"), preferLong = false),
                originalRooms = originalNames(o.optJSONArray("ro")),
                teachers = names(o.optJSONArray("te"), preferLong = true),
                originalTeachers = originalNames(o.optJSONArray("te")),
                cancelled = o.optString("code") == "cancelled",
                irregular = o.optString("code") == "irregular",
                exam = o.optString("lstype") == "ex",
                notes = notes,
            )
        }

        /** Joins directly following periods of the same lesson (double lessons). */
        fun merge(lessons: List<Lesson>): List<Lesson> {
            val sorted = lessons.sortedWith(compareBy({ it.date }, { it.start }, { it.subject }))
            val result = mutableListOf<Lesson>()
            for (lesson in sorted) {
                val last = result.lastOrNull()
                if (last != null && last.canMergeWith(lesson)) {
                    result[result.lastIndex] = last.copy(ids = last.ids + lesson.ids, end = lesson.end)
                } else {
                    result += lesson
                }
            }
            return result
        }

        private fun names(array: JSONArray?, preferLong: Boolean): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val e = array.optJSONObject(i) ?: return@mapNotNull null
                val long = e.optString("longname").trim()
                val short = e.optString("name").trim()
                (if (preferLong) long.ifEmpty { short } else short.ifEmpty { long }).ifEmpty { null }
            }.distinct()
        }

        private fun originalNames(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.optString("orgname")?.trim()?.ifEmpty { null }
            }.distinct()
        }

        private fun parseDate(value: Int): LocalDate? = try {
            LocalDate.of(value / 10000, value / 100 % 100, value % 100)
        } catch (_: Exception) {
            null
        }

        private fun parseTime(value: Int): LocalTime? = try {
            LocalTime.of(value / 100, value % 100)
        } catch (_: Exception) {
            null
        }
    }
}
