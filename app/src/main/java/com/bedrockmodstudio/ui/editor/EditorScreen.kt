package com.bedrockmodstudio.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.formatSize
import com.bedrockmodstudio.ui.theme.BmsColors
import com.bedrockmodstudio.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(nav: NavHostController, id: String, path: String, line: Int) {
    val app = LocalContext.current.container
    val vm: EditorViewModel = viewModel(key = "$id/$path") {
        EditorViewModel(app.store.files(id), path, app.errorLog, line)
    }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var askLeave by remember { mutableStateOf(false) }
    var askInvalidSave by remember { mutableStateOf(false) }
    var askFormat by remember { mutableStateOf(false) }
    var leaveAfterSave by remember { mutableStateOf(false) }
    val savedMsg = stringResource(R.string.editor_saved)

    fun doSave(thenLeave: Boolean = false) {
        scope.launch(Dispatchers.Main.immediate) {
            if (vm.save()) {
                snackbar.showSnackbar(savedMsg)
                if (thenLeave) nav.popBackStack()
            }
        }
    }
    fun requestSave(thenLeave: Boolean = false) {
        leaveAfterSave = thenLeave
        if (vm.isJson && vm.jsonError != null) askInvalidSave = true else doSave(thenLeave)
    }

    BackHandler(enabled = vm.dirty) { askLeave = true }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        topBar = {
            BmsTopBar(
                path.substringAfterLast('/') + if (vm.dirty) " •" else "",
                subtitle = path,
                onBack = { if (vm.dirty) askLeave = true else nav.popBackStack() },
                actions = {
                    if (vm.kind == EditorViewModel.Kind.TEXT) {
                        IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                        IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                        if (vm.isJson) IconButton(onClick = { if (vm.hasComments()) askFormat = true else vm.format() }, enabled = vm.jsonError == null) {
                            Icon(Icons.Filled.AutoFixHigh, stringResource(R.string.editor_format))
                        }
                        IconButton(onClick = { requestSave() }, enabled = vm.dirty) { Icon(Icons.Filled.Save, stringResource(R.string.save)) }
                    }
                    IconButton(onClick = { nav.navigate(Routes.history(id, path)) }) { Icon(Icons.Filled.History, stringResource(R.string.history)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when (vm.kind) {
                EditorViewModel.Kind.LOADING -> LoadingBox()
                EditorViewModel.Kind.TEXT -> Column {
                    if (path.contains("/particles/") && path.endsWith(".json")) androidx.compose.material3.OutlinedButton(
                        onClick = { nav.navigate(Routes.particle(id, path)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.particles_open_editor)) }
                    if (path.endsWith(".geo.json", ignoreCase = true)) androidx.compose.material3.OutlinedButton(
                        onClick = { nav.navigate(Routes.model(id, path)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.model_open_editor)) }
                    TextEditor(vm)
                }
                EditorViewModel.Kind.IMAGE -> Column {
                    if (path.endsWith(".png", ignoreCase = true)) androidx.compose.material3.Button(
                        onClick = { nav.navigate(Routes.texture(id, path)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.tex_edit)) }
                    ImageViewer(vm)
                }
                EditorViewModel.Kind.BINARY -> Column(Modifier.padding(16.dp)) {
                    if (path.endsWith(".mcstructure", ignoreCase = true)) androidx.compose.material3.Button(
                        onClick = { nav.navigate(Routes.structure(id, path)) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) { Text(stringResource(R.string.file_open_structure)) }
                    InfoCard(stringResource(R.string.editor_binary))
                    Text(stringResource(R.string.file_size, formatSize(vm.size)), Modifier.padding(top = 12.dp))
                }
                EditorViewModel.Kind.TOO_BIG -> Column(Modifier.padding(16.dp)) { InfoCard(stringResource(R.string.editor_too_big), color = BmsColors.Warning) }
                EditorViewModel.Kind.MISSING -> Column(Modifier.padding(16.dp)) { InfoCard(vm.message ?: "Fichier introuvable : $path", color = BmsColors.Error) }
            }
        }
    }

    if (vm.draftOffer != null) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.editor_draft_title)) },
        text = { Text(stringResource(R.string.editor_draft_body)) },
        confirmButton = { TextButton(onClick = vm::acceptDraft) { Text(stringResource(R.string.editor_draft_recover)) } },
        dismissButton = { TextButton(onClick = vm::rejectDraft) { Text(stringResource(R.string.editor_draft_ignore)) } },
    )
    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_body)) },
        confirmButton = { TextButton(onClick = { askLeave = false; requestSave(thenLeave = true) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { askLeave = false; vm.discardDraft(); nav.popBackStack() }) {
                    Text(stringResource(R.string.editor_discard), color = BmsColors.Error)
                }
                TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    if (askInvalidSave) ConfirmDialog(
        stringResource(R.string.editor_invalid_save_title), stringResource(R.string.editor_invalid_save_body),
        confirmLabel = stringResource(R.string.save),
        onConfirm = { askInvalidSave = false; doSave(thenLeave = leaveAfterSave) },
        onDismiss = { askInvalidSave = false },
    )
    if (askFormat) ConfirmDialog(
        stringResource(R.string.editor_format), "La mise en forme supprime les commentaires (// et /* */) du fichier. Continuer ?",
        onConfirm = { askFormat = false; vm.format() },
        onDismiss = { askFormat = false },
    )
}

@Composable
private fun TextEditor(vm: EditorViewModel) {
    val highlighter = remember(vm.path) { SyntaxHighlighter.forPath(vm.path) }
    val lineCount = remember(vm.value.text) { vm.value.text.count { it == '\n' } + 1 }
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val minWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    Column(Modifier.fillMaxSize()) {
        if (vm.isJson) {
            val e = vm.jsonError
            Row(
                Modifier.fillMaxWidth()
                    .background((if (e == null) BmsColors.Primary else BmsColors.Error).copy(alpha = 0.15f))
                    .clickable(enabled = e != null) { vm.goToError() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (e == null) stringResource(R.string.editor_json_ok) else stringResource(R.string.editor_json_error, e.line, e.column, e.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e == null) BmsColors.Primary else BmsColors.Error,
                )
            }
        }
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                numbers, style = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = vm.value,
                    onValueChange = vm::onEdit,
                    textStyle = MonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(BmsColors.Primary),
                    visualTransformation = highlighter,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.widthIn(min = minWidth).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ImageViewer(vm: EditorViewModel) {
    val bmp = vm.bitmap ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    val state = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(0.5f, 16f) }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.editor_image_size, bmp.width, bmp.height) + " · " + formatSize(vm.size))
        Box(Modifier.fillMaxSize().transformable(state), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bmp.asImageBitmap(), contentDescription = vm.path,
                filterQuality = FilterQuality.None, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .graphicsLayer(scaleX = scale, scaleY = scale),
            )
        }
    }
}
