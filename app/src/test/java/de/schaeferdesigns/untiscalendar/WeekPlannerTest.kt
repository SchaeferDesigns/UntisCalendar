package de.schaeferdesigns.untiscalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeekPlannerTest {

    private fun planner() = WeekPlanner(DefaultTimetable.templates.toMutableMap())

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

    private val autumnHolidays = (0L..4L).map { LocalDate.of(2026, 10, 26).plusDays(it) }.toSet()

    @Test
    fun correctionRestartsAfterHolidaysAndKeepsEarlierWeeks() {
        val p = planner()
        // Counter says B for the week of 16.11., the user says A.
        assertEquals('B', p.weekType(LocalDate.of(2026, 11, 16)))
        p.correctWeekType(LocalDate.of(2026, 11, 16), 'A', autumnHolidays)

        assertEquals('A', p.weekType(LocalDate.of(2026, 11, 2)))   // first week after the holidays
        assertEquals('B', p.weekType(LocalDate.of(2026, 11, 9)))
        assertEquals('A', p.weekType(LocalDate.of(2026, 11, 16)))
        assertEquals('B', p.weekType(LocalDate.of(2026, 11, 23)))
        assertEquals('A', p.weekType(LocalDate.of(2026, 10, 12)))  // before the holidays unchanged
        assertEquals('B', p.weekType(LocalDate.of(2026, 10, 19)))
    }

    @Test
    fun correctionWithoutHolidaysStartsThisWeek() {
        val p = planner()
        p.correctWeekType(LocalDate.of(2026, 11, 16), 'A', emptySet())
        assertEquals('A', p.weekType(LocalDate.of(2026, 11, 16)))
        assertEquals('A', p.weekType(LocalDate.of(2026, 11, 9)))   // earlier weeks keep the old order
    }

    @Test
    fun correctionMovesTimetablesLearnedUnderTheWrongWeek() {
        val p = planner()
        // Wednesday 11.11. counted as A, but really was a B week: B lessons got stored as A.
        val wednesday = LocalDate.of(2026, 11, 11)
        assertEquals('A', p.weekType(wednesday))
        val bDay = DefaultTimetable.templates.getValue("B3").map {
            it.toLesson(wednesday, 'B').copy(plannedWeek = null, subject = it.subject + " neu")
        }
        p.plan(listOf(wednesday), mapOf(wednesday to bDay), emptySet())
        assertEquals("Psychologie neu", p.templates.getValue("A3").first().subject)

        p.correctWeekType(LocalDate.of(2026, 11, 9), 'B', autumnHolidays)
        assertEquals('B', p.weekType(wednesday))
        assertEquals("Psychologie neu", p.templates.getValue("B3").first().subject)
        assertEquals(DefaultTimetable.templates.getValue("A3"), p.templates.getValue("A3"))
    }

    @Test
    fun undatedLearnedTimetablesAreFlipped() {
        val templates = DefaultTimetable.templates.toMutableMap()
        val learnedA = templates.getValue("A3").map { it.copy(subject = "alt") }
        templates["A3"] = learnedA
        val p = WeekPlanner(templates)
        p.correctWeekType(LocalDate.of(2026, 11, 16), 'A', autumnHolidays)
        assertEquals(learnedA, p.templates.getValue("B3"))
        assertEquals(DefaultTimetable.templates.getValue("A3"), p.templates.getValue("A3"))
    }

    @Test
    fun noChangeWhenWeekIsAlreadyRight() {
        val p = planner()
        p.correctWeekType(LocalDate.of(2026, 11, 16), 'B', autumnHolidays)
        assertEquals(listOf(WeekPlanner.DEFAULT_ANCHOR), p.anchors)
    }
}
