package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Day
import org.dandeliondaily.sundial.api.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CardModelTest {
    private val thu = LocalDate.of(2026, 11, 12)
    private val zone = ZoneId.of("US/Mountain")
    private var next = 1

    private fun a(part: Part, text: String = "item ${next}", expires: Boolean = false) = Action(
        id = next++, description = text, displayText = text, actionType = null, date = thu.toString(), part = part,
        priorityLevel = null, timeEstimateMins = null, linkUrl = null, projectId = null, projectName = null,
        projectIcon = null, templateActionNextId = if (expires) 9 else null,
        missedActionBehavior = if (expires) "AUTO_CANCEL" else null, expires = expires, overdue = false,
    )

    private fun day(vararg actions: Action) = Day(
        date = thu.toString(), today = thu.toString(), isToday = true, overdue = emptyList(),
        parts = Part.entries.associateWith { p -> actions.filter { it.part == p } },
    )

    private fun build(part: Part, day: Day?, receipt: Receipt? = null, nowMs: Long = 1_000_000L, fetchedAt: Long = 1_000_000L) =
        CardModel.build(SundialNow(thu, part), day, receipt, nowMs, fetchedAt, zone)

    @Test
    fun morningShowsOnlyMorning() {
        val d = day(a(Part.WAKE, "stretch"), a(Part.MORNING, "pay the water bill"), a(Part.AFTERNOON, "mow"))
        val c = build(Part.MORNING, d)
        assertEquals("Morning · 1 left", c.title)
        assertEquals("next: pay the water bill", c.line)
        assertEquals(listOf("pay the water bill"), c.rows.map { it.action.displayText })
        assertTrue(c.movableLeftovers.isEmpty())
    }

    @Test
    fun wakeNeverCarriesOver() {
        val d = day(a(Part.WAKE, "stretch"), a(Part.MORNING, "bill"))
        val c = build(Part.AFTERNOON, d)
        assertEquals(listOf("bill"), c.rows.map { it.action.displayText })
        assertEquals("from Morning", c.rows[0].header)
    }

    @Test
    fun afternoonPutsMorningLeftoversFirst() {
        val bill = a(Part.MORNING, "bill")
        val d = day(bill, a(Part.AFTERNOON, "mow"), a(Part.AFTERNOON, "dry cleaning"))
        val c = build(Part.AFTERNOON, d)
        assertEquals("Afternoon · 3 left · 1 from Morning", c.title)
        assertEquals(listOf("bill", "mow", "dry cleaning"), c.rows.map { it.action.displayText })
        assertEquals(listOf("from Morning", "Afternoon", null), c.rows.map { it.header })
        assertTrue(c.rows[0].leftover && c.rows[0].canMove)
        assertFalse(c.rows[1].canMove)
        assertEquals(listOf(bill.id), c.movableLeftovers)
    }

    @Test
    fun eveningShowsEverythingOpenFromToday() {
        val d = day(a(Part.WAKE, "stretch"), a(Part.MORNING, "bill"), a(Part.AFTERNOON, "mow"), a(Part.EVENING, "brush teeth", expires = true))
        val c = build(Part.EVENING, d)
        assertEquals(listOf("bill", "mow", "brush teeth"), c.rows.map { it.action.displayText })
        assertEquals("from today", c.rows[0].header)
        assertEquals("Evening", c.rows[2].header)
        assertEquals("Evening · 3 left · 2 from today", c.title)
    }

    @Test
    fun leftoverRoutinesSkipInsteadOfMove() {
        val routine = a(Part.MORNING, "vitamins", expires = true)
        val c = build(Part.AFTERNOON, day(routine, a(Part.MORNING, "bill")))
        assertFalse(c.rows[0].canMove)
        assertTrue(c.rows[0].canSkip)
        assertEquals(listOf(c.rows[1].action.id), c.movableLeftovers)
    }

    @Test
    fun fiveRowsThenMore() {
        val d = day(*Array(8) { a(Part.MORNING) })
        val c = build(Part.MORNING, d)
        assertEquals(5, c.rows.size)
        assertEquals(3, c.more)
        // A receipt takes one row.
        val r = build(Part.MORNING, d, Receipt("✓ item", at = 1_000_000L - 1000))
        assertEquals(4, r.rows.size)
        assertEquals(4, r.more)
    }

    @Test
    fun receiptLastsFiveMinutes() {
        val r = Receipt("✓ bill", at = 0L, undoToken = "t")
        assertEquals(r, build(Part.MORNING, day(), r, nowMs = CardModel.RECEIPT_MS - 1).receipt)
        assertNull(build(Part.MORNING, day(), r, nowMs = CardModel.RECEIPT_MS + 1).receipt)
    }

    @Test
    fun allClearAndNotLoaded() {
        assertEquals("Wake · all clear", build(Part.WAKE, day()).title)
        assertEquals("Nothing left in Wake", build(Part.WAKE, day()).line)
        val missing = build(Part.WAKE, null)
        assertEquals("Wake", missing.title)
        assertEquals("Open Sundial to load today", missing.line)
        // Yesterday's data never shows as today.
        val old = Day(thu.minusDays(1).toString(), thu.toString(), false, emptyList(), Part.entries.associateWith { listOf(a(it)) })
        assertEquals("Open Sundial to load today", build(Part.MORNING, old).line)
    }

    @Test
    fun staleDataSaysAsOf() {
        // 2026-11-12 16:41 UTC = 9:41 Mountain.
        val fetched = java.time.Instant.parse("2026-11-12T16:41:00Z").toEpochMilli()
        assertNull(build(Part.MORNING, day(), nowMs = fetched + 60_000, fetchedAt = fetched).asOf)
        assertEquals("as of 9:41", build(Part.MORNING, day(), nowMs = fetched + CardModel.STALE_MS + 1, fetchedAt = fetched).asOf)
    }
}
