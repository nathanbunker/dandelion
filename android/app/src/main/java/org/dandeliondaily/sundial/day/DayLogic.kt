package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Part
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * When each part of the day starts. Nathan's defaults (Sundial Plan, change 8); they become phone
 * settings in step 6. Evening runs past midnight until Wake starts the next day.
 */
data class DayParts(
    val wake: LocalTime = LocalTime.of(4, 0),
    val morning: LocalTime = LocalTime.of(8, 0),
    val afternoon: LocalTime = LocalTime.of(12, 0),
    val evening: LocalTime = LocalTime.of(20, 0),
) {
    /** The Sundial day and part for a moment. Before Wake it is still the previous day's Evening. */
    fun now(at: LocalDateTime): SundialNow {
        val t = at.toLocalTime()
        return when {
            t < wake -> SundialNow(at.toLocalDate().minusDays(1), Part.EVENING)
            t < morning -> SundialNow(at.toLocalDate(), Part.WAKE)
            t < afternoon -> SundialNow(at.toLocalDate(), Part.MORNING)
            t < evening -> SundialNow(at.toLocalDate(), Part.AFTERNOON)
            else -> SundialNow(at.toLocalDate(), Part.EVENING)
        }
    }
}

data class SundialNow(val date: LocalDate, val part: Part)

/** Where a part sits relative to now, on the day being shown. */
enum class When { EARLIER, NOW, LATER, OTHER_DAY }

object DayLogic {
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(date: String): LocalDate = LocalDate.parse(date, ISO)
    fun key(date: LocalDate): String = date.format(ISO)

    fun whenIs(part: Part, shown: LocalDate, now: SundialNow): When = when {
        shown != now.date -> When.OTHER_DAY
        part.ordinal < now.part.ordinal -> When.EARLIER
        part == now.part -> When.NOW
        else -> When.LATER
    }

    /** The section header: "✓ Wake · done", "Morning · 3 left", "Afternoon · 4", "Evening · none". */
    fun sectionLabel(part: Part, open: Int, whenIs: When): String = when (whenIs) {
        When.EARLIER, When.NOW -> if (open == 0) "✓ ${part.label} · done" else "${part.label} · $open left"
        When.LATER, When.OTHER_DAY -> if (open == 0) "${part.label} · none" else "${part.label} · $open"
    }

    /** Today opens on the current part only; another day opens every part that has something. */
    fun expandedByDefault(part: Part, open: Int, whenIs: When): Boolean = when (whenIs) {
        When.NOW -> true
        When.OTHER_DAY -> open > 0
        else -> false
    }

    /**
     * Overdue as the app shows it. Auto Cancel routines never go overdue (plan change 11): the server
     * cancels them each hour, and one that slips through shouldn't nag in the meantime.
     */
    fun overdue(actions: List<Action>): List<Action> = actions.filter { !it.expires }

    fun daysLate(action: Action, today: LocalDate): Int {
        val d = action.date ?: return 0
        return ChronoUnit.DAYS.between(parse(d), today).toInt().coerceAtLeast(0)
    }

    fun lateLabel(days: Int): String = when (days) {
        0 -> ""
        1 -> "1 day late"
        else -> "$days days late"
    }

    /**
     * The header title, in the web's wording (TodoServlet.formatTitle): "Today", the weekday for the
     * next 7 days, "Next <weekday>" for the 7 after that, otherwise the date. Yesterday is named too.
     */
    fun title(shown: LocalDate, today: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today, shown)
        return when {
            diff == 0L -> "Today"
            diff == -1L -> "Yesterday"
            diff in 1..7 -> weekday(shown.dayOfWeek)
            diff in 8..14 -> "Next " + weekday(shown.dayOfWeek)
            shown.year == today.year -> shown.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
            else -> shown.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US))
        }
    }

    /** The small line under the title: "Thursday, November 12". */
    fun fullDate(shown: LocalDate): String =
        shown.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US))

    private fun weekday(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.US)

    fun minutes(mins: Int?): String? = when {
        mins == null || mins <= 0 -> null
        mins < 60 -> "$mins min"
        mins % 60 == 0 -> "${mins / 60} h"
        else -> "${mins / 60} h ${mins % 60} min"
    }
}
