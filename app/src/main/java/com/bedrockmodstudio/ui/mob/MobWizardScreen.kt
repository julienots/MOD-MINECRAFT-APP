package com.bedrockmodstudio.ui.mob

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.bedrock.MobTemperament
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ConfirmDialog
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val PALETTE = listOf(
    0xFF4E8A3E, 0xFF2F5A23, 0xFF6B4F3A, 0xFF8B5A2B, 0xFFB0B0B0, 0xFF3C3C46, 0xFF1F2A44, 0xFF2E6FA8,
    0xFF7A2E8E, 0xFFB03A2E, 0xFFE0A030, 0xFFE8E4D8, 0xFF2A9D8F, 0xFF101014,
).map { it.toInt() }

/** Objets vanilla courants pour le butin (identifiants Bedrock). */
private val LOOT_CHOICES = listOf(
    null to "Aucun", "minecraft:leather" to "Cuir", "minecraft:porkchop" to "Côtelette de porc", "minecraft:beef" to "Bœuf cru",
    "minecraft:bone" to "Os", "minecraft:rotten_flesh" to "Chair putréfiée", "minecraft:feather" to "Plume",
    "minecraft:string" to "Ficelle", "minecraft:gunpowder" to "Poudre à canon", "minecraft:slime_ball" to "Boule de slime",
    "minecraft:iron_ingot" to "Lingot de fer", "minecraft:gold_ingot" to "Lingot d'or", "minecraft:emerald" to "Émeraude",
    "minecraft:diamond" to "Diamant",
)

private fun fmt(d: Double) = String.format(Locale.FRANCE, "%.2f", d)

