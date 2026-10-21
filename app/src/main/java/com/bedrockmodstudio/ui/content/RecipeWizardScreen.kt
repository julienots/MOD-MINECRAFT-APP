package com.bedrockmodstudio.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.bedrock.RecipeGenerator
import com.bedrockmodstudio.core.bedrock.RecipeSpec
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.COMMON_ITEMS
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.ItemField
import com.bedrockmodstudio.ui.common.LabeledSlider
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.theme.BmsColors

private enum class RecipeType(val labelFr: String, val helpFr: String) {
    SHAPED("Établi (avec forme)", "Les ingrédients doivent être placés exactement comme dans la grille."),
    SHAPELESS("Établi (sans forme)", "Les ingrédients peuvent être placés n'importe où dans la grille."),
    FURNACE("Four", "Cuire un objet pour en obtenir un autre."),
}

/** Nom court d'un objet pour l'afficher dans une case de la grille. */
private fun shortLabel(id: String): String =
    COMMON_ITEMS.firstOrNull { it.first == id }?.second ?: id.substringAfter(':').replace('_', ' ')

@Composable
fun RecipeWizardScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var projectItems by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(id) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            meta = runCatching { app.store.load(id) }.getOrNull()
            projectItems = runCatching { ProjectIndex.usableItems(app.store.files(id)) }.getOrDefault(emptyList())
        }
    }
    val (ctl, generate) = rememberGenerator(id, "Assistant recette")

    var type by remember { mutableStateOf(RecipeType.SHAPED) }
    val grid = remember { mutableStateListOf<String?>(null, null, null, null, null, null, null, null, null) }
    val ingredients = remember { mutableStateListOf<String>() }
    var result by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(1f) }
    var input by remember { mutableStateOf("") }
    var smoker by remember { mutableStateOf(false) }
    var blast by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Int?>(null) } // case de la grille, ou -1 = nouvel ingrédient sans forme
    var recipeId by remember { mutableStateOf("") }
    var idEdited by remember { mutableStateOf(false) }

    val output = result
    val autoId = Identifiers.slug(output.substringAfter(':')).replace('.', '_').ifEmpty { "recette" } +
        when (type) { RecipeType.FURNACE -> "_four"; RecipeType.SHAPELESS -> "_sans_forme"; else -> "" }
    val effectiveId = if (idEdited) recipeId else autoId
    val spec: RecipeSpec = when (type) {
        RecipeType.SHAPED -> RecipeSpec.Shaped(effectiveId, grid.toList(), output, count.toInt())
        RecipeType.SHAPELESS -> RecipeSpec.Shapeless(effectiveId, ingredients.toList(), output, count.toInt())
        RecipeType.FURNACE -> RecipeSpec.Furnace(effectiveId, input, output, smoker, blast)
    }
    val errors = if (output.isBlank()) listOf(stringResource(R.string.recipe_need_result)) else spec.validate()

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.recipe_title), subtitle = meta?.name, onBack = { nav.popBackStack() }) }) { padding ->
        val m = meta
        if (m == null || ctl.busy) { LoadingBox(); return@Scaffold }
        val profile = BedrockVersions.byId(m.targetVersion) ?: BedrockVersions.DEFAULT
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GenerationResult(ctl, stringResource(R.string.recipe_test_hint))
            SectionTitle(stringResource(R.string.recipe_type))
            ChoiceChips(RecipeType.entries, type, { it.labelFr }) { type = it; ctl.written = null }
            HelpText(type.helpFr)

            when (type) {
                RecipeType.SHAPED -> {
                    HelpText(stringResource(R.string.recipe_grid_help))
                    for (r in 0 until 3) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        for (c in 0 until 3) GridCell(grid[r * 3 + c]) { picking = r * 3 + c }
                    }
                    TextButton(onClick = { for (i in 0 until 9) grid[i] = null }) { Text(stringResource(R.string.recipe_clear)) }
                }
                RecipeType.SHAPELESS -> {
                    ingredients.forEachIndexed { i, ing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("• ${shortLabel(ing)}  ($ing)", Modifier.weight(1f))
                            TextButton(onClick = { ingredients.removeAt(i) }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                    if (ingredients.size < 9) OutlinedButton(onClick = { picking = -1 }) { Text(stringResource(R.string.recipe_add_ingredient)) }
                }
                RecipeType.FURNACE -> {
                    ItemField(stringResource(R.string.recipe_input), input, { input = it }, projectItems)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.recipe_smoker), Modifier.weight(1f)); Switch(smoker, { smoker = it }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.recipe_blast), Modifier.weight(1f)); Switch(blast, { blast = it }) }
                }
            }

            SectionTitle(stringResource(R.string.recipe_result))
            ItemField(stringResource(R.string.recipe_result), result, { result = it }, projectItems)
            if (type != RecipeType.FURNACE) LabeledSlider(stringResource(R.string.recipe_count, count.toInt()), null, count, 1f..64f) { count = it }
            androidx.compose.material3.OutlinedTextField(
                effectiveId, { recipeId = it.lowercase(); idEdited = true }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.recipe_id)) }, singleLine = true,
            )
            HelpText(stringResource(R.string.mob_id_help, m.namespace, effectiveId))

            if (errors.isNotEmpty()) InfoCard(errors.joinToString("\n"), color = BmsColors.Error)
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = { generate(RecipeGenerator.generate(m.namespace, spec, profile)) },
            ) { Text(stringResource(R.string.recipe_generate)) }
            Spacer(Modifier.height(32.dp))
        }

        picking?.let { slot ->
            var value by remember(slot) { mutableStateOf(if (slot >= 0) grid[slot] ?: "" else "") }
            AlertDialog(
                onDismissRequest = { picking = null },
                title = { Text(stringResource(R.string.recipe_pick_item)) },
                text = { ItemField(stringResource(R.string.recipe_item), value, { value = it }, projectItems) },
                confirmButton = {
                    TextButton(onClick = {
                        if (slot >= 0) grid[slot] = value.ifBlank { null } else if (value.isNotBlank()) ingredients.add(value)
                        picking = null
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = {
                    if (slot >= 0) TextButton(onClick = { grid[slot] = null; picking = null }) { Text(stringResource(R.string.recipe_empty_cell)) }
                    else TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

@Composable
private fun GridCell(item: String?, onClick: () -> Unit) {
    Box(
        Modifier.size(92.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(item?.let { shortLabel(it) } ?: "+", textAlign = TextAlign.Center, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            color = if (item == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}
