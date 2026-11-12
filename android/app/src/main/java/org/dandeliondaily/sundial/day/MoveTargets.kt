package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Part
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One move chip: where the action goes. */
data class MoveTarget(val label: String, val date: LocalDate, val part: Part)

/**
 * The move chips on the action sheet (Sundial Plan, change 4): later parts of today, Now, tomorrow,
 * tomorrow morning, the next four weekdays by name, next week and +2 weeks. Far-out moves stay one tap
 * away; anything else is *Pick a date…*.
 *
 * Auto Cancel routines only move within their own day (plan change 11): tomorrow has its own copy.
 */
object MoveTargets {

    fun forAction(action: Action, now: SundialNow, serverToday: LocalDate): List<MoveTarget> {
        val current = action.date?.let { DayLogic.parse(it) }
        val part = action.part
        val today = now.date
        val out = mutableListOf<MoveTarget>()

        fun add(label: String, date: LocalDate, p: Part) {
            if (date.isBefore(serverToday)) return // the server refuses moves into the past
            if (date == current && p == part) return // already there
            if (out.any { it.date == date && it.part == p }) return
            out += MoveTarget(label, date, p)
        }

        if (action.expires) {
            // Only within the routine's own day, and only to a part that hasn't passed.
            val day = current ?: return emptyList()
            if (day != today) return emptyList()
            for (p in Part.entries) if (p.ordinal >= now.part.ordinal) add(if (p == now.part) "Now" else p.label, day, p)
            return out
        }

        add("Now", today, now.part)
        for (p in Part.entries) if (p.ordinal > now.part.ordinal) add(p.label, today, p)
        val tomorrow = today.plusDays(1)
        add("Tomorrow", tomorrow, part)
        if (part != Part.MORNING) add("Tmrw morning", tomorrow, Part.MORNING)
        for (i in 2L..5L) {
            val d = today.plusDays(i)
            add(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US), d, part)
        }
        add("Next week", today.plusDays(7), part)
        add("+2 wks", today.plusDays(14), part)
        return out
    }
}
