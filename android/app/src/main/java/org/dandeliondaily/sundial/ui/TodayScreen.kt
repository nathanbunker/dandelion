package org.dandeliondaily.sundial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.dandeliondaily.sundial.AppViewModel
import org.dandeliondaily.sundial.BuildConfig
import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Me
import org.dandeliondaily.sundial.api.Part
import org.dandeliondaily.sundial.day.DayLogic
import org.dandeliondaily.sundial.day.SundialNow
import org.dandeliondaily.sundial.day.When
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun TodayScreen(me: Me, vm: AppViewModel) {
    val state by vm.day.collectAsState()
    // Re-evaluate "now" every minute so the expanded part follows the clock while the app is open.
    var now by remember { mutableStateOf(vm.sundialNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = vm.sundialNow()
        }
    }
    val shown = state.date ?: now.date
    var capturing by remember { mutableStateOf(false) }
    val justAdded by vm.justAdded.collectAsState()
    LaunchedEffect(now) { vm.dropExpiredJustAdded() }

    Box(Modifier.fillMaxSize()) {

    Column(Modifier.fillMaxSize()) {
        Header(shown, now, me, vm)
        if (state.offline) OfflineStrip()
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        val day = state.day
        if (day == null) {
            if (!state.loading && !state.offline && state.error == null) Text("Nothing loaded yet.", Modifier.padding(16.dp))
            return@Column
        }
        // Expanded sections, remembered per shown date; defaults decided by DayLogic.
        val expanded = remember(shown, now.part) { mutableStateMapOf<String, Boolean>() }
        LazyColumn(Modifier.fillMaxSize().swipeDays(vm)) {
            val overdue = if (day.isToday) DayLogic.overdue(day.overdue) else emptyList()
            // Just added (P-20): what was captured in the last 10 minutes, still open, easy to find.
            val openIds = (day.overdue + Part.entries.flatMap { day.part(it) }).map { it.id }.toSet()
            val recent = justAdded.map { it.action }.filter { it.id in openIds || it.date != day.date }
            if (recent.isNotEmpty() && day.isToday) {
                section(
                    key = "just", label = "Just added · ${recent.size}", emphasis = false,
                    open = expanded["just"] ?: true, toggle = { expanded["just"] = !(expanded["just"] ?: true) },
                    actions = recent, late = { "" }, vm = vm,
                )
            }
            if (overdue.isNotEmpty()) {
                section(
                    key = "overdue", label = "Overdue · ${overdue.size}", emphasis = true,
                    open = expanded["overdue"] ?: false, toggle = { expanded["overdue"] = !(expanded["overdue"] ?: false) },
                    actions = overdue, late = { DayLogic.lateLabel(DayLogic.daysLate(it, DayLogic.parse(day.today))) }, vm = vm,
                )
            }
            for (part in Part.entries) {
                val actions = day.part(part)
                val w = DayLogic.whenIs(part, shown, now)
                val open = expanded[part.id] ?: DayLogic.expandedByDefault(part, actions.size, w)
                section(
                    key = part.id, label = DayLogic.sectionLabel(part, actions.size, w), emphasis = w == When.NOW,
                    note = if (w == When.NOW) "now" else null,
                    open = open, toggle = { expanded[part.id] = !open }, actions = actions, late = { "" }, vm = vm,
                )
            }
            item { Spacer(Modifier.padding(bottom = 96.dp)) }
        }
    }
        if (vm.canCapture()) FloatingActionButton(
            onClick = { capturing = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) { Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary) }
    }

    if (capturing) {
        val serverToday = state.day?.today?.let { DayLogic.parse(it) } ?: now.date
        CaptureSheet(serverToday, vm) { capturing = false }
    }
}

@Composable
private fun Header(shown: LocalDate, now: SundialNow, me: Me, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).swipeDays(vm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.step(-1) }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(DayLogic.title(shown, now.date), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(DayLogic.fullDate(shown), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { vm.step(1) }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (shown != now.date) TextButton(onClick = { vm.goToday() }) { Text("Today") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.refresh() }) { Text("↻") }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Signed in as ${me.firstName ?: me.username}", style = MaterialTheme.typography.bodySmall) },
                        onClick = {}, enabled = false,
                    )
                    DropdownMenuItem(
                        text = { Text("Sundial ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall) },
                        onClick = {}, enabled = false,
                    )
                    DropdownMenuItem(text = { Text("Sign out of this phone") }, onClick = { menu = false; vm.signOut() })
                }
            }
        }
        HorizontalDivider()
    }
}

/** A horizontal swipe steps one day, like ‹ and ›. */
private fun Modifier.swipeDays(vm: AppViewModel): Modifier = pointerInput(Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            if (abs(total) > 120f) vm.step(if (total < 0) 1 else -1)
            total = 0f
        },
        onHorizontalDrag = { _, dx -> total += dx },
    )
}

@Composable
private fun OfflineStrip() {
    Text(
        "Can't reach Dandelion · retrying",
        color = Color.White,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private fun LazyListScope.section(
    key: String,
    label: String,
    emphasis: Boolean,
    open: Boolean,
    toggle: () -> Unit,
    actions: List<Action>,
    late: (Action) -> String,
    vm: AppViewModel,
    note: String? = null,
) {
    item(key = "h-$key") {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = actions.isNotEmpty(), onClick = toggle)
                .heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (actions.isEmpty()) "  " else if (open) "▾ " else "▸ ",
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
                color = if (key == "overdue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            note?.let { Text("($it)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (open) {
        items(actions, key = { "$key-${it.id}" }) { a -> ActionRow(a, late(a), vm) }
    }
}

@Composable
private fun ActionRow(a: Action, lateLabel: String, vm: AppViewModel) {
    val busy by vm.busy.collectAsState()
    val offline = vm.day.collectAsState().value.offline
    Row(
        Modifier.fillMaxWidth().clickable { vm.openAction(a.id) }.heightIn(min = 52.dp)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The completion circle: one tap completes, with Undo in the snackbar.
        Box(
            Modifier.size(48.dp).clickable(enabled = !offline && a.id !in busy) { vm.complete(a) },
            contentAlignment = Alignment.Center,
        ) {
            if (a.id in busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Box(Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(a.displayText, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            val sub = listOfNotNull(
                lateLabel.takeIf { it.isNotEmpty() },
                DayLogic.minutes(a.timeEstimateMins),
                if (a.isRoutine) "routine" else null,
            )
            if (sub.isNotEmpty()) {
                Text(
                    modifier = Modifier.padding(bottom = 6.dp),
                    text = 
                    sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = if (lateLabel.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                )
            }
        }
        ProjectTag(a)
    }
}

@Composable
private fun ProjectTag(a: Action) {
    val icon = a.projectIcon
    val name = a.projectName
    when {
        icon != null -> Text(icon, modifier = Modifier.padding(start = 8.dp))
        name != null -> Text(
            name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, modifier = Modifier.padding(start = 8.dp).width(84.dp),
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