@Composable
fun MobWizardScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    LaunchedEffect(id) { meta = withContext(Dispatchers.IO) { runCatching { app.store.load(id) }.getOrNull() } }

    var name by remember { mutableStateOf("") }
    var shortId by remember { mutableStateOf("") }
    var idEdited by remember { mutableStateOf(false) }
    var temperament by remember { mutableStateOf(MobTemperament.NEUTRAL) }
    var health by remember { mutableStateOf(20f) }
    var speed by remember { mutableStateOf(0.25f) }
    var damage by remember { mutableStateOf(3f) }
    var follow by remember { mutableStateOf(16f) }
    var scale by remember { mutableStateOf(1f) }
    var body by remember { mutableStateOf(PALETTE[0]) }
    var accent by remember { mutableStateOf(PALETTE[1]) }
    var xp by remember { mutableStateOf(5f) }
    var loot by remember { mutableStateOf<String?>("minecraft:leather") }
    var lootMin by remember { mutableStateOf(0f) }
    var lootMax by remember { mutableStateOf(2f) }
    var spawn by remember { mutableStateOf(false) }
    var weight by remember { mutableStateOf(8f) }
    var pending by remember { mutableStateOf<Pair<GeneratedContent, List<String>>?>(null) }
    var written by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val effectiveId = if (idEdited) shortId else Identifiers.slug(name).replace('.', '_')
    val spec = MobSpec(
        displayName = name.trim(), shortId = effectiveId, temperament = temperament, health = health.toInt(),
        speed = Math.round(speed * 100) / 100.0, attackDamage = damage.toInt(), followRange = follow.toInt(),
        scale = Math.round(scale * 10) / 10.0, bodyColor = body, accentColor = accent, xpOnDeath = xp.toInt(),
        lootItem = loot, lootMin = lootMin.toInt(), lootMax = maxOf(lootMin.toInt(), lootMax.toInt()),
        spawnNaturally = spawn, spawnWeight = weight.toInt(),
    )
    val errors = spec.validate()
    val preview = remember(effectiveId, body, accent) {
        runCatching { MobGenerator.texture(spec).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }

    fun apply(content: GeneratedContent, overwrite: Boolean) {
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching { app.store.files(id).apply(content, overwrite, "Assistant créature").also { app.store.touch(id) } }
            }
            busy = false
            r.onSuccess { written = it.written; snackbar.showSnackbar(context.getString(R.string.mob_created, it.written.size)) }
                .onFailure { app.errorLog.log("Assistant créature", it); snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.mob_title), subtitle = meta?.name, onBack = { nav.popBackStack() }) }) { padding ->
        val m = meta
        if (m == null || busy) { LoadingBox(); return@Scaffold }
        val profile = BedrockVersions.byId(m.targetVersion) ?: BedrockVersions.DEFAULT
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            written?.let { files ->
                InfoCard(stringResource(R.string.mob_files_written) + "\n" + files.joinToString("\n") { "• $it" } +
                    "\n\n" + stringResource(R.string.mob_test_hint, "${m.namespace}:$effectiveId"), color = BmsColors.Primary)
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(name, { name = it; written = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.mob_name)) }, singleLine = true)
            HelpText(stringResource(R.string.mob_name_help))
            OutlinedTextField(effectiveId, { shortId = it.lowercase(); idEdited = true }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mob_id)) }, singleLine = true)
            HelpText(stringResource(R.string.mob_id_help, m.namespace, effectiveId))

            SectionTitle(stringResource(R.string.mob_temperament))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobTemperament.entries.forEach { t ->
                    FilterChip(selected = temperament == t, onClick = { temperament = t }, label = { Text(t.labelFr) })
                }
            }
            HelpText(temperament.explanationFr)

            LabeledSlider(stringResource(R.string.mob_health, health.toInt(), fmt(health / 2.0)), stringResource(R.string.mob_health_help), health, 1f..300f) { health = it }
            LabeledSlider(stringResource(R.string.mob_speed, fmt(speed.toDouble())), stringResource(R.string.mob_speed_help), speed, 0.05f..0.6f) { speed = it }
            if (temperament != MobTemperament.PASSIVE) {
                LabeledSlider(stringResource(R.string.mob_damage, damage.toInt(), fmt(damage / 2.0)), stringResource(R.string.mob_damage_help), damage, 0f..40f) { damage = it }
            }
            if (temperament == MobTemperament.HOSTILE) {
                LabeledSlider(stringResource(R.string.mob_follow, follow.toInt()), stringResource(R.string.mob_follow_help), follow, 4f..64f) { follow = it }
            }
            LabeledSlider(stringResource(R.string.mob_scale, fmt(scale.toDouble())), stringResource(R.string.mob_scale_help), scale, 0.3f..4f) { scale = it }

            SectionTitle(stringResource(R.string.mob_body_color))
            ColorRow(body) { body = it }
            SectionTitle(stringResource(R.string.mob_accent_color))
            ColorRow(accent) { accent = it }
            if (preview != null) {
                SectionTitle(stringResource(R.string.mob_preview))
                Image(preview.asImageBitmap(), null, filterQuality = FilterQuality.None,
                    modifier = Modifier.size(192.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }

            LabeledSlider(stringResource(R.string.mob_xp, xp.toInt()), null, xp, 0f..100f) { xp = it }
            SectionTitle(stringResource(R.string.mob_loot))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOOT_CHOICES.forEach { (item, label) -> FilterChip(selected = loot == item, onClick = { loot = item }, label = { Text(label) }) }
            }
            if (loot != null) {
                LabeledSlider(stringResource(R.string.mob_loot_amount, lootMin.toInt(), maxOf(lootMin.toInt(), lootMax.toInt())), null, lootMin, 0f..16f) { lootMin = it }
                Slider(value = lootMax, onValueChange = { lootMax = it }, valueRange = 0f..16f)
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mob_spawn))
                    HelpText(stringResource(R.string.mob_spawn_help))
                }
                Switch(spawn, { spawn = it })
            }
            if (spawn) LabeledSlider(stringResource(R.string.mob_spawn_weight, weight.toInt()), null, weight, 1f..100f) { weight = it }

            if (errors.isNotEmpty() && name.isNotBlank()) InfoCard(errors.joinToString("\n"), color = BmsColors.Error)
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = errors.isEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = {
                    scope.launch(Dispatchers.Main.immediate) {
                        val content = MobGenerator.generate(m.namespace, spec, profile)
                        val conflicts = withContext(Dispatchers.IO) { app.store.files(id).conflicts(content) }
                        if (conflicts.isEmpty()) apply(content, overwrite = false) else pending = content to conflicts
                    }
                },
            ) { Text(stringResource(R.string.mob_generate)) }
            Spacer(Modifier.height(32.dp))
        }
    }

    pending?.let { (content, conflicts) ->
        ConfirmDialog(
            stringResource(R.string.mob_overwrite_title),
            stringResource(R.string.mob_overwrite_body, conflicts.joinToString("\n") { "• $it" }),
            confirmLabel = stringResource(R.string.mob_overwrite_action), destructive = true,
            onConfirm = { pending = null; apply(content, overwrite = true) },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, help: String?, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, Modifier.padding(top = 12.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range)
    if (help != null) HelpText(help)
}

@Composable
private fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
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
