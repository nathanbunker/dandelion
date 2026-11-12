package org.dandeliondaily.sundial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.dandeliondaily.sundial.AppViewModel
import org.dandeliondaily.sundial.DetailState
import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Part
import org.dandeliondaily.sundial.day.DayLogic
import org.dandeliondaily.sundial.day.MoveTargets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The action sheet (Seedclock §2.3, Sundial Plan change 4): ✔ Done, ✖ Cancel, move chips, Pick a
 * date…, notes with Add, and the link. One tap does it and closes the sheet; the snackbar offers Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(d: DetailState, serverToday: LocalDate, offline: Boolean, vm: AppViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { vm.closeAction() }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
            if (d.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            d.action?.let { Sheet(it, serverToday, offline, vm) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sheet(a: Action, serverToday: LocalDate, offline: Boolean, vm: AppViewModel) {
    val uri = LocalUriHandler.current
    val busy by vm.busy.collectAsState()
    val enabled = !offline && a.id !in busy && (a.status == null || a.status == "R")
    var picking by remember { mutableStateOf(false) }
    var tool by remember(a.id) { mutableStateOf(Tool.NONE) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 32.dp)) {
        SelectionContainer { Text(a.displayText, style = MaterialTheme.typography.titleLarge) }
        val where = listOfNotNull(
            listOfNotNull(a.projectIcon, a.projectName).joinToString(" ").ifBlank { null },
            a.actionType,
            a.date?.let { DayLogic.title(DayLogic.parse(it), vm.sundialNow().date) + ", " + a.part.label },
        )
        Text(where.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.complete(a) }, enabled = enabled) { Text("✔ Done") }
            if (vm.canCapture()) OutlinedButton(onClick = { tool = Tool.NEXT }, enabled = enabled) { Text("Done & next…") }
            OutlinedButton(onClick = { vm.cancel(a) }, enabled = enabled) { Text("✖") }
        }
        if (offline) Text("Can't reach Dandelion. Changes wait until it's back.", color = MaterialTheme.colorScheme.error)
        when (tool) {
            Tool.NEXT -> DoneAndNext(a, serverToday, enabled, vm) { tool = Tool.NONE }
            Tool.BLOCK -> BlockOnNew(a, enabled, vm) { tool = Tool.NONE }
            else -> {}
        }

        val targets = MoveTargets.forAction(a, vm.sundialNow(), serverToday)
        HorizontalDivider()
        Text("Move", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        if (a.expires) {
            Text(
                "A routine: it expires if it isn't done today, and tomorrow has its own.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in targets) {
                AssistChip(onClick = { vm.move(a, t.date, t.part) }, label = { Text(t.label) }, enabled = enabled)
            }
            if (!a.expires) {
                AssistChip(onClick = { picking = true }, label = { Text("Pick a date…") }, enabled = enabled)
            }
        }

        DayLogic.minutes(a.timeEstimateMins)?.let { Field("Estimate", it) }
        a.deadline?.let { Field("Deadline", DayLogic.fullDate(DayLogic.parse(it))) }
        if (vm.canCapture()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { tool = Tool.EDIT }, label = { Text("✎ Edit details") }, enabled = enabled)
            AssistChip(onClick = { tool = Tool.BLOCK }, label = { Text("⛓ Block on new action") }, enabled = enabled)
        }
        a.linkUrl?.let { link ->
            Text(
                "🔗 Open link", color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { runCatching { uri.openUri(link) } }.padding(vertical = 4.dp),
            )
        }

        HorizontalDivider()
        Notes(a, offline, vm)
    }

    if (tool == Tool.EDIT) {
        EditDetails(a, serverToday, vm) { tool = Tool.NONE }
    }

    if (picking) {
        PickDate(a, serverToday, onDismiss = { picking = false }) { date, part ->
            picking = false
            vm.move(a, date, part)
        }
    }
}

private enum class Tool { NONE, NEXT, BLOCK, EDIT }

@Composable
private fun Notes(a: Action, offline: Boolean, vm: AppViewModel) {
    var text by remember(a.id) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Text("Notes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
    a.notes?.let { SelectionContainer { Text(it) } }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Add to notes…") }, enabled = !saving && !offline,
    )
    if (text.isNotBlank()) {
        TextButton(onClick = {
            saving = true
            vm.addNote(a.id, text) { ok ->
                saving = false
                if (ok) text = ""
            }
        }, enabled = !saving && !offline) { Text(if (saving) "Saving…" else "Add note") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PickDate(a: Action, serverToday: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate, Part) -> Unit) {
    val startMillis = serverToday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val initial = a.date?.let { DayLogic.parse(it) }?.takeIf { !it.isBefore(serverToday) } ?: serverToday
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= startMillis
        },
    )
    var part by remember { mutableStateOf(a.part) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate(), part)
                }
            }, enabled = state.selectedDateMillis != null) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, showModeToggle = false, title = null, headline = null)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Part.entries) {
                FilterChip(selected = p == part, onClick = { part = p }, label = { Text(p.label) })
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(84.dp))
        Text(value)
    }
}
