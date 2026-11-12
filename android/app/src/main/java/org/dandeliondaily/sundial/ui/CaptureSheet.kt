package org.dandeliondaily.sundial.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.dandeliondaily.sundial.AppViewModel
import org.dandeliondaily.sundial.api.Project
import org.dandeliondaily.sundial.day.CaptureWhen
import org.dandeliondaily.sundial.day.MoveTarget
import java.time.LocalDate

/** How many recently used projects get a chip; the rest are under "More…". */
private const val PROJECT_CHIPS = 4

/**
 * Quick capture (P-17 to P-19): one sentence, a When chip (optional; the sentence can say "tuesday"),
 * and a project chip, or a "project: " prefix in the sentence. Saving closes the sheet; the snackbar
 * offers Edit (P-20) and the action shows under "Just added".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureSheet(serverToday: LocalDate, vm: AppViewModel, onClose: () -> Unit) {
    val projects by vm.projects.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf<Int?>(null) }
    var whenChoice by remember { mutableStateOf<MoveTarget?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val options = remember { CaptureWhen.options(vm.sundialNow(), serverToday) }
    // A "name: …" prefix names the project itself (the server checks it is a personal project).
    val hasPrefix = Regex("^[^:]{1,40}:\\s*\\S").containsMatchIn(text.trim())
    val canSave = text.isNotBlank() && (projectId != null || hasPrefix) && !saving

    LaunchedEffect(Unit) {
        vm.loadProjects()
        runCatching { focus.requestFocus() }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("What and when?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text, onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("call the clerk about dinner tuesday") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                enabled = !saving,
            )

            Text("When", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in options) {
                    FilterChip(
                        selected = whenChoice == o,
                        onClick = { whenChoice = if (whenChoice == o) null else o },
                        label = { Text(o.label) },
                    )
                }
            }
            if (whenChoice == null) {
                Text(
                    "Or say it: \"… tuesday\", \"… tomorrow\". Otherwise today.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }

            Text("Project", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            val chips = projects.take(PROJECT_CHIPS).toMutableList()
            val chosen = projects.firstOrNull { it.id == projectId }
            if (chosen != null && chosen !in chips) chips += chosen
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in chips) ProjectChip(p, p.id == projectId) { projectId = if (projectId == p.id) null else p.id }
                if (projects.size > PROJECT_CHIPS) {
                    Box {
                        FilterChip(selected = false, onClick = { more = true }, label = { Text("More…") })
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            for (p in projects) {
                                DropdownMenuItem(text = { Text(p.label) }, onClick = { projectId = p.id; more = false })
                            }
                        }
                    }
                }
            }
            if (projects.isEmpty()) {
                Text("Loading projects…", style = MaterialTheme.typography.bodySmall)
            }
            if (hasPrefix && projectId == null) {
                Text(
                    "Using the project named before the colon.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                Spacer(Modifier.weight(1f))
                Button(enabled = canSave, onClick = {
                    saving = true
                    vm.capture(text, projectId, whenChoice?.date, whenChoice?.part) { failure ->
                        saving = false
                        if (failure == null) onClose() else error = failure
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            }
        }
    }
}

@Composable
private fun ProjectChip(p: Project, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(p.label, maxLines = 1) })
}
