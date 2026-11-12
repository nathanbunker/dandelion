package org.dandeliondaily.sundial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.dandeliondaily.sundial.AppViewModel
import org.dandeliondaily.sundial.DetailState
import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.day.DayLogic

/** Read-only action detail (step 2). The action sheet with moves and ✔ replaces it in step 3. */
@Composable
fun ActionDetailScreen(d: DetailState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = { vm.closeAction() }) { Text("‹ Back") }
        }
        if (d.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()
        d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        d.action?.let { Detail(it) }
    }
}

@Composable
private fun Detail(a: Action) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SelectionContainer {
            Text(a.displayText, style = MaterialTheme.typography.titleLarge)
        }
        val project = listOfNotNull(a.projectIcon, a.projectName).joinToString(" ")
        if (project.isNotBlank()) Field("Project", project)
        val date = a.date?.let { DayLogic.fullDate(DayLogic.parse(it)) }
        Field("When", listOfNotNull(date, a.part.label).joinToString(" · "))
        a.deadline?.let { Field("Deadline", DayLogic.fullDate(DayLogic.parse(it))) }
        DayLogic.minutes(a.timeEstimateMins)?.let { Field("Estimate", it) }
        a.actionType?.let { Field("Type", it) }
        if (a.isRoutine) {
            Field(
                "Routine",
                when {
                    a.expires -> "From a Plan Ahead template. If it isn't done today it expires; tomorrow has its own."
                    a.missedActionBehavior == "CARRY_FORWARD" -> "From a Plan Ahead template. Carries forward if missed."
                    else -> "From a Plan Ahead template."
                },
            )
        }
        a.linkUrl?.let { link ->
            Column {
                Label("Link")
                Text(
                    link, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { runCatching { uri.openUri(link) } },
                )
            }
        }
        a.notes?.let { notes ->
            Column {
                Label("Notes")
                SelectionContainer { Text(notes) }
            }
        }
        Text(
            "Checking off and moving come in the next version.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(84.dp))
        Text(value)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
}
