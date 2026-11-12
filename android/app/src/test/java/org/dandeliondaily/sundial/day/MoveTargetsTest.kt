package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoveTargetsTest {
    private val thu = LocalDate.of(2026, 11, 12)

    private fun action(date: LocalDate, part: Part, expires: Boolean = false) = Action(
        id = 1, description = "x", displayText = "x", actionType = null, date = date.toString(), part = part,
        priorityLevel = null, timeEstimateMins = null, linkUrl = null, projectId = null, projectName = null,
        projectIcon = null, templateActionNextId = if (expires) 9 else null,
        missedActionBehavior = if (expires) "AUTO_CANCEL" else null, expires = expires, overdue = false,
    )

    private fun labels(t: List<MoveTarget>) = t.map { it.label }

    @Test
    fun morningItemDuringTheMorning() {
        val t = MoveTargets.forAction(action(thu, Part.MORNING), SundialNow(thu, Part.MORNING), thu)
        assertEquals(
            listOf("Afternoon", "Evening", "Tomorrow", "Sat", "Sun", "Mon", "Tue", "Next week", "+2 wks"),
            labels(t),
        )
        assertEquals(MoveTarget("Tomorrow", thu.plusDays(1), Part.MORNING), t[2])
        assertEquals(MoveTarget("Next week", thu.plusDays(7), Part.MORNING), t[7])
        assertEquals(MoveTarget("+2 wks", thu.plusDays(14), Part.MORNING), t[8])
    }

    @Test
    fun futureItemCanBePulledToNow() {
        val t = MoveTargets.forAction(action(thu.plusDays(3), Part.EVENING), SundialNow(thu, Part.AFTERNOON), thu)
        assertEquals(MoveTarget("Now", thu, Part.AFTERNOON), t.first())
        assertTrue(labels(t).contains("Tmrw morning"))
        assertTrue(labels(t).contains("Tomorrow"))
    }

    @Test
    fun overdueItemGetsTodayChoices() {
        val t = MoveTargets.forAction(action(thu.minusDays(2), Part.AFTERNOON), SundialNow(thu, Part.MORNING), thu)
        assertEquals(listOf("Now", "Afternoon", "Evening", "Tomorrow", "Tmrw morning"), labels(t).take(5))
    }

    @Test
    fun autoCancelRoutineStaysOnItsDay() {
        val t = MoveTargets.forAction(action(thu, Part.WAKE, expires = true), SundialNow(thu, Part.MORNING), thu)
        assertEquals(listOf("Now", "Afternoon", "Evening"), labels(t))
        assertTrue(t.all { it.date == thu })
        // Tomorrow's routine has no chips at all today.
        assertTrue(MoveTargets.forAction(action(thu.plusDays(1), Part.WAKE, expires = true), SundialNow(thu, Part.MORNING), thu).isEmpty())
    }

    @Test
    fun afterMidnightNothingLandsBeforeTheServersToday() {
        // 1:00 Friday: Sundial still shows Thursday's Evening; the server's today is Friday.
        val fri = thu.plusDays(1)
        val t = MoveTargets.forAction(action(thu, Part.EVENING), SundialNow(thu, Part.EVENING), fri)
        assertTrue(t.none { it.date.isBefore(fri) })
        assertEquals("Tomorrow", t.first().label)
        assertEquals(fri, t.first().date)
    }
}
