package com.bedrockmodstudio.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.library.ComponentLibrary
import com.bedrockmodstudio.core.library.ComponentTarget
import com.bedrockmodstudio.core.library.LibraryComponent
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ComponentLibraryScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<ComponentTarget?>(null) }
    var open by remember { mutableStateOf<LibraryComponent?>(null) }
    var askReplace by remember { mutableStateOf<Pair<LibraryComponent, String>?>(null) }
    val index by produceState(emptyList<ProjectIndex.Entry>()) {
        value = withContext(Dispatchers.IO) { runCatching { ProjectIndex.scan(app.store.files(id)) }.getOrDefault(emptyList()) }
    }

    fun insert(c: LibraryComponent, path: String, replace: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val files = app.store.files(id)
                    when (val res = ComponentLibrary.insert(files.readText(path), c, replace)) {
                        is ComponentLibrary.InsertResult.Ok -> { files.writeText(path, res.text, "Bibliothèque : ${c.name}"); app.store.touch(id); res }
                        is ComponentLibrary.InsertResult.Problem -> res
                    }
                }
            }
            r.onSuccess { res ->
                when (res) {
                    is ComponentLibrary.InsertResult.Ok -> snackbar.showSnackbar(context.getString(R.string.library_added, c.labelFr, path.substringAfterLast('/')))
                    is ComponentLibrary.InsertResult.Problem ->
                        if (res.messageFr == "EXISTS") askReplace = c to path else snackbar.showSnackbar(res.messageFr)
                }
            }.onFailure { app.errorLog.log("Bibliothèque", it); snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.library_title), onBack = { nav.popBackStack() }) }) { padding ->
        val list = remember(query, target) { ComponentLibrary.search(query, target) }
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoCard(stringResource(R.string.library_intro))
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("library_search"), label = { Text(stringResource(R.string.library_search)) }, singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = target == null, onClick = { target = null }, label = { Text(stringResource(R.string.library_all)) })
                        ComponentTarget.entries.forEach { t -> FilterChip(selected = target == t, onClick = { target = t }, label = { Text(t.labelFr) }) }
                    }
                }
            }
            items(list, key = { it.target.name + it.name }) { c ->
                Card(onClick = { open = if (open == c) null else c }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.labelFr, style = MaterialTheme.typography.titleMedium)
                        Text("${c.target.labelFr} • ${c.category} • ${c.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.descriptionFr)
                        if (open == c) {
                            Text("\"${c.name}\": " + JsonWriter.write(c.value), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            val targets = index.filter { it.kind.name == c.target.name }
                            if (targets.isEmpty()) HelpText(stringResource(R.string.library_no_target))
                            else {
                                Text(stringResource(R.string.library_add_to), style = MaterialTheme.typography.labelLarge)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    targets.forEach { t -> AssistChip(onClick = { insert(c, t.path, replace = false) }, label = { Text(t.id) }) }
                                }
                            }
                            OutlinedButton(onClick = { open = null }) { Text(stringResource(R.string.library_close)) }
                        }
                    }
                }
            }
            item { HelpText(stringResource(R.string.library_footer)) }
        }
    }
    askReplace?.let { (c, path) ->
        ConfirmDialog(
            stringResource(R.string.library_replace_title),
            stringResource(R.string.library_replace_body, c.name, path.substringAfterLast('/')),
            onConfirm = { askReplace = null; insert(c, path, replace = true) },
            onDismiss = { askReplace = null },
        )
    }
}
