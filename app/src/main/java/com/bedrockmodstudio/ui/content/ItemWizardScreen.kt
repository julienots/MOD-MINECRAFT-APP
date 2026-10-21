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
import com.bedrockmodstudio.core.bedrock.ItemGenerator
import com.bedrockmodstudio.core.bedrock.ItemKind
import com.bedrockmodstudio.core.bedrock.ItemSpec
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.bedrock.RecipeGenerator
import com.bedrockmodstudio.core.bedrock.ToolTier
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

private fun fmt(d: Double) = String.format(Locale.FRANCE, "%.1f", d)

/** Valeurs de départ réalistes (proches du vanilla) quand on change de type d'objet. */
private fun defaultsFor(kind: ItemKind): Triple<Int, Int, Int> = when (kind) { // dégâts, durabilité, protection
    ItemKind.SWORD -> Triple(6, 500, 0)
    ItemKind.PICKAXE -> Triple(4, 500, 0)
    ItemKind.AXE -> Triple(5, 500, 0)
    ItemKind.SHOVEL -> Triple(3, 500, 0)
    ItemKind.HELMET -> Triple(0, 220, 2)
    ItemKind.CHESTPLATE -> Triple(0, 320, 6)
    ItemKind.LEGGINGS -> Triple(0, 300, 5)
    ItemKind.BOOTS -> Triple(0, 260, 2)
    else -> Triple(0, 1, 0)
}

