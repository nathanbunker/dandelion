package org.dandeliondaily.sundial.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.dandeliondaily.sundial.AppViewModel
import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.Part
import org.dandeliondaily.sundial.day.CaptureWhen
import org.dandeliondaily.sundial.day.DayLogic
import org.dandeliondaily.sundial.day.MoveTarget
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The verbs for Complete & Next, as on /m/action. */
private val VERBS = listOf("I will", "I might", "I am waiting", "I will meet", "I would like to", "I have committed")

/** Action types for Edit details: (server id, label). */
private val TYPES = listOf(
    "WILL" to "Will", "WILL_CONTACT" to "Will contact", "WILL_MEET" to "Will meet", "REVIEW" to "Will review",
    "DOCUMENT" to "Will document", "WILL_FOLLOW_UP" to "Follow up", "MIGHT" to "Might",
    "WOULD_LIKE_TO" to "Would like to", "COMMITTED_TO" to "Committed to", "GOAL" to "Goal", "WAITING" to "Waiting",
)

/**
 * Done & next (P-16): pick a verb, finish the sentence, optionally a When chip. Saving completes this
 * action and creates the next one in the same project; Undo reverses both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoneAndNext(a: Action, serverToday: LocalDate, enabled: Boolean, vm: AppViewModel, onCancel: () -> Unit) {
    var verb by remember { mutableStateOf("I will") }
    var rest by remember { mutableStateOf("") }
    var whenChoice by remember { mutableStateOf<MoveTarget?>(null) }
    val options = remember { CaptureWhen.options(vm.sundialNow(), serverToday) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Done, and what's next?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in VERBS) FilterChip(selected = v == verb, onClick = { verb = v }, label = { Text(v) })
        }
        OutlinedTextField(
            value = rest, onValueChange = { rest = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("call him back thursday") }, enabled = enabled,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (o in options) FilterChip(selected = whenChoice == o, onClick = { whenChoice = if (whenChoice == o) null else o }, label = { Text(o.label) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = enabled && rest.isNotBlank(),
                onClick = { vm.completeNext(a, "$verb ${rest.trim()}", whenChoice?.date, whenChoice?.part) },
            ) { Text("✔ Done & save next") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
}

/** Block on a new action (P-15): what has to happen first. It's added for today; this one waits for it. */
@Composable
fun BlockOnNew(a: Action, enabled: Boolean, vm: AppViewModel, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What has to happen first?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(
            "It's added for today. This action leaves the list and comes back when that one is done.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = enabled && text.isNotBlank(), onClick = { vm.block(a, text) }) { Text("⛓ Block on it") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
}

/** Edit details (P-14, P-20): description, type, date and part, link, project. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditDetails(a: Action, serverToday: LocalDate, vm: AppViewModel, onClose: () -> Unit) {
    val projects by vm.projects.collectAsState()
    var description by remember { mutableStateOf(a.description.replace(Regex("<[^>]*>"), "")) }
    var type by remember { mutableStateOf(a.actionType ?: "WILL") }
    var date by remember { mutableStateOf(a.date?.let { DayLogic.parse(it) }) }
    var part by remember { mutableStateOf(a.part) }
    var link by remember { mutableStateOf(a.linkUrl ?: "") }
    var projectId by remember { mutableStateOf(a.projectId) }
    var typeMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row {
                    TextButton(onClick = onClose) { Text("‹ Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = !saving && description.isNotBlank(), onClick = {
                        val fields = JSONObject()
                        val cleanOld = a.description.replace(Regex("<[^>]*>"), "")
                        if (description.trim() != cleanOld.trim()) fields.put("description", description.trim())
                        if (type != a.actionType) fields.put("actionType", type)
                        val oldDate = a.date?.let { DayLogic.parse(it) }
                        if (date != oldDate) {
                            if (date == null) fields.put("clearDate", true) else fields.put("date", DayLogic.key(date!!))
                        }
                        if (part != a.part) fields.put("part", part.id)
                        if (link.trim() != (a.linkUrl ?: "")) fields.put("linkUrl", link.trim())
                        if (projectId != a.projectId && projectId != null) fields.put("projectId", projectId)
                        if (fields.length() == 0) {
                            onClose()
                            return@Button
                        }
                        saving = true
                        vm.edit(a.id, fields) { failure ->
                            saving = false
                            if (failure == null) onClose() else error = failure
                        }
                    }) { Text(if (saving) "Saving…" else "Save") }
                }
                Text("Edit details", style = MaterialTheme.typography.titleLarge)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                OutlinedTextField(
                    value = description, onValueChange = { description = it }, label = { Text("What") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Label("Type")
                Box {
                    OutlinedButton(onClick = { typeMenu = true }) { Text(TYPES.firstOrNull { it.first == type }?.second ?: type) }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        for ((id, label) in TYPES) DropdownMenuItem(text = { Text(label) }, onClick = { type = id; typeMenu = false })
                    }
                }

                Label("When")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = true }) {
                        Text(date?.let { DayLogic.title(it, vm.sundialNow().date) + " · " + DayLogic.fullDate(it) } ?: "No date")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in Part.entries) FilterChip(selected = p == part, onClick = { part = p }, label = { Text(p.label) })
                }

                Label("Project")
                Box {
                    val current = projects.firstOrNull { it.id == projectId }
                    OutlinedButton(onClick = { projectMenu = true }) {
                        Text(current?.label ?: listOfNotNull(a.projectIcon, a.projectName).joinToString(" ").ifBlank { "Project" })
                    }
                    DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                        for (p in projects) DropdownMenuItem(text = { Text(p.label) }, onClick = { projectId = p.id; projectMenu = false })
                    }
                }

                OutlinedTextField(
                    value = link, onValueChange = { link = it }, label = { Text("Link") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                HorizontalDivider()
                Text(
                    "Notes are added from the action sheet. Assigning to someone else isn't on the phone yet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    if (picking) {
        val startMillis = serverToday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val initial = date?.takeIf { !it.isBefore(serverToday) } ?: serverToday
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= startMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state, showModeToggle = false, title = null, headline = null)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
}
