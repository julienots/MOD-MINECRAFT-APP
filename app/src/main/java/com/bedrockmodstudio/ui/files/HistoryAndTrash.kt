package com.bedrockmodstudio.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.bedrockmodstudio.core.storage.HistoryEntry
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.storage.SearchHit
import com.bedrockmodstudio.core.storage.TrashEntry
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.formatDate
import com.bedrockmodstudio.ui.common.formatSize
import com.bedrockmodstudio.ui.theme.BmsColors
import com.bedrockmodstudio.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(nav: NavHostController, id: String, path: String) {
    val app = LocalContext.current.container
    val files = remember(id) { app.store.files(id) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var refresh by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Pair<HistoryEntry, String>?>(null) }
    var toRestore by remember { mutableStateOf<HistoryEntry?>(null) }
    val restoredMsg = stringResource(R.string.history_restored)
    val entries by produceState<List<HistoryEntry>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { files.history(path) }.getOrDefault(emptyList()) }
    }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.history_title, path.substringAfterLast('/')), subtitle = path, onBack = { nav.popBackStack() }) }) { padding ->
        val list = entries
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> Column(Modifier.padding(padding)) { EmptyState(stringResource(R.string.history_empty)) }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(list, key = { it.id }) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch(Dispatchers.Main.immediate) {
                                val text = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val b = files.readHistory(path, h.id)
                                        if (ProjectFiles.isTextFile(path)) b.toString(Charsets.UTF_8).take(20_000) else formatSize(b.size.toLong())
                                    }.getOrElse { it.message ?: "" }
                                }
                                preview = h to text
                            }
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDate(h.timestamp))
                            Text("${h.reason} · ${formatSize(h.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { toRestore = h }) { Text(stringResource(R.string.restore)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    preview?.let { (h, text) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.history_preview) + " — " + formatDate(h.timestamp)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(text, style = MonoStyle)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null; toRestore = h }) { Text(stringResource(R.string.restore)) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    toRestore?.let { h ->
        ConfirmDialog(
            stringResource(R.string.history_restore_title), stringResource(R.string.history_restore_body),
            confirmLabel = stringResource(R.string.restore),
            onConfirm = {
                toRestore = null
                scope.launch(Dispatchers.Main.immediate) {
                    val r = withContext(Dispatchers.IO) { runCatching { files.restoreHistory(path, h.id) } }
                    snackbar.showSnackbar(r.exceptionOrNull()?.message ?: restoredMsg)
                    refresh++
                }
            },
            onDismiss = { toRestore = null },
        )
    }
}

@Composable
fun ProjectTrashScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val files = remember(id) { app.store.files(id) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var refresh by remember { mutableIntStateOf(0) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val restoredMsg = stringResource(R.string.trash_restored, "%s")
    val entries by produceState<List<TrashEntry>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { files.listTrash() }
    }
    Scaffold(topBar = {
        BmsTopBar(stringResource(R.string.trash_title), onBack = { nav.popBackStack() }, actions = {
            if (!entries.isNullOrEmpty()) IconButton(onClick = { confirmEmpty = true }) {
                Icon(Icons.Filled.DeleteForever, stringResource(R.string.trash_empty_action), tint = BmsColors.Error)
            }
        })
    }) { padding ->
        val list = entries
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> Column(Modifier.padding(padding)) { EmptyState(stringResource(R.string.trash_empty)) }
            else -> LazyColumn(Modifier.padding(padding)) {
                items(list, key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.originalPath, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.deleted_on, formatDate(t.deletedAt)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            scope.launch(Dispatchers.Main.immediate) {
                                val r = withContext(Dispatchers.IO) { runCatching { files.restoreFromTrash(t.id) } }
                                snackbar.showSnackbar(r.map { restoredMsg.format(it) }.getOrElse { it.message ?: "Erreur" })
                                refresh++
                            }
                        }) { Text(stringResource(R.string.restore)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    if (confirmEmpty) ConfirmDialog(
        stringResource(R.string.trash_empty_title), stringResource(R.string.trash_empty_body),
        confirmLabel = stringResource(R.string.trash_empty_action), destructive = true,
        onConfirm = {
            confirmEmpty = false
            scope.launch(Dispatchers.Main.immediate) { withContext(Dispatchers.IO) { files.emptyTrash() }; refresh++ }
        },
        onDismiss = { confirmEmpty = false },
    )
}

@Composable
fun SearchScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val files = remember(id) { app.store.files(id) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    LaunchedEffect(query) {
        if (query.length < 2) { hits = null; return@LaunchedEffect }
        delay(300)
        hits = withContext(Dispatchers.IO) { runCatching { files.search(query) }.getOrDefault(emptyList()) }
    }
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.search_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
            )
            val list = hits
            if (list != null && list.isEmpty()) EmptyState(stringResource(R.string.search_no_result))
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(list ?: emptyList()) { h ->
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.edit(id, h.path, h.line)) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(h.path + if (h.line > 0) " · " + stringResource(R.string.search_line, h.line) else "",
                            style = MaterialTheme.typography.bodySmall, color = BmsColors.Info)
                        if (h.line > 0) Text(h.snippet, style = MonoStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
