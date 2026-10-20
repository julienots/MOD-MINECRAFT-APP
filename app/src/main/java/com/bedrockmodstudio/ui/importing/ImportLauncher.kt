package com.bedrockmodstudio.ui.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.packaging.ImportOutcome
import com.bedrockmodstudio.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nom du fichier choisi dans le sélecteur Android. */
fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { return it }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
}

/** Lit tout le flux en refusant au-delà de [max] octets. */
fun readLimited(input: java.io.InputStream, max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw IllegalStateException("Fichier trop volumineux (${max / 1024 / 1024} Mo maximum).")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

class ImportState {
    var running by mutableStateOf(false)
    var rejected by mutableStateOf<String?>(null)
}

/**
 * Sélecteur de fichier + import d'addon. Renvoie la fonction qui ouvre le sélecteur.
 * En cas de succès, ouvre l'écran du rapport d'import.
 */
@Composable
fun rememberAddonImporter(nav: NavHostController, state: ImportState): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state.running = true
        scope.launch(Dispatchers.Main.immediate) {
            val app = context.container
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val name = displayName(context, uri)
                    context.contentResolver.openInputStream(uri)?.use { app.importer.import(it, name) }
                        ?: ImportOutcome.Rejected("Impossible de lire le fichier choisi.")
                } catch (e: Exception) {
                    app.errorLog.log("Import", e)
                    ImportOutcome.Rejected(e.message ?: e.javaClass.simpleName)
                }
            }
            state.running = false
            when (outcome) {
                is ImportOutcome.Success -> {
                    app.lastImportReport = outcome.report
                    app.lastImportProjectId = outcome.meta.id
                    nav.navigate(Routes.IMPORT_REPORT)
                }
                is ImportOutcome.Rejected -> state.rejected = outcome.message
            }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}
