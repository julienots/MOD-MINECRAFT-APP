package com.bedrockmodstudio.ui.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.core.storage.ProjectSettings
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProjectSettingsScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(BedrockVersions.DEFAULT) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val m = withContext(Dispatchers.IO) { runCatching { app.store.load(id) }.getOrNull() }
        if (m != null) {
            meta = m; name = m.name; description = m.description; author = m.author
            version = BedrockVersions.byId(m.targetVersion) ?: BedrockVersions.DEFAULT
        }
    }
    val m = meta
    val savedMsg = stringResource(R.string.project_settings_saved)
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.project_settings_title), onBack = { nav.popBackStack() }) }) { padding ->
        if (m == null) { LoadingBox(); return@Scaffold }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_project_name)) }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_description)) })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_author)) }, singleLine = true)
            Text(stringResource(R.string.project_namespace_fixed, m.namespace), Modifier.padding(vertical = 12.dp))
            VersionPicker(version) { version = it }
            Text(stringResource(R.string.project_version_feature_note), style = MaterialTheme.typography.bodySmall)
            version.features.forEach { Text("• ${it.labelFr}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !saving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    saving = true
                    scope.launch(Dispatchers.Main.immediate) {
                        val r = withContext(Dispatchers.IO) { runCatching { ProjectSettings.update(app.store, id, name, description, author, version) } }
                        saving = false
                        r.onSuccess {
                            meta = it.meta
                            val extra = if (it.skipped.isNotEmpty()) "\n" + it.skipped.joinToString("\n") else ""
                            snackbar.showSnackbar(savedMsg + extra)
                        }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                    }
                },
            ) { Text(stringResource(R.string.save)) }

            SectionTitle(stringResource(R.string.project_uuids))
            HelpText(stringResource(R.string.project_uuids_help))
            SelectionContainer {
                Text(
                    "Behavior pack : ${m.behaviorPack.headerUuid}\n  module : ${m.behaviorPack.dataModuleUuid}\n" +
                        "Resource pack : ${m.resourcePack.headerUuid}\n  module : ${m.resourcePack.dataModuleUuid}",
                    style = MonoStyle,
                )
            }
        }
    }
}
