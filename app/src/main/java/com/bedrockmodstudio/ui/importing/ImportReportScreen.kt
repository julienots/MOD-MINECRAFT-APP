package com.bedrockmodstudio.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.theme.BmsColors

@Composable
fun ImportReportScreen(nav: NavHostController) {
    val app = LocalContext.current.container
    val report = app.lastImportReport
    val projectId = app.lastImportProjectId
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.import_report_title), subtitle = report?.sourceName, onBack = { nav.popBackStack() }) }) { padding ->
        if (report == null || projectId == null) { Column(Modifier.padding(padding)) { EmptyState("—") }; return@Scaffold }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val ok = report.validationErrors == 0
            InfoCard(stringResource(R.string.import_validation, report.validationErrors, report.validationWarnings),
                color = if (ok) BmsColors.Primary else BmsColors.Warning)
            Button(
                onClick = { nav.navigate(Routes.project(projectId)) { popUpTo(Routes.IMPORT_REPORT) { inclusive = true } } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(52.dp),
            ) { Text(stringResource(R.string.import_open_project)) }

            if (report.packs.isNotEmpty()) {
                SectionTitle(stringResource(R.string.import_packs))
                report.packs.forEach { p ->
                    Text("• ${p.name} (${p.kind}) v${p.version ?: "?"}", style = MaterialTheme.typography.bodyMedium)
                    Text("   → ${p.usedAs}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (report.recognized.isNotEmpty()) {
                SectionTitle(stringResource(R.string.import_recognized))
                report.recognized.forEach { (k, v) -> Text("• $k : $v") }
                Text(stringResource(R.string.import_editable, report.editableFiles, report.binaryFiles),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ListSection(stringResource(R.string.import_invalid_json), report.invalidJson, BmsColors.Error)
            ListSection(stringResource(R.string.import_unknown), report.unknownFiles)
            ListSection(stringResource(R.string.import_dependencies), report.dependencies)
            ListSection(stringResource(R.string.import_skipped), report.skippedEntries, BmsColors.Warning)
            ListSection(stringResource(R.string.import_notes), report.notes, BmsColors.Info)
        }
    }
}

@Composable
private fun ListSection(title: String, items: List<String>, color: androidx.compose.ui.graphics.Color? = null) {
    if (items.isEmpty()) return
    SectionTitle(title)
    items.take(100).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = color ?: MaterialTheme.colorScheme.onSurface) }
    if (items.size > 100) Text("… et ${items.size - 100} autre(s)", style = MaterialTheme.typography.bodySmall)
}
