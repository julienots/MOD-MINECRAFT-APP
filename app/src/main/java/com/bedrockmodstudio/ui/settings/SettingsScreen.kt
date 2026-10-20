package com.bedrockmodstudio.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.formatDate
import com.bedrockmodstudio.ui.project.VersionPicker
import com.bedrockmodstudio.ui.theme.BmsColors
import com.bedrockmodstudio.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var version by remember { mutableStateOf(BedrockVersions.byId(app.defaultVersion) ?: BedrockVersions.DEFAULT) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmClearLog by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    val copiedMsg = stringResource(R.string.settings_copied)
    val log by produceState("", refresh) { value = withContext(Dispatchers.IO) { app.errorLog.read() } }
    val trash by produceState<List<ProjectStore.TrashedProject>>(emptyList(), refresh) {
        value = withContext(Dispatchers.IO) { app.store.listTrash() }
    }
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.settings_title)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle(stringResource(R.string.settings_default_version))
            VersionPicker(version) { version = it; app.defaultVersion = it.id }

            SectionTitle(stringResource(R.string.projects_trash))
            if (trash.isEmpty()) Text(stringResource(R.string.settings_projects_trash_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            trash.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.deleted_on, formatDate(t.deletedAt)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.Main.immediate) {
                            val r = withContext(Dispatchers.IO) { runCatching { app.store.restoreFromTrash(t.trashName) } }
                            r.exceptionOrNull()?.let { snackbar.showSnackbar(it.message ?: "Erreur") }
                            refresh++
                        }
                    }) { Text(stringResource(R.string.restore)) }
                }
            }
            if (trash.isNotEmpty()) TextButton(onClick = { confirmEmptyTrash = true }) {
                Text(stringResource(R.string.trash_empty_action), color = BmsColors.Error)
            }

            SectionTitle(stringResource(R.string.settings_error_log))
            HelpText(stringResource(R.string.settings_error_log_desc))
            if (log.isBlank()) Text(stringResource(R.string.settings_error_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(log.takeLast(20_000), style = MonoStyle)
                }
                Row {
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("journal", log))
                        scope.launch(Dispatchers.Main.immediate) { snackbar.showSnackbar(copiedMsg) }
                    }) { Text(stringResource(R.string.settings_copy)) }
                    TextButton(onClick = { confirmClearLog = true }) { Text(stringResource(R.string.settings_error_log_clear), color = BmsColors.Error) }
                }
            }

            SectionTitle(stringResource(R.string.settings_limits))
            Text(stringResource(R.string.settings_limits_body), style = MaterialTheme.typography.bodyMedium)
            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_about_body, appVersion), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmClearLog) ConfirmDialog(
        stringResource(R.string.settings_error_log_clear_title), "", confirmLabel = stringResource(R.string.settings_error_log_clear), destructive = true,
        onConfirm = { confirmClearLog = false; scope.launch(Dispatchers.Main.immediate) { withContext(Dispatchers.IO) { app.errorLog.clear() }; refresh++ } },
        onDismiss = { confirmClearLog = false },
    )
    if (confirmEmptyTrash) ConfirmDialog(
        stringResource(R.string.trash_empty_title), stringResource(R.string.trash_empty_body),
        confirmLabel = stringResource(R.string.trash_empty_action), destructive = true,
        onConfirm = { confirmEmptyTrash = false; scope.launch(Dispatchers.Main.immediate) { withContext(Dispatchers.IO) { app.store.emptyTrash() }; refresh++ } },
        onDismiss = { confirmEmptyTrash = false },
    )
}
