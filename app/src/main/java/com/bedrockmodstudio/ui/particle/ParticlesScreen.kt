package com.bedrockmodstudio.ui.particle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.particle.EmitDirection
import com.bedrockmodstudio.core.particle.EmitterShape
import com.bedrockmodstudio.core.particle.ParticleCodec
import com.bedrockmodstudio.core.particle.ParticleGenerator
import com.bedrockmodstudio.core.particle.ParticlePreset
import com.bedrockmodstudio.core.particle.ParticleSimulator
import com.bedrockmodstudio.core.particle.ParticleSpec
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.ActionTile
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.ColorRow
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LabeledSlider
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ParticlesScreen(nav: NavHostController, id: String, path: String) {
    if (path.isBlank()) ParticleList(nav, id) else ParticleEditor(nav, id, path)
}

@Composable
private fun ParticleList(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    var name by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(ParticlePreset.SPARKS) }
    val (ctl, start) = rememberGenerator(id, "Nouvel effet de particules")
    val list by produceState<List<String>?>(null, ctl.written) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.store.files(id).walk("resource_pack/particles").filter { it.endsWith(".json") } }.getOrDefault(emptyList())
        }
    }
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.particles_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(stringResource(R.string.particles_intro))
            SectionTitle(stringResource(R.string.particles_new))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("particle_name"), label = { Text(stringResource(R.string.particles_name)) }, singleLine = true)
            ChoiceChips(ParticlePreset.entries, preset, { it.labelFr }) { preset = it }
            val shortId = Identifiers.slug(name).replace('.', '_').take(40)
            var ns by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { ns = withContext(Dispatchers.IO) { runCatching { app.store.load(id).namespace }.getOrDefault("") } }
            Button(onClick = { start(ParticleGenerator.generate(ns, shortId, preset)) }, enabled = shortId.isNotEmpty() && ns.isNotEmpty() && !ctl.busy,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.particles_create)) }
            GenerationResult(ctl, stringResource(R.string.particles_test_hint, "$ns:$shortId"))
            SectionTitle(stringResource(R.string.particles_existing))
            val l = list
            if (l != null && l.isEmpty()) EmptyState(stringResource(R.string.particles_empty))
            l?.forEach { p -> ActionTile(Icons.Filled.AutoAwesome, p.substringAfterLast('/'), p, Modifier.fillMaxWidth()) { nav.navigate(Routes.particle(id, p)) } }
        }
    }
}

@Composable
private fun ParticleEditor(nav: NavHostController, id: String, path: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var spec by remember { mutableStateOf<ParticleSpec?>(null) }
    var unsupported by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        val r = withContext(Dispatchers.IO) { runCatching { ParticleCodec.read(app.store.files(id).readText(path)) } }
        r.onSuccess { res -> spec = res.spec; unsupported = res.unsupported; if (res.spec == null) error = res.unsupported.joinToString(" ") }
            .onFailure { error = it.message }
    }
    val readOnly = unsupported.isNotEmpty()
    fun update(s: ParticleSpec) { spec = s; dirty = true }
    fun save() {
        val s = spec ?: return
        val errors = s.validate()
        if (errors.isNotEmpty()) { scope.launch { snackbar.showSnackbar(errors.joinToString(" ")) }; return }
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) { runCatching { app.store.files(id).writeText(path, ParticleCodec.toJson(s), "Éditeur de particules"); app.store.touch(id) } }
            r.onSuccess { dirty = false; snackbar.showSnackbar(context.getString(R.string.editor_saved)) }
                .onFailure { app.errorLog.log("Particules", it); snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }
    Scaffold(topBar = {
        BmsTopBar(path.substringAfterLast('/') + if (dirty) " •" else "", subtitle = spec?.identifier, onBack = { nav.popBackStack() }) {
            if (!readOnly) IconButton(onClick = ::save, enabled = spec != null && dirty, modifier = Modifier.testTag("particle_save")) {
                Icon(Icons.Filled.Save, stringResource(R.string.save))
            }
        }
    }) { padding ->
        val s = spec
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { InfoCard(it, color = BmsColors.Error) }
            if (s != null) ParticleControls(s, readOnly, unsupported, onOpenText = { nav.navigate(Routes.edit(id, path)) }, update = ::update)
        }
    }
}

