package com.bedrockmodstudio.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.storage.ProjectSummary
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.ActionTile
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.formatDate
import com.bedrockmodstudio.ui.importing.ImportState
import com.bedrockmodstudio.ui.importing.rememberAddonImporter
import com.bedrockmodstudio.ui.navigateTopLevel
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Draft(val projectName: String, val projectId: String, val path: String)

private data class HomeData(val recent: List<ProjectSummary>, val broken: Int, val drafts: List<Draft>)

@Composable
fun HomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var welcome by remember { mutableStateOf(!app.welcomeSeen) }
    val importState = remember { ImportState() }
    val import = rememberAddonImporter(nav, importState)
    var busy by remember { mutableStateOf(false) }

    val data by produceState<HomeData?>(null) {
        value = withContext(Dispatchers.IO) {
            val list = app.store.list()
            // Projets dont un brouillon non enregistré a été retrouvé (fermeture inattendue).
            val drafts = list.flatMap { p -> app.store.files(p.meta.id).allDrafts().map { Draft(p.meta.name, p.meta.id, it) } }
            HomeData(list.take(5), app.store.broken().size, drafts)
        }
    }

    if (importState.running || busy) { LoadingBox(stringResource(R.string.importing)); return }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (welcome) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { app.welcomeSeen = true; welcome = false }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.welcome_dismiss))
                    }
                }
            }
        }

        Button(
            onClick = { nav.navigate(Routes.NEW_PROJECT) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BmsColors.Primary),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.home_create_mod), style = MaterialTheme.typography.titleMedium)
        }
        ActionTile(Icons.Filled.FileOpen, stringResource(R.string.home_import), stringResource(R.string.home_import_desc), accent = BmsColors.Info) { import() }
        ActionTile(Icons.Filled.AutoAwesome, stringResource(R.string.home_example), stringResource(R.string.home_example_desc), accent = BmsColors.Secondary) {
            busy = true
            scope.launch(Dispatchers.Main.immediate) {
                val result = withContext(Dispatchers.IO) { runCatching { app.store.createExample() } }
                busy = false
                result.onSuccess { nav.navigate(Routes.project(it.id)) }
                    .onFailure { app.errorLog.log("Projet d'exemple", it); snackbar.showSnackbar(it.message ?: "Erreur") }
            }
        }

        val d = data
        if (d != null) {
            if (d.broken > 0) InfoCard(stringResource(R.string.broken_projects, d.broken), color = BmsColors.Warning)
            d.drafts.forEach { dr ->
                InfoCard(stringResource(R.string.drafts_found, "${dr.projectName} › ${dr.path}"), color = BmsColors.Warning,
                    modifier = Modifier.clickable { nav.navigate(Routes.edit(dr.projectId, dr.path)) })
            }
            SectionTitle(stringResource(R.string.home_recent))
            if (d.recent.isEmpty()) {
                Text(stringResource(R.string.home_no_projects), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                d.recent.forEach { p -> ProjectCard(p) { nav.navigate(Routes.project(p.meta.id)) } }
                TextButton(onClick = { nav.navigateTopLevel(Routes.PROJECTS) }) { Text(stringResource(R.string.home_see_all)) }
            }
        }
    }

    importState.rejected?.let { msg ->
        AlertDialog(
            onDismissRequest = { importState.rejected = null },
            title = { Text(stringResource(R.string.import_rejected_title)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importState.rejected = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
fun ProjectCard(p: ProjectSummary, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Inventory2, contentDescription = null, tint = BmsColors.Primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.meta.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.project_namespace_version, p.meta.namespace, p.meta.targetVersion, p.meta.packVersion.toString()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.project_updated, formatDate(p.meta.updatedAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}
