package org.dandeliondaily.sundial.day

import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Day
import org.dandeliondaily.sundial.api.Part
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The last change made from the card, shown as its top row ("✓ Pay the water bill · Undo"). */
data class Receipt(
    val text: String,
    val at: Long,
    val undoToken: String? = null,
    /** For "Saved to House: …": the new action, so the row can open it. */
    val actionId: Int? = null,
)

/** One action row on the card. */
data class CardRow(
    val action: Action,
    /** Shown above this row: "from Morning", "Afternoon". */
    val header: String?,
    val leftover: Boolean,
    /** → tomorrow (leftovers that aren't Auto Cancel routines). */
    val canMove: Boolean,
    /** ✖ skip (leftover routines, which expire rather than move). */
    val canSkip: Boolean,
)

data class CardState(
    val part: Part,
    /** "Morning · 3 left", "Afternoon · 2 left · 1 from Morning", "Wake · all clear". */
    val title: String,
    /** The collapsed second line. */
    val line: String,
    val rows: List<CardRow>,
    val more: Int,
    /** Leftovers that All to tomorrow would move. */
    val movableLeftovers: List<Int>,
    val receipt: Receipt?,
    /** "as of 9:41" when the data is old. */
    val asOf: String?,
)

/**
 * What the Now card shows (Sundial Plan, changes 2, 9, 10, 11). Pure, so it is tested on the JVM.
 *
 * | Part      | Shows                                                        |
 * |-----------|--------------------------------------------------------------|
 * | Wake      | Wake items                                                   |
 * | Morning   | Morning items                                                |
 * | Afternoon | Morning leftovers first ("from Morning"), then Afternoon     |
 * | Evening   | Every open item from today (Morning and Afternoon), then Evening |
 *
 * Wake items never carry over. Up to five rows (one fewer while a receipt shows), then "+N more".
 * No ranking: the same order as the app.
 */
object CardModel {
    const val MAX_ROWS = 5
    const val RECEIPT_MS = 5 * 60 * 1000L
    const val STALE_MS = 20 * 60 * 1000L

    fun build(
        now: SundialNow,
        day: Day?,
        receipt: Receipt?,
        nowMs: Long,
        fetchedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CardState {
        val part = now.part
        val leftoverParts = when (part) {
            Part.WAKE, Part.MORNING -> emptyList()
            Part.AFTERNOON -> listOf(Part.MORNING)
            Part.EVENING -> listOf(Part.MORNING, Part.AFTERNOON)
        }
        val usable = day?.takeIf { it.date == DayLogic.key(now.date) }
        val leftovers = leftoverParts.flatMap { usable?.part(it).orEmpty() }
        val current = usable?.part(part).orEmpty()

        val all = mutableListOf<CardRow>()
        leftovers.forEachIndexed { i, a ->
            all += CardRow(
                a, header = if (i == 0) (if (part == Part.EVENING) "from today" else "from Morning") else null,
                leftover = true, canMove = !a.expires, canSkip = a.expires,
            )
        }
        current.forEachIndexed { i, a ->
            all += CardRow(
                a, header = if (i == 0 && leftovers.isNotEmpty()) part.label else null,
                leftover = false, canMove = false, canSkip = false,
            )
        }

        val liveReceipt = receipt?.takeIf { nowMs - it.at < RECEIPT_MS }
        val budget = if (liveReceipt != null) MAX_ROWS - 1 else MAX_ROWS
        val shown = all.take(budget)

        val open = all.size
        val title = when {
            usable == null -> part.label
            open == 0 -> "${part.label} · all clear"
            leftovers.isEmpty() -> "${part.label} · $open left"
            else -> "${part.label} · $open left · ${leftovers.size} from " + (if (part == Part.EVENING) "today" else "Morning")
        }
        val line = when {
            usable == null -> "Open Sundial to load today"
            all.isEmpty() -> "Nothing left in ${part.label}"
            else -> "next: " + all.first().action.displayText
        }
        val asOf = if (fetchedAt > 0 && nowMs - fetchedAt > STALE_MS) {
            "as of " + DateTimeFormatter.ofPattern("h:mm", Locale.US).format(Instant.ofEpochMilli(fetchedAt).atZone(zone))
        } else null

        return CardState(
            part = part,
            title = title,
            line = line,
            rows = shown,
            more = all.size - shown.size,
            movableLeftovers = leftovers.filter { !it.expires }.map { it.id },
            receipt = liveReceipt,
            asOf = asOf,
        )
    }
}
