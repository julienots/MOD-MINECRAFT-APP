package com.bedrockmodstudio.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val PALETTE = listOf(
    0xFF4E8A3E, 0xFF2F5A23, 0xFF6B4F3A, 0xFF8B5A2B, 0xFFB0B0B0, 0xFF3C3C46, 0xFF1F2A44, 0xFF2E6FA8,
    0xFF4FC3F7, 0xFF7A2E8E, 0xFFB03A2E, 0xFFE0A030, 0xFFFFD54F, 0xFFE8E4D8, 0xFF2A9D8F, 0xFF101014,
).map { it.toInt() }

/** Objets vanilla courants (identifiants Bedrock) proposés dans les champs d'objet. */
val COMMON_ITEMS = listOf(
    "minecraft:diamond" to "Diamant", "minecraft:iron_ingot" to "Lingot de fer", "minecraft:gold_ingot" to "Lingot d'or",
    "minecraft:copper_ingot" to "Lingot de cuivre", "minecraft:netherite_ingot" to "Lingot de netherite",
    "minecraft:emerald" to "Émeraude", "minecraft:amethyst_shard" to "Éclat d'améthyste", "minecraft:quartz" to "Quartz",
    "minecraft:lapis_lazuli" to "Lapis-lazuli", "minecraft:redstone" to "Redstone", "minecraft:coal" to "Charbon",
    "minecraft:stick" to "Bâton", "minecraft:string" to "Ficelle", "minecraft:leather" to "Cuir",
    "minecraft:cobblestone" to "Pierre taillée", "minecraft:stone" to "Pierre", "minecraft:oak_planks" to "Planches de chêne",
    "minecraft:sand" to "Sable", "minecraft:glass" to "Verre", "minecraft:obsidian" to "Obsidienne",
    "minecraft:apple" to "Pomme", "minecraft:wheat" to "Blé", "minecraft:sugar" to "Sucre", "minecraft:bone" to "Os",
    "minecraft:gunpowder" to "Poudre à canon", "minecraft:blaze_powder" to "Poudre de blaze", "minecraft:ender_pearl" to "Perle de l'Ender",
)

@Composable
fun LabeledSlider(label: String, help: String?, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, Modifier.padding(top = 12.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range)
    if (help != null) HelpText(help)
}

@Composable
fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PALETTE.forEach { c ->
            Box(
                Modifier.size(40.dp).background(Color(c), CircleShape)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Color.White else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onSelect(c) },
            )
        }
    }
}

@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

/** Aperçu agrandi d'une texture PNG générée, sans lissage (pixel art net). */
@Composable
fun PngPreview(png: ByteArray?, size: Dp = 160.dp) {
    val bmp = remember(png) { png?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    if (bmp != null) Image(
        bmp.asImageBitmap(), null, filterQuality = FilterQuality.None,
        modifier = Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Champ d'identifiant d'objet avec suggestions (objets du projet en premier, puis objets vanilla courants). */
@Composable
fun ItemField(label: String, value: String, onChange: (String) -> Unit, projectItems: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        OutlinedTextField(value, { onChange(it.trim().lowercase()) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            placeholder = { Text("minecraft:diamond") })
        val suggestions = projectItems.map { it to it.substringAfter(':') } + COMMON_ITEMS
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.filter { value.isBlank() || it.first.contains(value) || it.second.contains(value, true) }.take(30).forEach { (id, name) ->
                AssistChip(onClick = { onChange(id) }, label = { Text(name) })
            }
        }
    }
}

/**
 * Applique un contenu généré au projet : demande confirmation avant de remplacer des fichiers,
 * puis affiche la liste des fichiers écrits.
 */
class GenerationController {
    var busy by mutableStateOf(false)
    var written by mutableStateOf<List<String>?>(null)
    var skipped by mutableStateOf<List<String>>(emptyList())
    var pending by mutableStateOf<Pair<GeneratedContent, List<String>>?>(null)
}

@Composable
fun rememberGenerator(projectId: String, reason: String): Pair<GenerationController, (GeneratedContent) -> Unit> {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val ctl = remember { GenerationController() }

    fun apply(content: GeneratedContent, overwrite: Boolean) {
        ctl.busy = true
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching { ContentInstaller.install(app.store, projectId, content, overwrite, reason).also { app.store.touch(projectId) } }
            }
            ctl.busy = false
            r.onSuccess { ctl.written = it.written; ctl.skipped = it.skippedExisting; snackbar.showSnackbar(context.getString(R.string.mob_created, it.written.size)) }
                .onFailure { app.errorLog.log(reason, it); snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    val start: (GeneratedContent) -> Unit = { content ->
        scope.launch(Dispatchers.Main.immediate) {
            val conflicts = withContext(Dispatchers.IO) { app.store.files(projectId).conflicts(content) }
            if (conflicts.isEmpty()) apply(content, overwrite = false) else ctl.pending = content to conflicts
        }
    }

    ctl.pending?.let { (content, conflicts) ->
        ConfirmDialog(
            stringResource(R.string.mob_overwrite_title),
            stringResource(R.string.mob_overwrite_body, conflicts.joinToString("\n") { "• $it" }),
            confirmLabel = stringResource(R.string.mob_overwrite_action), destructive = true,
            onConfirm = { ctl.pending = null; apply(content, overwrite = true) },
            onDismiss = { ctl.pending = null },
        )
    }
    return ctl to start
}

/** Carte de résultat : fichiers écrits, entrées conservées, conseil pour tester en jeu. */
@Composable
fun GenerationResult(ctl: GenerationController, testHint: String) {
    val files = ctl.written ?: return
    var text = stringResource(R.string.mob_files_written) + "\n" + files.joinToString("\n") { "• $it" }
    if (ctl.skipped.isNotEmpty()) text += "\n\n" + stringResource(R.string.gen_kept_existing) + "\n" + ctl.skipped.joinToString("\n") { "• $it" }
    InfoCard(text + "\n\n" + testHint, color = BmsColors.Primary)
}