@Composable
private fun ParticleControls(s: ParticleSpec, readOnly: Boolean, unsupported: List<String>, onOpenText: () -> Unit, update: (ParticleSpec) -> Unit) {
    ParticlePreview(s)
    HelpText(stringResource(R.string.particles_preview_note))
    if (readOnly) {
        InfoCard(stringResource(R.string.particles_read_only, unsupported.joinToString(", ")), color = BmsColors.Warning)
        OutlinedButton(onClick = onOpenText) { Text(stringResource(R.string.particles_open_text)) }
        return
    }
    SectionTitle(stringResource(R.string.particles_emission))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.particles_burst), Modifier.weight(1f))
        Switch(s.burst, { update(s.copy(burst = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.particles_looping), Modifier.weight(1f))
        Switch(s.looping, { update(s.copy(looping = it)) })
    }
    LabeledSlider(stringResource(if (s.burst) R.string.particles_count else R.string.particles_rate, s.rate.toInt()), null, s.rate.toFloat(), 1f..200f) { update(s.copy(rate = it.toInt().toDouble())) }
    LabeledSlider(stringResource(R.string.particles_active, fmt(s.activeTime)), null, s.activeTime.toFloat(), 0.1f..10f) { update(s.copy(activeTime = round1(it))) }
    SectionTitle(stringResource(R.string.particles_shape))
    ChoiceChips(EmitterShape.entries, s.shape, { it.labelFr }) { update(s.copy(shape = it)) }
    if (s.shape != EmitterShape.POINT) LabeledSlider(stringResource(R.string.particles_radius, fmt(s.radius)), null, s.radius.toFloat(), 0f..4f) { update(s.copy(radius = round1(it))) }
    ChoiceChips(EmitDirection.entries, s.direction, { it.labelFr }) { update(s.copy(direction = it)) }
    SectionTitle(stringResource(R.string.particles_motion))
    LabeledSlider(stringResource(R.string.particles_speed, fmt(s.speed)), null, s.speed.toFloat(), 0f..10f) { update(s.copy(speed = round1(it))) }
    LabeledSlider(stringResource(R.string.particles_lifetime, fmt(s.lifetime)), null, s.lifetime.toFloat(), 0.1f..5f) { update(s.copy(lifetime = round1(it))) }
    LabeledSlider(stringResource(R.string.particles_gravity, fmt(s.gravity)), stringResource(R.string.particles_gravity_help), s.gravity.toFloat(), -5f..15f) { update(s.copy(gravity = round1(it))) }
    LabeledSlider(stringResource(R.string.particles_drag, fmt(s.drag)), null, s.drag.toFloat(), 0f..10f) { update(s.copy(drag = round1(it))) }
    SectionTitle(stringResource(R.string.particles_look))
    LabeledSlider(stringResource(R.string.particles_size, fmt(s.size)), null, s.size.toFloat(), 0.02f..1f) { update(s.copy(size = Math.round(it * 100) / 100.0)) }
    Text(stringResource(R.string.particles_color_start))
    ColorRow(s.colorStart or (0xFF shl 24)) { update(s.copy(colorStart = it)) }
    Text(stringResource(R.string.particles_color_end))
    ColorRow(s.colorEnd or (0xFF shl 24)) { update(s.copy(colorEnd = it and 0x00FFFFFF)) }
    HelpText(stringResource(R.string.particles_color_help))
}

private fun round1(f: Float) = Math.round(f * 10) / 10.0
private fun fmt(d: Double) = String.format(java.util.Locale.FRANCE, "%.1f", d)

/** Aperçu animé : simulation approchée, vue de trois quarts, 1 bloc = 1/6 de la largeur. */
@Composable
private fun ParticlePreview(spec: ParticleSpec) {
    var restarts by remember { mutableIntStateOf(0) }
    val sim = remember(spec, restarts) { ParticleSimulator(spec) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sim) {
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) sim.step(((now - last) / 1e9).coerceAtMost(0.1))
                last = now
                frame = now
            }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF15181D)).testTag("particle_preview")) {
        @Suppress("UNUSED_EXPRESSION") frame
        val scale = size.width / 6f
        val yaw = Math.toRadians(30.0); val pitch = Math.toRadians(15.0)
        fun proj(x: Double, y: Double, z: Double): Offset {
            val x1 = x * cos(yaw) - z * sin(yaw); val z1 = x * sin(yaw) + z * cos(yaw)
            val y2 = y * cos(pitch) - z1 * sin(pitch)
            return Offset(size.width / 2 + (x1 * scale).toFloat(), size.height * 0.62f - (y2 * scale).toFloat())
        }
        // Repère : carré d'un bloc au sol sous l'émetteur.
        val corners = listOf(proj(-0.5, 0.0, -0.5), proj(0.5, 0.0, -0.5), proj(0.5, 0.0, 0.5), proj(-0.5, 0.0, 0.5))
        for (i in 0..3) drawLine(Color(0x55FFFFFF), corners[i], corners[(i + 1) % 4], 2f)
        for (p in sim.particles) {
            drawCircle(Color(sim.colorOf(p)), radius = (spec.size * scale / 2).toFloat().coerceAtLeast(2f), center = proj(p.pos.x, p.pos.y, p.pos.z))
        }
    }
    OutlinedButton(onClick = { restarts++ }) { Text(stringResource(R.string.particles_replay)) }
}
