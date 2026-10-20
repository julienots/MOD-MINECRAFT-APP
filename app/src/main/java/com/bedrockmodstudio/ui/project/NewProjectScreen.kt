package com.bedrockmodstudio.ui.project

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersionProfile
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.HelpText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NewProjectScreen(nav: NavHostController) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf(app.defaultAuthor) }
    var namespace by remember { mutableStateOf("") }
    var namespaceEdited by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(BedrockVersions.byId(app.defaultVersion) ?: BedrockVersions.DEFAULT) }
    var creating by remember { mutableStateOf(false) }

    val effectiveNs = if (namespaceEdited) namespace else Identifiers.toNamespace(name)
    val nsError = if (Identifiers.isValidNamespace(effectiveNs)) null
    else "Minuscules, chiffres et _ uniquement ; « minecraft » est réservé."

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.new_project_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_project_name)) }, singleLine = true)
            HelpText(stringResource(R.string.field_project_name_help))
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_description)) })
            HelpText(stringResource(R.string.field_description_help))
            OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_author)) }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = effectiveNs,
                onValueChange = { namespace = it.lowercase(); namespaceEdited = true },
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_namespace)) }, singleLine = true,
                isError = nsError != null && name.isNotBlank(),
                supportingText = { if (nsError != null && name.isNotBlank()) Text(nsError) },
            )
            HelpText(stringResource(R.string.field_namespace_help))
            VersionPicker(version) { version = it }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    creating = true
                    scope.launch(Dispatchers.Main.immediate) {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { app.store.create(name, description, author, effectiveNs, version) }
                        }
                        creating = false
                        r.onSuccess {
                            if (author.isNotBlank()) app.defaultAuthor = author
                            nav.navigate(Routes.project(it.id)) { popUpTo(Routes.NEW_PROJECT) { inclusive = true } }
                        }.onFailure {
                            app.errorLog.log("Création de projet", it)
                            snackbar.showSnackbar(it.message ?: "Erreur")
                        }
                    }
                },
                enabled = !creating && name.isNotBlank() && nsError == null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.create)) }
        }
    }
}

@Composable
fun VersionPicker(selected: BedrockVersionProfile, onSelect: (BedrockVersionProfile) -> Unit) {
    Text(stringResource(R.string.field_target_version), style = MaterialTheme.typography.titleSmall)
    HelpText(stringResource(R.string.field_target_version_help))
    BedrockVersions.ALL.forEach { p ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = p == selected, onClick = { onSelect(p) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = p == selected, onClick = { onSelect(p) })
            Column {
                Text(p.label)
                Text("API de scripts ${p.scriptServerVersion}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
