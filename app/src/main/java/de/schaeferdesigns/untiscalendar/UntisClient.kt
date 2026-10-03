package de.schaeferdesigns.untiscalendar

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class UntisException(message: String, val code: Int? = null) : Exception(message)

/** Minimal client for the WebUntis JSON-RPC API. */
class UntisClient(server: String, private val school: String) {

    private val host = normalizeServer(server)
    private var sessionId: String? = null
    private var personId = 0
    private var personType = 0

    private val endpoint: String
        get() = "https://$host/WebUntis/jsonrpc.do?school=" + URLEncoder.encode(school, "UTF-8")

    fun login(user: String, password: String) {
        val result = call(
            "authenticate",
            JSONObject()
                .put("user", user)
                .put("password", password)
                .put("client", "UntisCalendar")
        ) as JSONObject
        sessionId = result.getString("sessionId")
        personId = result.optInt("personId")
        personType = result.optInt("personType")
        if (personId == 0) {
            throw UntisException("Für dieses Konto gibt es keinen eigenen Stundenplan.")
        }
    }

    fun logout() {
        try {
            call("logout", JSONObject())
        } catch (_: Exception) {
            // Session expires on its own.
        }
        sessionId = null
    }

    fun timetable(start: LocalDate, end: LocalDate): JSONArray {
        val options = JSONObject()
            .put("element", JSONObject().put("id", personId).put("type", personType))
            .put("startDate", start.format(DATE))
            .put("endDate", end.format(DATE))
            .put("showInfo", true)
            .put("showSubstText", true)
            .put("showLsText", true)
            .put("klasseFields", JSONArray(listOf("id", "name", "longname")))
            .put("roomFields", JSONArray(listOf("id", "name", "longname")))
            .put("subjectFields", JSONArray(listOf("id", "name", "longname")))
            .put("teacherFields", JSONArray(listOf("id", "name", "longname")))
        return call("getTimetable", JSONObject().put("options", options)) as JSONArray
    }

    /** All days off (holidays, vacation) between [start] and [end]. */
    fun holidays(start: LocalDate, end: LocalDate): Set<LocalDate> {
        val result = mutableSetOf<LocalDate>()
        val array = call("getHolidays", JSONObject()) as JSONArray
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            var day = LocalDate.parse(o.getInt("startDate").toString(), DATE)
            val last = LocalDate.parse(o.getInt("endDate").toString(), DATE)
            while (!day.isAfter(last)) {
                if (!day.isBefore(start) && !day.isAfter(end)) result += day
                day = day.plusDays(1)
            }
        }
        return result
    }

    private fun call(method: String, params: JSONObject): Any {
        val body = JSONObject()
            .put("id", System.currentTimeMillis().toString())
            .put("method", method)
            .put("params", params)
            .put("jsonrpc", "2.0")
            .toString()

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            sessionId?.let { conn.setRequestProperty("Cookie", "JSESSIONID=$it") }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IOException("Leere Antwort vom Server (HTTP $status)")

            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("Unerwartete Antwort vom Server (HTTP $status)")
            }
            json.optJSONObject("error")?.let { err ->
                val code = err.optInt("code")
                throw UntisException(translateError(code, err.optString("message")), code)
            }
            return json.get("result")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val DATE = DateTimeFormatter.BASIC_ISO_DATE

        /** Accepts "xyz.webuntis.com" as well as a full URL copied from the browser. */
        fun normalizeServer(input: String): String =
            input.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore('?')

        private fun translateError(code: Int, message: String): String = when (code) {
            -8504 -> "Benutzername oder Passwort falsch."
            -8520 -> "Nicht angemeldet. Bitte Zugangsdaten prüfen."
            -8509, -8511 -> "Keine Berechtigung für den Stundenplan."
            -8998 -> "Zu viele Anmeldeversuche. Bitte später erneut versuchen."
            -7004 -> "Für diesen Zeitraum ist kein Stundenplan freigegeben."
            else -> "Untis Fehler $code: $message"
        }
    }
}
