package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DayLogicTest {
    private val parts = DayParts()
    private val thu = LocalDate.of(2026, 11, 12)

    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 12, h, m)

    @Test
    fun partsFollowNathansTimes() {
        assertEquals(SundialNow(thu, Part.WAKE), parts.now(at(4)))
        assertEquals(SundialNow(thu, Part.WAKE), parts.now(at(7, 59)))
        assertEquals(SundialNow(thu, Part.MORNING), parts.now(at(8)))
        assertEquals(SundialNow(thu, Part.AFTERNOON), parts.now(at(12)))
        assertEquals(SundialNow(thu, Part.AFTERNOON), parts.now(at(19, 59)))
        assertEquals(SundialNow(thu, Part.EVENING), parts.now(at(20)))
        assertEquals(SundialNow(thu, Part.EVENING), parts.now(at(23, 59)))
    }

    @Test
    fun beforeWakeIsStillYesterdaysEvening() {
        assertEquals(SundialNow(thu.minusDays(1), Part.EVENING), parts.now(at(0, 30)))
        assertEquals(SundialNow(thu.minusDays(1), Part.EVENING), parts.now(at(3, 59)))
    }

    @Test
    fun sectionLabels() {
        val now = SundialNow(thu, Part.AFTERNOON)
        assertEquals(When.EARLIER, DayLogic.whenIs(Part.WAKE, thu, now))
        assertEquals(When.NOW, DayLogic.whenIs(Part.AFTERNOON, thu, now))
        assertEquals(When.LATER, DayLogic.whenIs(Part.EVENING, thu, now))
        assertEquals(When.OTHER_DAY, DayLogic.whenIs(Part.EVENING, thu.plusDays(1), now))

        assertEquals("✓ Wake · done", DayLogic.sectionLabel(Part.WAKE, 0, When.EARLIER))
        assertEquals("Morning · 1 left", DayLogic.sectionLabel(Part.MORNING, 1, When.EARLIER))
        assertEquals("Afternoon · 3 left", DayLogic.sectionLabel(Part.AFTERNOON, 3, When.NOW))
        assertEquals("Evening · 4", DayLogic.sectionLabel(Part.EVENING, 4, When.LATER))
        assertEquals("Evening · none", DayLogic.sectionLabel(Part.EVENING, 0, When.OTHER_DAY))
    }

    @Test
    fun todayOpensOnTheCurrentPartOnly() {
        assertTrue(DayLogic.expandedByDefault(Part.MORNING, 3, When.NOW))
        assertFalse(DayLogic.expandedByDefault(Part.WAKE, 2, When.EARLIER))
        assertFalse(DayLogic.expandedByDefault(Part.EVENING, 2, When.LATER))
        assertTrue(DayLogic.expandedByDefault(Part.EVENING, 2, When.OTHER_DAY))
        assertFalse(DayLogic.expandedByDefault(Part.EVENING, 0, When.OTHER_DAY))
    }

    @Test
    fun titlesFollowTheWeb() {
        assertEquals("Today", DayLogic.title(thu, thu))
        assertEquals("Yesterday", DayLogic.title(thu.minusDays(1), thu))
        assertEquals("Friday", DayLogic.title(thu.plusDays(1), thu))
        assertEquals("Thursday", DayLogic.title(thu.plusDays(7), thu))
        assertEquals("Next Friday", DayLogic.title(thu.plusDays(8), thu))
        assertEquals("Next Thursday", DayLogic.title(thu.plusDays(14), thu))
        assertEquals("Fri, Nov 27", DayLogic.title(thu.plusDays(15), thu))
        assertEquals("Mon, Nov 9", DayLogic.title(thu.minusDays(3), thu))
        assertEquals("Fri, Jan 1, 2027", DayLogic.title(LocalDate.of(2027, 1, 1), thu))
        assertEquals("Thursday, November 12", DayLogic.fullDate(thu))
    }

    private fun action(id: Int, date: String?, expires: Boolean = false) = Action(
        id = id, description = "a$id", displayText = "a$id", actionType = null, date = date, part = Part.MORNING,
        priorityLevel = null, timeEstimateMins = null, linkUrl = null, projectId = null, projectName = null,
        projectIcon = null, templateActionNextId = if (expires) 5 else null,
        missedActionBehavior = if (expires) "AUTO_CANCEL" else null, expires = expires, overdue = true,
    )

    @Test
    fun autoCancelRoutinesNeverShowAsOverdue() {
        val list = listOf(action(1, "2026-11-10"), action(2, "2026-11-11", expires = true), action(3, "2026-11-11"))
        assertEquals(listOf(1, 3), DayLogic.overdue(list).map { it.id })
    }

    @Test
    fun daysLate() {
        assertEquals(2, DayLogic.daysLate(action(1, "2026-11-10"), thu))
        assertEquals("1 day late", DayLogic.lateLabel(1))
        assertEquals("2 days late", DayLogic.lateLabel(2))
        assertEquals(0, DayLogic.daysLate(action(1, null), thu))
    }

    @Test
    fun minutes() {
        assertEquals(null, DayLogic.minutes(0))
        assertEquals("20 min", DayLogic.minutes(20))
        assertEquals("1 h", DayLogic.minutes(60))
        assertEquals("1 h 30 min", DayLogic.minutes(90))
    }
}
