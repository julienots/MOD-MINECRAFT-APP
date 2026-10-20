package com.bedrockmodstudio.ui.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.packaging.ExportKind
import com.bedrockmodstudio.core.packaging.ExportOutcome
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.ActionTile
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.theme.BmsColors
import com.bedrockmodstudio.ui.validation.IssueCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"

@Composable
fun ExportScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var bump by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ExportOutcome?>(null) }
    val savedMsg = stringResource(R.string.export_saved)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = (outcome as? ExportOutcome.Success)?.file
        if (uri != null && file != null) scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Écriture impossible") }
            }
            r.onFailure { app.errorLog.log("Enregistrement de l'export", it) }
            snackbar.showSnackbar(r.exceptionOrNull()?.message ?: savedMsg)
        }
    }

    fun start(kind: ExportKind) {
        running = true; outcome = null
        scope.launch(Dispatchers.Main.immediate) {
            outcome = withContext(Dispatchers.IO) { app.exporter.export(id, kind, app.exportDir, bumpVersion = bump) }
            (outcome as? ExportOutcome.Failed)?.let { app.errorLog.log("Export", message = it.message) }
            running = false
        }
    }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.export_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.export_bump))
                    HelpText(stringResource(R.string.export_bump_help))
                }
                Switch(checked = bump, onCheckedChange = { bump = it })
            }
            val tile = Modifier.fillMaxWidth()
            ActionTile(Icons.Filled.Extension, ExportKind.MCADDON.labelFr, stringResource(R.string.export_desc_mcaddon), tile, BmsColors.Primary) { if (!running) start(ExportKind.MCADDON) }
            ActionTile(Icons.Filled.Psychology, ExportKind.MCPACK_BEHAVIOR.labelFr, stringResource(R.string.export_desc_bp), tile, BmsColors.Info) { if (!running) start(ExportKind.MCPACK_BEHAVIOR) }
            ActionTile(Icons.Filled.Palette, ExportKind.MCPACK_RESOURCE.labelFr, stringResource(R.string.export_desc_rp), tile, BmsColors.Secondary) { if (!running) start(ExportKind.MCPACK_RESOURCE) }
            ActionTile(Icons.Filled.Backup, ExportKind.BACKUP_ZIP.labelFr, stringResource(R.string.export_desc_backup), tile, BmsColors.TextMuted) { if (!running) start(ExportKind.BACKUP_ZIP) }

            if (running) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                CircularProgressIndicator(); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.export_running))
            }

            when (val o = outcome) {
                is ExportOutcome.Success -> {
                    SectionTitle(stringResource(R.string.export_done, o.file.name))
                    o.steps.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall, color = BmsColors.Primary) }
                    o.report?.warnings?.takeIf { it.isNotEmpty() }?.let { w ->
                        Text("${w.size} avertissement(s) : voir « Vérifier ».", style = MaterialTheme.typography.bodySmall, color = BmsColors.Warning)
                    }
                    Button(onClick = { saveLauncher.launch(o.file.name) }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.export_save_as)) }
                    if (!o.file.name.endsWith(".zip")) {
                        OutlinedButton(onClick = { scope.launch(Dispatchers.Main.immediate) { openWithMinecraft(context, o.file)?.let { snackbar.showSnackbar(it) } } }, Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(R.string.export_open_minecraft))
                        }
                    }
                    OutlinedButton(onClick = { share(context, o.file) }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.export_share)) }
                    InfoCard(stringResource(R.string.export_test_help))
                }
                is ExportOutcome.Blocked -> {
                    InfoCard(stringResource(R.string.export_blocked, o.report.errors.size), color = BmsColors.Error)
                    o.report.errors.take(20).forEach { issue ->
                        IssueCard(issue) { issue.file?.let { nav.navigate(Routes.edit(id, it, issue.line ?: 0)) } }
                    }
                    OutlinedButton(onClick = { nav.navigate(Routes.validate(id)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_see_errors)) }
                }
                is ExportOutcome.Failed -> InfoCard(stringResource(R.string.export_failed, o.message), color = BmsColors.Error)
                null -> Unit
            }
        }
    }
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".files", file)

/** Ouvre le fichier dans Minecraft s'il est installé. Renvoie un message à afficher en cas d'échec. */
private fun openWithMinecraft(context: Context, file: File): String? {
    val uri = uriFor(context, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/octet-stream")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val installed = runCatching { context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, 0) }.isSuccess
    return try {
        if (installed) context.startActivity(Intent(intent).setPackage(MINECRAFT_PACKAGE))
        else return context.getString(R.string.export_minecraft_missing)
        null
    } catch (e: ActivityNotFoundException) {
        // Minecraft est installé mais n'accepte pas cette ouverture directe : on propose les applications compatibles.
        try {
            context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
        } catch (e2: ActivityNotFoundException) {
            context.getString(R.string.export_no_app)
        }
    }
}

private fun share(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
