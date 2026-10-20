package com.bedrockmodstudio.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.storage.FileEntry
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.TextInputDialog
import com.bedrockmodstudio.ui.common.formatSize
import com.bedrockmodstudio.ui.importing.displayName
import com.bedrockmodstudio.ui.importing.readLimited
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class FileDialog {
    data object NewFile : FileDialog()
    data object NewFolder : FileDialog()
    data class Rename(val entry: FileEntry) : FileDialog()
    data class Delete(val entry: FileEntry) : FileDialog()
}

/** Contenu de départ d'un nouveau fichier selon son extension. */
private fun starterContent(name: String): ByteArray = when (name.substringAfterLast('.', "").lowercase()) {
    "json" -> "{\n  \n}\n"
    "mcfunction" -> "# Une commande par ligne, sans le « / ».\n"
    "lang" -> "## Traductions : clé=texte\n"
    else -> ""
}.toByteArray()

fun iconFor(entry: FileEntry): ImageVector = when {
    entry.isDirectory -> Icons.Filled.Folder
    else -> when (entry.name.substringAfterLast('.', "").lowercase()) {
        "json" -> Icons.Filled.DataObject
        "png", "tga", "jpg", "jpeg" -> Icons.Filled.Image
        "ogg", "wav", "fsb" -> Icons.Filled.MusicNote
        "js", "ts", "mcfunction" -> Icons.Filled.Code
        "lang" -> Icons.Filled.Translate
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

@Composable
fun FileExplorerScreen(nav: NavHostController, id: String, dir: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val files = remember(id) { app.store.files(id) }
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var fabMenu by remember { mutableStateOf(false) }

    val entries by produceState<Result<List<FileEntry>>?>(null, dir, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { files.list(dir) } }
    }

    fun op(block: suspend () -> String?) {
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            r.onSuccess { msg -> msg?.let { snackbar.showSnackbar(it) } }
                .onFailure { e -> app.errorLog.log("Explorateur", e); snackbar.showSnackbar(e.message ?: "Erreur") }
            refresh++
        }
    }

    val importedMsg = stringResource(R.string.file_imported, "%s")
    val trashMsg = stringResource(R.string.moved_to_trash)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) op {
            val name = displayName(context, uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, 64L * 1024 * 1024) }
                ?: error("Lecture impossible.")
            importedMsg.format(files.importFile(dir, name, bytes))
        }
    }

    Scaffold(
        topBar = {
            BmsTopBar(
                stringResource(R.string.files_title), subtitle = "/" + dir,
                onBack = { nav.popBackStack() },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.search(id)) }) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
                    IconButton(onClick = { nav.navigate(Routes.trash(id)) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_trash)) }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.new_file)) }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.Filled.NoteAdd, null) },
                        onClick = { fabMenu = false; dialog = FileDialog.NewFile })
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_folder)) }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = { fabMenu = false; dialog = FileDialog.NewFolder })
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_file)) }, leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                        onClick = { fabMenu = false; importLauncher.launch(arrayOf("*/*")) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumb(dir) { target -> nav.navigate(Routes.files(id, target)) { launchSingleTop = true } }
            val list = entries
            when {
                list == null -> LoadingBox()
                list.isFailure -> EmptyState(list.exceptionOrNull()?.message ?: "Erreur")
                list.getOrThrow().isEmpty() -> EmptyState(stringResource(R.string.files_empty))
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(list.getOrThrow(), key = { it.path }) { e ->
                        FileRow(
                            e,
                            onOpen = {
                                if (e.isDirectory) nav.navigate(Routes.files(id, e.path)) else nav.navigate(Routes.edit(id, e.path))
                            },
                            onRename = { dialog = FileDialog.Rename(e) },
                            onDuplicate = { op { files.duplicate(e.path); null } },
                            onHistory = { nav.navigate(Routes.history(id, e.path)) },
                            onDelete = { dialog = FileDialog.Delete(e) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    fun child(name: String) = if (dir.isEmpty()) name else "$dir/$name"
    when (val d = dialog) {
        FileDialog.NewFile -> TextInputDialog(
            stringResource(R.string.new_file), stringResource(R.string.file_name), validate = { ProjectFiles.validateName(it) },
            onConfirm = { name -> dialog = null; op { files.createFile(child(name), starterContent(name)); null } },
            onDismiss = { dialog = null },
        )
        FileDialog.NewFolder -> TextInputDialog(
            stringResource(R.string.new_folder), stringResource(R.string.file_name), validate = { ProjectFiles.validateName(it) },
            onConfirm = { name -> dialog = null; op { files.createFolder(child(name)); null } },
            onDismiss = { dialog = null },
        )
        is FileDialog.Rename -> TextInputDialog(
            stringResource(R.string.rename), stringResource(R.string.file_name), initial = d.entry.name,
            validate = { ProjectFiles.validateName(it) },
            onConfirm = { name -> dialog = null; op { files.rename(d.entry.path, name); null } },
            onDismiss = { dialog = null },
        )
        is FileDialog.Delete -> ConfirmDialog(
            stringResource(R.string.delete_file_title, d.entry.name), stringResource(R.string.delete_file_body),
            confirmLabel = stringResource(R.string.delete), destructive = true,
            onConfirm = { dialog = null; op { files.moveToTrash(d.entry.path); trashMsg } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun Breadcrumb(dir: String, onNavigate: (String) -> Unit) {
    val parts = if (dir.isEmpty()) emptyList() else dir.split('/')
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onNavigate("") }) { Text("projet") }
        parts.forEachIndexed { i, p ->
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(parts.take(i + 1).joinToString("/")) }) { Text(p) }
        }
    }
}

@Composable
private fun FileRow(
    e: FileEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(e), null, tint = if (e.isDirectory) BmsColors.Secondary else BmsColors.Info, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!e.isDirectory) Text(formatSize(e.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; onDuplicate() })
                if (!e.isDirectory) DropdownMenuItem(text = { Text(stringResource(R.string.history)) }, onClick = { menu = false; onHistory() })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = BmsColors.Error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
