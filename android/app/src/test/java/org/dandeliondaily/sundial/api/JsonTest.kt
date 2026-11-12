package org.dandeliondaily.sundial.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    /** Shaped like a real local response for 2026-11-12, with made-up actions. */
    private val dayJson = """
        {"date":"2026-11-12","today":"2026-11-12","isToday":true,
         "overdue":[{"id":11,"description":"Return library book","displayText":"Return library book","actionType":"will",
                     "date":"2026-11-10","part":"MORNING","priorityLevel":null,"timeEstimateMins":15,"linkUrl":null,
                     "projectId":42,"projectName":"Home","projectIcon":null,"templateActionNextId":null,
                     "missedActionBehavior":null,"expires":false,"overdue":true}],
         "parts":{"WAKE":[{"id":21,"description":"<i>Stretch</i>","displayText":"Stretch","date":"2026-11-12","part":"WAKE",
                           "templateActionNextId":7,"missedActionBehavior":"AUTO_CANCEL","expires":true,"overdue":false}],
                  "MORNING":[],"AFTERNOON":[],"EVENING":[]}}
    """.trimIndent()

    @Test
    fun parsesADay() {
        val day = Json.day(JSONObject(dayJson))
        assertTrue(day.isToday)
        assertEquals(1, day.overdue.size)
        val book = day.overdue[0]
        assertEquals(15, book.timeEstimateMins)
        assertEquals("Home", book.projectName)
        assertNull(book.projectIcon)
        assertNull(book.linkUrl)
        assertNull(book.priorityLevel)
        assertFalse(book.isRoutine)

        val stretch = day.part(Part.WAKE).single()
        assertEquals("Stretch", stretch.displayText)
        assertEquals(Part.WAKE, stretch.part)
        assertTrue(stretch.isRoutine)
        assertTrue(stretch.expires)
        assertTrue(day.part(Part.EVENING).isEmpty())
    }

    @Test
    fun unknownPartFallsBackToAfternoon() {
        assertEquals(Part.AFTERNOON, Part.fromId(null))
        assertEquals(Part.AFTERNOON, Part.fromId("LATER"))
        assertEquals(Part.EVENING, Part.fromId("EVENING"))
    }

    @Test
    fun errorsCarryDandelionsCode() {
        val e = DandelionApi.error(403, """{"errorCode":"device_pending","message":"Waiting for approval."}""")
        assertEquals("device_pending", e.code)
        assertTrue(e.isPending)
        assertFalse(e.isRevoked)
        val html = DandelionApi.error(502, "<html>Bad gateway</html>")
        assertEquals("http_502", html.code)
        assertTrue(DandelionApi.error(401, """{"errorCode":"device_revoked"}""").isRevoked)
    }

    @Test
    fun apiRoot() {
        assertEquals("http://localhost:8089/dandelion/api/", DandelionApi.apiRoot(DandelionApi.LOCAL))
        assertEquals("https://dandelion-daily.org/api/", DandelionApi.apiRoot(DandelionApi.PRODUCTION))
    }
}
