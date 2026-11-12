package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.card.Boundaries
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class BoundariesTest {
    private val parts = DayParts()
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test
    fun nextSwitchIsTheNextPartStart() {
        assertEquals(at(12, 8), Boundaries.nextSwitch(parts, at(12, 7, 59)))
        assertEquals(at(12, 12), Boundaries.nextSwitch(parts, at(12, 8)))
        assertEquals(at(12, 20), Boundaries.nextSwitch(parts, at(12, 15)))
        assertEquals(at(13, 4), Boundaries.nextSwitch(parts, at(12, 21)))
        assertEquals(at(13, 4), Boundaries.nextSwitch(parts, at(13, 1)))
    }
}
