package de.schaeferdesigns.untiscalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeekPlannerTest {

    private fun planner() = WeekPlanner(DefaultTimetable.templates.toMutableMap(), 0)

    private fun week(monday: LocalDate) = (0L..4L).map { monday.plusDays(it) }

    private fun real(date: LocalDate, start: String, end: String, subject: String, cancelled: Boolean = false) = Lesson(
        date = date, start = LocalTime.parse(start), end = LocalTime.parse(end), subject = subject,
        rooms = listOf("X"), originalRooms = emptyList(), teachers = emptyList(), originalTeachers = emptyList(),
        cancelled = cancelled, irregular = false, exam = false, notes = emptyList(),
    )

    @Test
    fun alternatesWeeksFromReference() {
        val p = planner()
        assertEquals('A', p.weekType(LocalDate.of(2026, 9, 30)))
        assertEquals('B', p.weekType(LocalDate.of(2026, 10, 5)))
        assertEquals('A', p.weekType(LocalDate.of(2026, 10, 12)))
        assertEquals('B', p.weekType(LocalDate.of(2026, 9, 21)))
    }

    @Test
    fun fillsWholeWeekFromTimetableWithoutRealData() {
        val monday = LocalDate.of(2026, 10, 12) // A week
        val lessons = planner().plan(week(monday), emptyMap(), emptySet())
        val thursday = lessons.filter { it.date == monday.plusDays(3) }
        assertEquals(LocalTime.of(7, 45), thursday.first().start)
        assertTrue(lessons.all { it.plannedWeek == 'A' })
        // Tuesday 7th period was a one-off event and is not part of the plan.
        assertTrue(lessons.none { it.date == monday.plusDays(1) && it.start == LocalTime.of(13, 25) })
    }

    @Test
    fun realDaysReplacePlanAndLaterDaysUsePlan() {
        val monday = LocalDate.of(2026, 10, 5) // B week
        val dates = week(monday)
        val real = mapOf(
            dates[0] to listOf(real(dates[0], "09:35", "10:20", "Englisch", cancelled = true)),
            dates[1] to listOf(real(dates[1], "09:35", "11:10", "Englisch")),
            dates[2] to emptyList(), // not released yet
        )
        val lessons = planner().plan(dates, real, emptySet())
        assertEquals(1, lessons.count { it.date == dates[0] })
        assertTrue(lessons.first { it.date == dates[0] }.cancelled)
        assertTrue(lessons.filter { it.date == dates[2] }.all { it.plannedWeek == 'B' })
        assertTrue(lessons.any { it.date == dates[2] })
    }

    @Test
    fun noPlannedLessonsOnHolidays() {
        val monday = LocalDate.of(2026, 10, 26)
        val lessons = planner().plan(week(monday), emptyMap(), week(monday).toSet())
        assertTrue(lessons.isEmpty())
    }

    @Test
    fun detectsShiftedWeekFromRealData() {
        // Wednesday of a week the counter calls B, but Untis shows physics in period 10 and 11 (A week).
        val wednesday = LocalDate.of(2026, 10, 7)
        val p = planner()
        val day = DefaultTimetable.templates.getValue("A3").map { it.toLesson(wednesday, 'A').copy(plannedWeek = null) }
        p.plan(listOf(wednesday), mapOf(wednesday to day), emptySet())
        assertEquals('A', p.weekType(wednesday))
        assertEquals(1, p.weekOffset)
    }

    @Test
    fun learnsRealSubjectNames() {
        val monday = LocalDate.of(2026, 10, 12) // A week
        val p = planner()
        val day = DefaultTimetable.templates.getValue("A1").map {
            it.toLesson(monday, 'A').copy(plannedWeek = null, subject = it.subject.uppercase())
        }
        p.plan(listOf(monday), mapOf(monday to day), emptySet())
        assertEquals("SPORT", p.templates.getValue("A1").last().subject)
        assertEquals(p.templates, WeekPlanner.fromJson(WeekPlanner.toJson(p.templates)))
    }
}