@Composable
fun ItemWizardScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var projectItems by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            meta = runCatching { app.store.load(id) }.getOrNull()
            projectItems = runCatching { ProjectIndex.usableItems(app.store.files(id)) }.getOrDefault(emptyList())
        }
    }
    val (ctl, generate) = rememberGenerator(id, "Assistant objet")

    var kind by remember { mutableStateOf(ItemKind.SWORD) }
    var name by remember { mutableStateOf("") }
    var shortId by remember { mutableStateOf("") }
    var idEdited by remember { mutableStateOf(false) }
    var main by remember { mutableStateOf(PALETTE[8]) }
    var accent by remember { mutableStateOf(PALETTE[2]) }
    var stack by remember { mutableStateOf(64f) }
    var damage by remember { mutableStateOf(6f) }
    var durability by remember { mutableStateOf(500f) }
    var tier by remember { mutableStateOf(ToolTier.IRON) }
    var protection by remember { mutableStateOf(3f) }
    var nutrition by remember { mutableStateOf(4f) }
    var saturation by remember { mutableStateOf(0.6f) }
    var alwaysEat by remember { mutableStateOf(false) }
    var enchant by remember { mutableStateOf(10f) }
    var repair by remember { mutableStateOf("") }
    var glint by remember { mutableStateOf(false) }
    var withRecipe by remember { mutableStateOf(false) }
    var material by remember { mutableStateOf("minecraft:diamond") }

    val effectiveId = if (idEdited) shortId else Identifiers.slug(name).replace('.', '_')
    val spec = ItemSpec(
        displayName = name.trim(), shortId = effectiveId, kind = kind, mainColor = main, accentColor = accent,
        maxStack = stack.toInt(), damage = damage.toInt(), durability = durability.toInt(), tier = tier,
        protection = protection.toInt(), nutrition = nutrition.toInt(), saturation = Math.round(saturation * 10) / 10.0,
        canAlwaysEat = alwaysEat, enchantability = enchant.toInt(), repairItem = repair.ifBlank { null }, glint = glint,
    )
    val recipeAvailable = kind.isWeaponOrTool || kind.isArmor
    val errors = spec.validate().toMutableList().apply {
        if (withRecipe && recipeAvailable && !Identifiers.isValidIdentifier(material)) add("Matériau de la recette invalide.")
    }
    val icon = remember(kind, main, accent) { runCatching { ItemGenerator.icon(spec) }.getOrNull() }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.item_title), subtitle = meta?.name, onBack = { nav.popBackStack() }) }) { padding ->
        val m = meta
        if (m == null || ctl.busy) { LoadingBox(); return@Scaffold }
        val profile = BedrockVersions.byId(m.targetVersion) ?: BedrockVersions.DEFAULT
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GenerationResult(ctl, stringResource(R.string.item_test_hint, "${m.namespace}:$effectiveId"))

            SectionTitle(stringResource(R.string.item_kind))
            ChoiceChips(ItemKind.entries, kind, { it.labelFr }) { k ->
                kind = k; ctl.written = null
                val (d, dur, prot) = defaultsFor(k)
                damage = d.toFloat(); durability = dur.toFloat(); protection = prot.toFloat()
            }
            HelpText(kind.explanationFr)

            OutlinedTextField(name, { name = it; ctl.written = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.item_name)) }, singleLine = true)
            OutlinedTextField(effectiveId, { shortId = it.lowercase(); idEdited = true }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(R.string.mob_id)) }, singleLine = true)
            HelpText(stringResource(R.string.mob_id_help, m.namespace, effectiveId))

            SectionTitle(stringResource(R.string.mob_body_color))
            ColorRow(main) { main = it }
            SectionTitle(stringResource(R.string.item_accent_color))
            ColorRow(accent) { accent = it }
            SectionTitle(stringResource(R.string.item_icon_preview))
            PngPreview(icon, 128.dp)

            when {
                kind == ItemKind.SIMPLE ->
                    LabeledSlider(stringResource(R.string.item_stack, stack.toInt()), null, stack, 1f..64f) { stack = it }
                kind == ItemKind.FOOD -> {
                    LabeledSlider(stringResource(R.string.item_stack, stack.toInt()), null, stack, 1f..64f) { stack = it }
                    LabeledSlider(stringResource(R.string.item_nutrition, nutrition.toInt()), stringResource(R.string.item_nutrition_help), nutrition, 1f..20f) { nutrition = it }
                    LabeledSlider(stringResource(R.string.item_saturation, fmt(saturation.toDouble())), stringResource(R.string.item_saturation_help), saturation, 0f..2f) { saturation = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.item_always_eat), Modifier.weight(1f)); Switch(alwaysEat, { alwaysEat = it })
                    }
                }
            }
            if (kind.isWeaponOrTool) {
                LabeledSlider(stringResource(R.string.item_damage, damage.toInt()), stringResource(R.string.item_damage_help), damage, 0f..30f) { damage = it }
                SectionTitle(stringResource(R.string.item_tier))
                ChoiceChips(ToolTier.entries, tier, { it.labelFr }) { tier = it }
                HelpText(stringResource(R.string.item_tier_help))
            }
            if (kind.isArmor) {
                LabeledSlider(stringResource(R.string.item_protection, protection.toInt()), stringResource(R.string.item_protection_help), protection, 0f..20f) { protection = it }
            }
            if (kind.hasDurability) {
                LabeledSlider(stringResource(R.string.item_durability, durability.toInt()), stringResource(R.string.item_durability_help), durability, 1f..5000f) { durability = it }
                LabeledSlider(stringResource(R.string.item_enchant, enchant.toInt()), stringResource(R.string.item_enchant_help), enchant, 0f..30f) { enchant = it }
                ItemField(stringResource(R.string.item_repair), repair, { repair = it }, projectItems, Modifier.padding(top = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) { Text(stringResource(R.string.item_glint)); HelpText(stringResource(R.string.item_glint_help)) }
                Switch(glint, { glint = it })
            }
            if (recipeAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.item_with_recipe)); HelpText(stringResource(R.string.item_with_recipe_help)) }
                    Switch(withRecipe, { withRecipe = it })
                }
                if (withRecipe) ItemField(stringResource(R.string.item_material), material, { material = it }, projectItems)
            }

            if (errors.isNotEmpty() && name.isNotBlank()) InfoCard(errors.joinToString("\n"), color = BmsColors.Error)
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = errors.isEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = {
                    val content = ItemGenerator.generate(m.namespace, spec, profile)
                    if (withRecipe && recipeAvailable) {
                        RecipeGenerator.standardFor(kind, material, "${m.namespace}:${spec.shortId}", spec.shortId)
                            ?.let { content.addAll(RecipeGenerator.generate(m.namespace, it, profile)) }
                    }
                    generate(content)
                },
            ) { Text(stringResource(R.string.item_generate)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}
