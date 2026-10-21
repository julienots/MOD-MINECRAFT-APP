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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.BlockGenerator
import com.bedrockmodstudio.core.bedrock.BlockSound
import com.bedrockmodstudio.core.bedrock.BlockSpec
import com.bedrockmodstudio.core.bedrock.BlockStyle
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.ColorRow
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.ItemField
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

@Composable
fun BlockWizardScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var projectItems by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            meta = runCatching { app.store.load(id) }.getOrNull()
            projectItems = runCatching { ProjectIndex.usableItems(app.store.files(id)) }.getOrDefault(emptyList())
        }
    }
    val (ctl, generate) = rememberGenerator(id, "Assistant bloc")

    var name by remember { mutableStateOf("") }
    var shortId by remember { mutableStateOf("") }
    var idEdited by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(BlockStyle.STONE) }
    var main by remember { mutableStateOf(PALETTE[5]) }
    var accent by remember { mutableStateOf(PALETTE[12]) }
    var hardness by remember { mutableStateOf(1.5f) }
    var resistance by remember { mutableStateOf(6f) }
    var light by remember { mutableStateOf(0f) }
    var sound by remember { mutableStateOf(BlockSound.STONE) }
    var dropOther by remember { mutableStateOf(false) }
    var dropItem by remember { mutableStateOf("minecraft:diamond") }
    var dropMin by remember { mutableStateOf(1f) }
    var dropMax by remember { mutableStateOf(1f) }

    val effectiveId = if (idEdited) shortId else Identifiers.slug(name).replace('.', '_')
    val spec = BlockSpec(
        displayName = name.trim(), shortId = effectiveId, style = style, mainColor = main, accentColor = accent,
        hardness = Math.round(hardness * 10) / 10.0, explosionResistance = Math.round(resistance * 10) / 10.0,
        lightEmission = light.toInt(), sound = sound,
        dropItem = if (dropOther) dropItem else null, dropMin = dropMin.toInt(), dropMax = maxOf(dropMin.toInt(), dropMax.toInt()),
    )
    val errors = spec.validate()
    val texture = remember(style, main, accent, effectiveId) { runCatching { BlockGenerator.texture(spec) }.getOrNull() }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.block_title), subtitle = meta?.name, onBack = { nav.popBackStack() }) }) { padding ->
        val m = meta
        if (m == null || ctl.busy) { LoadingBox(); return@Scaffold }
        val profile = BedrockVersions.byId(m.targetVersion) ?: BedrockVersions.DEFAULT
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GenerationResult(ctl, stringResource(R.string.block_test_hint, "${m.namespace}:$effectiveId"))

            OutlinedTextField(name, { name = it; ctl.written = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.block_name)) }, singleLine = true)
            OutlinedTextField(effectiveId, { shortId = it.lowercase(); idEdited = true }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.mob_id)) }, singleLine = true)
            HelpText(stringResource(R.string.mob_id_help, m.namespace, effectiveId))

            SectionTitle(stringResource(R.string.block_style))
            ChoiceChips(BlockStyle.entries, style, { it.labelFr }) { s ->
                style = s
                if (s == BlockStyle.LAMP && light == 0f) light = 15f
                if (s == BlockStyle.GLASS) { sound = BlockSound.GLASS; hardness = 0.3f; resistance = 0.3f }
                if (s == BlockStyle.PLANKS) sound = BlockSound.WOOD
            }
            SectionTitle(stringResource(R.string.mob_body_color))
            ColorRow(main) { main = it }
            SectionTitle(stringResource(R.string.item_accent_color))
            ColorRow(accent) { accent = it }
            SectionTitle(stringResource(R.string.block_preview))
            PngPreview(texture, 128.dp)

            LabeledSlider(stringResource(R.string.block_hardness, String.format(Locale.FRANCE, "%.1f", hardness)), stringResource(R.string.block_hardness_help), hardness, 0f..50f) { hardness = it }
            LabeledSlider(stringResource(R.string.block_resistance, String.format(Locale.FRANCE, "%.0f", resistance)), stringResource(R.string.block_resistance_help), resistance, 0f..1200f) { resistance = it }
            LabeledSlider(stringResource(R.string.block_light, light.toInt()), stringResource(R.string.block_light_help), light, 0f..15f) { light = it }
            SectionTitle(stringResource(R.string.block_sound))
            ChoiceChips(BlockSound.entries, sound, { it.labelFr }) { sound = it }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.block_drop_other)); HelpText(stringResource(R.string.block_drop_other_help)) }
                Switch(dropOther, { dropOther = it })
            }
            if (dropOther) {
                ItemField(stringResource(R.string.block_drop_item), dropItem, { dropItem = it }, projectItems)
                LabeledSlider(stringResource(R.string.mob_loot_amount, dropMin.toInt(), maxOf(dropMin.toInt(), dropMax.toInt())), null, dropMin, 0f..16f) { dropMin = it }
                androidx.compose.material3.Slider(value = dropMax, onValueChange = { dropMax = it }, valueRange = 0f..16f)
            }

            if (errors.isNotEmpty() && name.isNotBlank()) InfoCard(errors.joinToString("\n"), color = BmsColors.Error)
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = { generate(BlockGenerator.generate(m.namespace, spec, profile)) },
            ) { Text(stringResource(R.string.block_generate)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}
