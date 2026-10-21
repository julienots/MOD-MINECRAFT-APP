package com.bedrockmodstudio.ui.content

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockFeature
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.DimensionGenerator
import com.bedrockmodstudio.core.bedrock.DimensionSpec
import com.bedrockmodstudio.core.bedrock.DimensionTerrain
import com.bedrockmodstudio.core.bedrock.ProjectIndex
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
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DimensionWizardScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var projectBlocks by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            meta = runCatching { app.store.load(id) }.getOrNull()
            projectBlocks = runCatching { ProjectIndex.scan(app.store.files(id)).filter { it.kind == ProjectIndex.Kind.BLOCK }.map { it.id } }
                .getOrDefault(emptyList())
        }
    }
    val (ctl, generate) = rememberGenerator(id, "Assistant dimension")

    var name by remember { mutableStateOf("") }
    var shortId by remember { mutableStateOf("") }
    var idEdited by remember { mutableStateOf(false) }
    var terrain by remember { mutableStateOf(DimensionTerrain.ISLANDS) }
    var surface by remember { mutableStateOf("minecraft:moss_block") }
    var filler by remember { mutableStateOf("minecraft:end_stone") }
    var radius by remember { mutableStateOf(12f) }
    var spawnY by remember { mutableStateOf(80f) }
    var portal by remember { mutableStateOf(true) }
    var key by remember { mutableStateOf(true) }
    var command by remember { mutableStateOf(true) }
    var color by remember { mutableStateOf(PALETTE[9]) }
    var message by remember { mutableStateOf("") }

    val effectiveId = if (idEdited) shortId else Identifiers.slug(name).replace('.', '_')
    val spec = DimensionSpec(
        displayName = name.trim(), shortId = effectiveId, terrain = terrain, surfaceBlock = surface, fillerBlock = filler,
        radius = radius.toInt(), spawnY = spawnY.toInt(), withPortal = portal, withKey = key, withCommand = command, portalColor = color,
        arrivalMessage = message.ifBlank { "Bienvenue dans ${name.trim()} !" },
    )
    val errors = spec.validate()
    val blockSuggestions = projectBlocks + listOf(
        "minecraft:moss_block", "minecraft:grass_block", "minecraft:end_stone", "minecraft:stone", "minecraft:sand",
        "minecraft:snow", "minecraft:obsidian", "minecraft:amethyst_block", "minecraft:netherrack", "minecraft:blackstone",
    )

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.dim_title), subtitle = meta?.name, onBack = { nav.popBackStack() }) }) { padding ->
        val m = meta
        if (m == null || ctl.busy) { LoadingBox(); return@Scaffold }
        val profile = BedrockVersions.byId(m.targetVersion) ?: BedrockVersions.DEFAULT
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GenerationResult(ctl, stringResource(R.string.dim_test_hint, "${m.namespace}:${effectiveId}"))
            if (!profile.supports(BedrockFeature.CUSTOM_DIMENSIONS)) {
                InfoCard(stringResource(R.string.dim_unsupported, profile.label), color = BmsColors.Error)
                return@Column
            }
            InfoCard(stringResource(R.string.dim_limits))

            OutlinedTextField(name, { name = it; ctl.written = null }, Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(stringResource(R.string.dim_name)) }, singleLine = true)
            OutlinedTextField(effectiveId, { shortId = it.lowercase(); idEdited = true }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.mob_id)) }, singleLine = true)
            HelpText(stringResource(R.string.mob_id_help, m.namespace, effectiveId))

            SectionTitle(stringResource(R.string.dim_terrain))
            ChoiceChips(DimensionTerrain.entries, terrain, { it.labelFr }) { terrain = it }
            HelpText(terrain.explanationFr)
            OutlinedTextField(surface, { surface = it.trim().lowercase() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dim_surface)) }, singleLine = true)
            HelpText(stringResource(R.string.dim_block_help))
            BlockChips(blockSuggestions) { surface = it }
            OutlinedTextField(filler, { filler = it.trim().lowercase() }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dim_filler)) }, singleLine = true)
            BlockChips(blockSuggestions) { filler = it }
            LabeledSlider(stringResource(R.string.dim_radius, radius.toInt()), null, radius, 4f..32f) { radius = it }
            LabeledSlider(stringResource(R.string.dim_spawn_y, spawnY.toInt()), null, spawnY, 10f..200f) { spawnY = it }

            SectionTitle(stringResource(R.string.dim_access))
            Toggle(stringResource(R.string.dim_portal), stringResource(R.string.dim_portal_help), portal) { portal = it }
            if (portal) { Text(stringResource(R.string.dim_portal_color)); ColorRow(color) { color = it } }
            Toggle(stringResource(R.string.dim_key), stringResource(R.string.dim_key_help), key) { key = it }
            Toggle(stringResource(R.string.dim_command), stringResource(R.string.dim_command_help, "${m.namespace}:$effectiveId"), command) { command = it }
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.dim_message)) }, placeholder = { Text("Bienvenue dans ${name.ifBlank { "…" }} !") })

            if (errors.isNotEmpty() && name.isNotBlank()) InfoCard(errors.joinToString("\n"), color = BmsColors.Error)
            Spacer(Modifier.height(16.dp))
            Button(enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = { generate(DimensionGenerator.generate(m.namespace, spec, profile)) }) { Text(stringResource(R.string.dim_generate)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BlockChips(blocks: List<String>, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        blocks.distinct().forEach { b -> androidx.compose.material3.AssistChip(onClick = { onPick(b) }, label = { Text(b.substringAfter(':').replace('_', ' ')) }) }
    }
}

@Composable
private fun Toggle(title: String, help: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) { Text(title); HelpText(help) }
        Switch(value, onChange)
    }
}
