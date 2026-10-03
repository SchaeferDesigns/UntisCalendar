package de.schaeferdesigns.untiscalendar

import java.time.LocalTime

/**
 * Starting point for the regular timetable (school year 2026/27, A and B week).
 * It is replaced automatically by real Untis data as soon as a day is released.
 */
object DefaultTimetable {

    private val periods = mapOf(
        1 to ("07:45" to "08:30"), 2 to ("08:35" to "09:20"),
        3 to ("09:35" to "10:20"), 4 to ("10:25" to "11:10"),
        5 to ("11:20" to "12:05"), 6 to ("12:10" to "12:55"),
        7 to ("13:25" to "14:10"), 8 to ("14:10" to "14:55"),
        9 to ("15:00" to "15:45"), 10 to ("15:50" to "16:35"),
        11 to ("16:35" to "17:20"),
    )

    private fun slot(from: Int, to: Int, subject: String, room: String, teacher: String) = Slot(
        LocalTime.parse(periods.getValue(from).first),
        LocalTime.parse(periods.getValue(to).second),
        subject, listOf(room), listOf(teacher),
    )

    private val english = { from: Int, to: Int -> slot(from, to, "Englisch", "207", "Si") }
    private val german = { from: Int, to: Int -> slot(from, to, "Deutsch", "N23", "Un") }
    private val math = { from: Int, to: Int -> slot(from, to, "Mathematik", "203", "Mx") }
    private val geo = { from: Int, to: Int -> slot(from, to, "Geographie", "V04", "Rs") }
    private val physics = { from: Int, to: Int -> slot(from, to, "Physik", "N11", "Ab") }

    private val monday = listOf(
        english(3, 3),
        slot(4, 4, "Biologie", "N06", "Od"),
        slot(5, 6, "Bildende Kunst", "200", "Wb"),
        geo(8, 9),
        slot(10, 11, "Sport", "SPC", "Ls"),
    )
    private val tuesday = listOf(english(3, 4), math(5, 6), physics(8, 9))
    private val wednesdayB = listOf(
        slot(1, 2, "Psychologie", "203", "Wt"),
        german(3, 4),
        geo(5, 6),
    )
    private val thursdayB = listOf(
        german(3, 3),
        math(4, 4),
        slot(5, 6, "Biologie", "N03", "Od"),
        slot(8, 9, "Religion", "007", "En"),
    )
    private val friday = listOf(
        slot(1, 2, "Biologie", "N01", "Od"),
        english(3, 4),
        slot(5, 6, "Geschichte", "007", "En"),
    )

    val templates: Map<String, List<Slot>> = mapOf(
        "A1" to monday, "A2" to tuesday, "A3" to wednesdayB + physics(10, 11),
        "A4" to listOf(geo(1, 2)) + thursdayB, "A5" to friday,
        "B1" to monday, "B2" to tuesday, "B3" to wednesdayB,
        "B4" to thursdayB, "B5" to friday,
    )
}
