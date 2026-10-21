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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.BossPhase
import com.bedrockmodstudio.core.bedrock.BossSpec
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.bedrock.MobTemperament
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ColorRow
import com.bedrockmodstudio.ui.common.LabeledSlider
import com.bedrockmodstudio.ui.common.PALETTE
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
    var isBoss by remember { mutableStateOf(false) }
    var bossBar by remember { mutableStateOf(true) }
    var darkSky by remember { mutableStateOf(false) }
    var immuneFall by remember { mutableStateOf(true) }
    var immuneFire by remember { mutableStateOf(false) }
    var immuneProj by remember { mutableStateOf(false) }
    var noKnockback by remember { mutableStateOf(true) }
    var rareLoot by remember { mutableStateOf("") }
    var rareChance by remember { mutableStateOf(0.25f) }
    val phases = remember { androidx.compose.runtime.mutableStateListOf<BossPhase>() }
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
        boss = if (!isBoss) null else BossSpec(
            bossBar = bossBar, darkenSky = darkSky, phases = phases.toList(), immuneFall = immuneFall, immuneFire = immuneFire,
            immuneProjectiles = immuneProj, knockbackResistant = noKnockback, rareLoot = rareLoot.ifBlank { null },
            rareChance = Math.round(rareChance * 100) / 100.0,
        ),
    )
    val errors = spec.validate()
    val preview = remember(effectiveId, body, accent) {
        runCatching { MobGenerator.texture(spec).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }

    fun apply(content: GeneratedContent, overwrite: Boolean) {
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching { ContentInstaller.install(app.store, id, content, overwrite, "Assistant créature").also { app.store.touch(id) } }
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

            // ---- Boss ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.boss_toggle), style = MaterialTheme.typography.titleMedium)
                    HelpText(stringResource(R.string.boss_toggle_help))
                }
                Switch(isBoss, { isBoss = it; if (it && health < 100f) health = 200f },
                    modifier = Modifier.testTag("boss_switch"))
            }
            if (isBoss) {
                BossOption(stringResource(R.string.boss_bar), bossBar) { bossBar = it }
                BossOption(stringResource(R.string.boss_dark_sky), darkSky) { darkSky = it }
                BossOption(stringResource(R.string.boss_no_knockback), noKnockback) { noKnockback = it }
                BossOption(stringResource(R.string.boss_immune_fall), immuneFall) { immuneFall = it }
                BossOption(stringResource(R.string.boss_immune_fire), immuneFire) { immuneFire = it }
                BossOption(stringResource(R.string.boss_immune_projectiles), immuneProj) { immuneProj = it }

                SectionTitle(stringResource(R.string.boss_phases))
                HelpText(stringResource(R.string.boss_phases_help))
                phases.forEachIndexed { i, p ->
                    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.boss_phase_n, i + 2), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = { phases.removeAt(i) }) { Text(stringResource(R.string.delete)) }
                            }
                            LabeledSlider(stringResource(R.string.boss_phase_threshold, p.healthPercent), null, p.healthPercent.toFloat(), 1f..99f) {
                                phases[i] = p.copy(healthPercent = it.toInt())
                            }
                            if (temperament != MobTemperament.PASSIVE) LabeledSlider(stringResource(R.string.mob_damage, p.attackDamage, fmt(p.attackDamage / 2.0)), null,
                                p.attackDamage.toFloat(), 0f..60f) { phases[i] = p.copy(attackDamage = it.toInt()) }
                            LabeledSlider(stringResource(R.string.mob_speed, fmt(p.speed)), null, p.speed.toFloat(), 0.05f..0.8f) {
                                phases[i] = p.copy(speed = Math.round(it * 100) / 100.0)
                            }
                            androidx.compose.material3.OutlinedTextField(p.message, { phases[i] = p.copy(message = it) }, Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.boss_phase_message)) })
                        }
                    }
                }
                if (phases.size < 4) androidx.compose.material3.OutlinedButton(onClick = {
                    val last = phases.lastOrNull()
                    val threshold = ((last?.healthPercent ?: 100) * 0.6).toInt().coerceAtLeast(1)
                    phases.add(BossPhase(threshold, ((last?.attackDamage ?: damage.toInt()) * 1.5).toInt(), ((last?.speed ?: speed.toDouble()) + 0.05).coerceAtMost(0.8)))
                }) { Text(stringResource(R.string.boss_add_phase)) }

                SectionTitle(stringResource(R.string.boss_rare_loot))
                androidx.compose.material3.OutlinedTextField(rareLoot, { rareLoot = it.trim().lowercase() }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.boss_rare_loot_item)) }, placeholder = { Text("minecraft:nether_star") }, singleLine = true)
                if (rareLoot.isNotBlank()) LabeledSlider(stringResource(R.string.boss_rare_chance, (rareChance * 100).toInt()), null, rareChance, 0.01f..1f) { rareChance = it }
            }

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
private fun BossOption(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f)); Switch(value, onChange)
    }
}
