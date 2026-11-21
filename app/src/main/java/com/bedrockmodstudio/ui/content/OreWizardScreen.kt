package com.bedrockmodstudio.ui.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.BlockGenerator
import com.bedrockmodstudio.core.bedrock.BlockSpec
import com.bedrockmodstudio.core.bedrock.BlockStyle
import com.bedrockmodstudio.core.bedrock.OreDrop
import com.bedrockmodstudio.core.bedrock.OreGenerator
import com.bedrockmodstudio.core.bedrock.OreSpec
import com.bedrockmodstudio.core.bedrock.OreWorld
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.ColorRow
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LabeledSlider
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.PALETTE
import com.bedrockmodstudio.ui.common.PngPreview
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Assistant « Créer un minerai » : bloc de minerai, gemme ou lingot, cuisson au four,
 * et génération naturelle dans le monde choisi (filons, hauteurs, fréquence).
 */
@Composable
fun OreWizardScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    LaunchedEffect(id) { withContext(Dispatchers.IO) { meta = runCatching { app.store.load(id) }.getOrNull() } }
    val (ctl, generate) = rememberGenerator(id, "Assistant minerai")

    var name by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var world by remember { mutableStateOf(OreWorld.OVERWORLD) }
    var oreColor by remember { mutableStateOf(PALETTE[8]) }
    var drop by remember { mutableStateOf(OreDrop.GEM) }
    var veinSize by remember { mutableStateOf(8f) }
    var veins by remember { mutableStateOf(4f) }
    var minY by remember { mutableStateOf(-48f) }
    var maxY by remember { mutableStateOf(16f) }
    var hardness by remember { mutableStateOf(3f) }
    var dropMax by remember { mutableStateOf(1f) }
    var smelting by remember { mutableStateOf(true) }

    val shortId = Identifiers.slug(name).replace('.', '_').ifEmpty { "minerai" }
    val resultName = result.ifBlank { if (name.isBlank()) "" else (if (drop == OreDrop.GEM) "Gemme de " else "Lingot de ") + name.trim().lowercase(Locale.FRANCE) }
    val spec = OreSpec(
        displayName = name.trim(), shortId = shortId, world = world, oreColor = oreColor, veinSize = veinSize.toInt(),
        veinsPerChunk = veins.toInt(), minY = minY.toInt(), maxY = maxY.toInt(), hardness = Math.round(hardness * 10) / 10.0,
        drop = drop, resultName = resultName, dropMin = 1, dropMax = dropMax.toInt().coerceAtLeast(1), smelting = smelting,
    )
    val errors = spec.validate()
    val texture = remember(world, oreColor, shortId) {
        runCatching { BlockGenerator.texture(BlockSpec("x", shortId = spec.oreBlockId, style = BlockStyle.ORE, mainColor = spec.stoneColor, accentColor = oreColor)) }.getOrNull()
    }

    Scaffold(topBar = { BmsTopBar("Créer un minerai", subtitle = meta?.name, onBack = { nav.popBackStack() }) }) { padding ->
        val m = meta
        if (m == null || ctl.busy) { LoadingBox(); return@Scaffold }
        val profile = BedrockVersions.byId(m.targetVersion) ?: BedrockVersions.DEFAULT
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GenerationResult(ctl, "En jeu : crée un NOUVEAU monde avec le pack activé (les tronçons déjà générés ne changent pas), " +
                "puis creuse entre Y = ${spec.minY} et Y = ${spec.maxY}. Pour vérifier vite : /give @s ${m.namespace}:${spec.oreBlockId}")

            OutlinedTextField(name, { name = it; ctl.written = null }, Modifier.fillMaxWidth().testTag("ore_name"), label = { Text("Nom du minerai (ex. Saphir)") }, singleLine = true)
            HelpText("Bloc : ${m.namespace}:${spec.oreBlockId}")
            SectionTitle("Ce que donne le minerai")
            ChoiceChips(OreDrop.entries, drop, { it.labelFr }) { drop = it }
            OutlinedTextField(result, { result = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(if (drop == OreDrop.GEM) "Nom de la gemme" else "Nom du lingot") }, placeholder = { Text(resultName) }, singleLine = true)
            HelpText("Objet : ${m.namespace}:${spec.resultShortId}")
            if (drop == OreDrop.GEM) LabeledSlider("Gemmes par bloc : 1 à ${dropMax.toInt()}", null, dropMax, 1f..8f) { dropMax = it }

            SectionTitle("Monde et couleur")
            ChoiceChips(OreWorld.entries, world, { it.labelFr }) { w ->
                world = w
                minY = minY.coerceIn(w.minY.toFloat(), w.maxY.toFloat()); maxY = maxY.coerceIn(w.minY.toFloat(), w.maxY.toFloat())
                if (minY >= maxY) { minY = w.minY.toFloat(); maxY = (w.minY + 64).toFloat() }
            }
            ColorRow(oreColor) { oreColor = it }
            PngPreview(texture, 112.dp)

            SectionTitle("Génération dans le monde")
            LabeledSlider("Blocs par filon : ${veinSize.toInt()}", "Diamant ≈ 8, fer ≈ 9, charbon ≈ 17.", veinSize, 1f..32f) { veinSize = it }
            LabeledSlider("Filons par tronçon de 16 × 16 : ${veins.toInt()}", "Plus il y en a, plus le minerai est commun.", veins, 1f..40f) { veins = it }
            LabeledSlider("Hauteur minimale : Y = ${minY.toInt()}", null, minY, world.minY.toFloat()..world.maxY.toFloat()) { minY = minOf(it, maxY - 1) }
            LabeledSlider("Hauteur maximale : Y = ${maxY.toInt()}", null, maxY, world.minY.toFloat()..world.maxY.toFloat()) { maxY = maxOf(it, minY + 1) }
            LabeledSlider("Dureté : ${String.format(Locale.FRANCE, "%.1f", hardness)} s à la main", "Pierre ≈ 1,5 ; minerai de diamant ≈ 3.", hardness, 0.5f..20f) { hardness = it }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) { Text("Cuisson au four"); HelpText("Le minerai cuit au four ou au haut fourneau donne l'objet.") }
                Switch(smelting, { smelting = it })
            }

            if (errors.isNotEmpty() && name.isNotBlank()) InfoCard(errors.joinToString("\n"), color = BmsColors.Error)
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp).testTag("ore_generate"),
                onClick = { generate(OreGenerator.generate(m.namespace, spec, profile)) },
            ) { Text("Créer le minerai") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
