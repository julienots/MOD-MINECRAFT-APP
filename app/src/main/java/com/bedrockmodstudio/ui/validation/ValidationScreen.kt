package com.bedrockmodstudio.ui.validation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.validation.Issue
import com.bedrockmodstudio.core.validation.Severity
import com.bedrockmodstudio.core.validation.ValidationReport
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.severityColor
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ValidationScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    var run by remember { mutableIntStateOf(0) }
    val report by produceState<Result<ValidationReport>?>(null, run) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { app.exporter.validate(id) } }
    }
    Scaffold(topBar = {
        BmsTopBar(stringResource(R.string.validation_title), onBack = { nav.popBackStack() }, actions = {
            IconButton(onClick = { run++ }) { Icon(Icons.Filled.Refresh, stringResource(R.string.validation_rerun)) }
        })
    }) { padding ->
        val r = report
        if (r == null) { LoadingBox(stringResource(R.string.validation_running)); return@Scaffold }
        val rep = r.getOrElse {
            Column(Modifier.padding(padding).padding(16.dp)) { InfoCard(it.message ?: "Erreur", color = BmsColors.Error) }
            return@Scaffold
        }
        IssueList(rep.issues, Modifier.padding(padding), header = {
            Text(stringResource(R.string.validation_summary, rep.checkedFiles, rep.errors.size, rep.warnings.size),
                style = MaterialTheme.typography.titleSmall)
            if (rep.hasErrors) InfoCard(stringResource(R.string.validation_has_errors), color = BmsColors.Error)
            else InfoCard(stringResource(R.string.validation_all_good), color = BmsColors.Primary)
            InfoCard(stringResource(R.string.validation_disclaimer))
        }) { issue -> issue.file?.let { f -> if ('.' in f.substringAfterLast('/')) nav.navigate(Routes.edit(id, f, issue.line ?: 0)) } }
    }
}

@Composable
fun IssueList(issues: List<Issue>, modifier: Modifier = Modifier, header: @Composable () -> Unit = {}, onOpen: (Issue) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { header() } }
        items(issues) { issue -> IssueCard(issue) { onOpen(issue) } }
    }
}

@Composable
fun IssueCard(issue: Issue, onClick: () -> Unit) {
    val color = severityColor(issue.severity)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = issue.file != null, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when (issue.severity) { Severity.ERROR -> Icons.Filled.Error; Severity.WARNING -> Icons.Filled.Warning; Severity.INFO -> Icons.Filled.Info },
                contentDescription = issue.severity.labelFr, tint = color, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(issue.message, style = MaterialTheme.typography.bodyMedium)
                if (issue.file != null) Text(
                    issue.file + (issue.line?.let { " · ligne $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = BmsColors.Info,
                )
            }
        }
    }
}
