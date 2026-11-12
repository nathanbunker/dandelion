package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CaptureWhenTest {
    private val thu = LocalDate.of(2026, 11, 12)

    @Test
    fun duringTheMorning() {
        val o = CaptureWhen.options(SundialNow(thu, Part.MORNING), thu)
        assertEquals(listOf("Now", "Afternoon", "Tonight", "Tmrw morning", "Tmrw afternoon", "Tmrw evening"), o.map { it.label })
        assertEquals(MoveTarget("Tonight", thu, Part.EVENING), o[2])
    }

    @Test
    fun afterMidnightOnlyTomorrowIsOffered() {
        // 1:00 Friday is still Thursday's Evening for Sundial; the server's today is Friday.
        val o = CaptureWhen.options(SundialNow(thu, Part.EVENING), thu.plusDays(1))
        assertTrue(o.none { it.date == thu })
        assertEquals("Tmrw morning", o.first().label)
    }
}
