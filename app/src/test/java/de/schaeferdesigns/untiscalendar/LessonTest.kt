package de.schaeferdesigns.untiscalendar

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class LessonTest {

    private val sample = JSONArray(
        """
        [
          {"id":1,"date":20261005,"startTime":745,"endTime":830,"su":[{"id":1,"name":"M","longname":"Mathematik"}],
           "ro":[{"id":3,"name":"A101","longname":"Raum A101"}],"te":[{"id":5,"name":"MUE","longname":"Müller"}]},
          {"id":2,"date":20261005,"startTime":830,"endTime":915,"su":[{"id":1,"name":"M","longname":"Mathematik"}],
           "ro":[{"id":3,"name":"A101","longname":"Raum A101"}],"te":[{"id":5,"name":"MUE","longname":"Müller"}]},
          {"id":3,"date":20261005,"startTime":935,"endTime":1020,"code":"cancelled","su":[{"id":2,"name":"D","longname":"Deutsch"}],
           "ro":[{"id":4,"name":"B2"}],"te":[{"id":6}]},
          {"id":4,"date":20261005,"startTime":1020,"endTime":1105,"code":"irregular","su":[{"id":3,"name":"E","longname":"Englisch"}],
           "ro":[{"id":7,"name":"C3","orgname":"B2"}],"te":[{"id":8,"name":"SCH","orgname":"MAI"}],"substText":"Raumtausch"}
        ]
        """
    )

    @Test
    fun parsesAndMergesDoubleLessons() {
        val lessons = Lesson.merge(Lesson.parse(sample))
        assertEquals(3, lessons.size)

        val math = lessons[0]
        assertEquals("Mathematik", math.title)
        assertEquals("A101", math.location)
        assertEquals(LocalTime.of(7, 45), math.start)
        assertEquals(LocalTime.of(9, 15), math.end)
        assertTrue(math.description.contains("Lehrer: Müller"))
    }

    @Test
    fun marksCancelledLessons() {
        val german = Lesson.merge(Lesson.parse(sample))[1]
        assertEquals("ENTFALL: Deutsch", german.title)
        assertTrue(german.cancelled)
        assertTrue(german.description.contains("Status: Entfall"))
    }

    @Test
    fun describesSubstitutions() {
        val english = Lesson.merge(Lesson.parse(sample))[2]
        assertEquals("Englisch", english.title)
        assertEquals("C3", english.location)
        assertTrue(english.description.contains("Statt Raum: B2"))
        assertTrue(english.description.contains("Statt Lehrer: MAI"))
        assertTrue(english.description.contains("Raumtausch"))
    }

    @Test
    fun selfStudyCountsAsCancelled() {
        val json = JSONArray(
            """[{"id":9,"date":20261005,"startTime":1410,"endTime":1455,"code":"irregular",
                 "su":[{"id":1,"name":"GEO","longname":"Geographie"}],"ro":[{"id":3,"name":"V04"}],
                 "substText":"eigenverantwortliches Arbeiten"}]"""
        )
        val lesson = Lesson.parse(json).single()
        assertTrue(lesson.cancelled)
        assertEquals("ENTFALL: Geographie", lesson.title)
    }

    @Test
    fun hashChangesWhenLessonIsCancelled() {
        val normal = Lesson.parse(sample)[0]
        assertTrue(normal.contentHash != normal.copy(cancelled = true).contentHash)
    }

    @Test
    fun normalizesServerUrl() {
        assertEquals(
            "hbg-schwaebisch-gmuend.webuntis.com",
            UntisClient.normalizeServer("https://hbg-schwaebisch-gmuend.webuntis.com/WebUntis/")
        )
    }
}
