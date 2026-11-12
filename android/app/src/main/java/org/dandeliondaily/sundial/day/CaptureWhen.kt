package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Part
import java.time.LocalDate

/**
 * The "When" chips on the capture sheet. Picking none lets the sentence decide ("… tuesday"), which
 * defaults to today. A chip overrides the sentence. Nothing lands before the server's today.
 */
object CaptureWhen {
    fun options(now: SundialNow, serverToday: LocalDate): List<MoveTarget> {
        val out = mutableListOf<MoveTarget>()
        fun add(label: String, date: LocalDate, part: Part) {
            if (!date.isBefore(serverToday) && out.none { it.date == date && it.part == part }) {
                out += MoveTarget(label, date, part)
            }
        }
        add("Now", now.date, now.part)
        for (p in Part.entries) if (p.ordinal > now.part.ordinal) add(if (p == Part.EVENING) "Tonight" else p.label, now.date, p)
        val tomorrow = now.date.plusDays(1)
        add("Tmrw morning", tomorrow, Part.MORNING)
        add("Tmrw afternoon", tomorrow, Part.AFTERNOON)
        add("Tmrw evening", tomorrow, Part.EVENING)
        return out
    }
}
