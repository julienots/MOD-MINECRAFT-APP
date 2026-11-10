package com.bedrockmodstudio.ui.model

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.GeoModel
import com.bedrockmodstudio.core.model3d.HeldItem
import com.bedrockmodstudio.core.model3d.HoldPreset
import com.bedrockmodstudio.core.model3d.HoldTransform
import com.bedrockmodstudio.core.model3d.HoldView
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class HeldModel(val path: String, val model: GeoModel, val texture: String?, val bitmap: Bitmap?)

/** Couleurs unies du personnage de démonstration (peau, vêtements). */
private fun mannequinColor(boneName: String) = when (boneName) {
    "head", "rightArm", "leftArm" -> 0xFFC69C6D.toInt()
    "body" -> 0xFF2A9D8F.toInt()
    else -> 0xFF3C3C46.toInt()
}

private fun cameraFor(view: HoldView, cam: OrbitCamera) {
    when (view) {
        HoldView.THIRD_PERSON -> { cam.yaw = 25f; cam.pitch = 12f; cam.target = Vec3(-4.0, 18.0, -2.0); cam.distance = 58f }
        HoldView.FIRST_PERSON -> { cam.yaw = 180f; cam.pitch = 0f; cam.distance = 20f; cam.target = HeldItem.EYE + Vec3(0.0, 0.0, -20.0) }
    }
}

/**
 * Placement d'un objet 3D dans la main, réglé à l'œil : le personnage tient l'objet, on le tourne, le déplace
 * (au doigt ou avec les curseurs) et on le grandit, séparément pour la vue à la 1re et à la 3e personne.
 */
@Composable
fun HeldItemScreen(nav: NavHostController, id: String, initialPath: String) {
    val app = LocalContext.current.container
    val files = remember(id) { app.store.files(id) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var modelPath by remember { mutableStateOf(initialPath) }
    var itemId by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(HoldView.THIRD_PERSON) }
    var fp by remember { mutableStateOf(HoldPreset.SWORD.firstPerson) }
    var tp by remember { mutableStateOf(HoldPreset.SWORD.thirdPerson) }
    var dragMode by remember { mutableStateOf(true) }
    val camera = remember { OrbitCamera().also { cameraFor(HoldView.THIRD_PERSON, it) } }
    val (ctl, start) = rememberGenerator(id, "Objet tenu en main")

    val items by produceState<List<HeldItem.ItemRef>?>(null, ctl.written) { value = withContext(Dispatchers.IO) { runCatching { HeldItem.items(files) }.getOrDefault(emptyList()) } }
    val models by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) { runCatching { files.walk("$RP/models").filter { it.endsWith(".json") && !it.contains("/models/blocks/") } }.getOrDefault(emptyList()) }
    }
    val held by produceState<Result<HeldModel>?>(null, modelPath, ctl.written) {
        value = if (modelPath.isEmpty()) null else withContext(Dispatchers.IO) {
            runCatching {
                val model = GeoCodec.parse(files.readText(modelPath)).models.firstOrNull() ?: error("Modèle vide")
                val tex = HeldItem.textureFor(files, model.identifier)
                    ?: "textures/entity/" + modelPath.substringAfterLast('/').substringBefore('.')
                HeldModel(modelPath, model, tex, loadBitmap(files, "$RP/$tex.png"))
            }
        }
    }
    // Réglages déjà enregistrés pour cet objet : on reprend là où on s'était arrêté.
    LaunchedEffect(itemId) {
        val idv = itemId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { HeldItem.read(files, idv) }.getOrNull() }?.let { (a, b) -> fp = a; tp = b }
    }

    val current = if (view == HoldView.FIRST_PERSON) fp else tp
    fun set(t: HoldTransform) { val c = t.clamped(); if (view == HoldView.FIRST_PERSON) fp = c else tp = c }

    Scaffold(topBar = { BmsTopBar(stringResource(R.string.held_title), subtitle = itemId, onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val hm = held?.getOrNull()
            if (hm != null) {
                val faces = remember(hm, view, current) {
                    val (body, item) = HeldItem.preview(hm.model, view, current)
                    body.map { it.copy(boneIndex = -100 - it.boneIndex) } + item
                }
                MeshViewport(
                    faces, hm.bitmap, hm.model.textureWidth, hm.model.textureHeight, camera, lighting = true, lightAngle = 45f,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    faceColor = { f: MeshFace -> if (f.boneIndex <= -100) mannequinColor(HeldItem.mannequin.bones[-100 - f.boneIndex].name) else null },
                    onDragObject = if (dragMode) ({ d: Vec3 -> set(current.copy(position = current.position + HeldItem.worldToHand(view, d))) }) else null,
                ) { }
            }
            Column(Modifier.fillMaxWidth().weight(if (hm != null) 1.1f else 1f).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hm == null) InfoCard(stringResource(R.string.held_intro))
                held?.exceptionOrNull()?.let { InfoCard(it.message ?: "Erreur", color = BmsColors.Error) }

                SectionTitle(stringResource(R.string.held_step_model))
                if (models.isEmpty()) {
                    EmptyState(stringResource(R.string.held_no_model))
                    OutlinedButton(onClick = { nav.navigate(Routes.models(id)) }) { Text(stringResource(R.string.held_create_model)) }
                } else ChoiceChips(models, modelPath, { it.substringAfterLast('/').substringBefore('.') }) { modelPath = it }

                SectionTitle(stringResource(R.string.held_step_item))
                val list = items
                if (list != null && list.isEmpty()) {
                    EmptyState(stringResource(R.string.held_no_item))
                    OutlinedButton(onClick = { nav.navigate(Routes.item(id)) }) { Text(stringResource(R.string.held_create_item)) }
                } else if (list != null) ChoiceChips(list.map { it.id }, itemId ?: "", { it.substringAfter(':') }) { itemId = it }

                if (hm != null) {
                    SectionTitle(stringResource(R.string.held_step_place))
                    ChoiceChips(HoldView.entries, view, { it.labelFr }) { view = it; cameraFor(it, camera) }
                    HelpText(stringResource(if (view == HoldView.FIRST_PERSON) R.string.held_view_first else R.string.held_view_third))
                    Text(stringResource(R.string.held_presets))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HoldPreset.entries.forEach { p ->
                            OutlinedButton(onClick = { fp = p.firstPerson; tp = p.thirdPerson }) { Text(p.labelFr) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.held_drag_mode), Modifier.weight(1f))
                        Switch(dragMode, { dragMode = it }, modifier = Modifier.testTag("held_drag"))
                    }
                    HelpText(stringResource(if (dragMode) R.string.held_drag_on else R.string.held_drag_off))
                    Text(stringResource(R.string.held_rotation), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    AxisSlider("↕ X", current.rotation.x, -180.0..180.0, 5.0, "°") { set(current.copy(rotation = current.rotation.copy(x = it))) }
                    AxisSlider("↔ Y", current.rotation.y, -180.0..180.0, 5.0, "°") { set(current.copy(rotation = current.rotation.copy(y = it))) }
                    AxisSlider("⟳ Z", current.rotation.z, -180.0..180.0, 5.0, "°") { set(current.copy(rotation = current.rotation.copy(z = it))) }
                    Text(stringResource(R.string.held_position), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    AxisSlider("X", current.position.x, -24.0..24.0, 0.5, " px") { set(current.copy(position = current.position.copy(x = it))) }
                    AxisSlider("Y", current.position.y, -24.0..24.0, 0.5, " px") { set(current.copy(position = current.position.copy(y = it))) }
                    AxisSlider("Z", current.position.z, -24.0..24.0, 0.5, " px") { set(current.copy(position = current.position.copy(z = it))) }
                    AxisSlider(stringResource(R.string.held_size), current.scale, 0.1..3.0, 0.05, "×") { set(current.copy(scale = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { set(HoldTransform()) }) { Text(stringResource(R.string.held_reset)) }
                        OutlinedButton(onClick = { if (view == HoldView.FIRST_PERSON) tp = fp else fp = tp }) { Text(stringResource(R.string.held_copy_other)) }
                    }
                    if (hm.bitmap == null) InfoCard(stringResource(R.string.held_no_texture, hm.texture ?: "?"), color = BmsColors.Warning)
                    InfoCard(stringResource(R.string.held_approx))
                    Button(
                        onClick = {
                            val idv = itemId ?: return@Button
                            scope.launch(Dispatchers.Main.immediate) {
                                withContext(Dispatchers.IO) {
                                    runCatching { HeldItem.generate(files, HeldItem.Spec(idv, hm.path, hm.texture ?: "", fp, tp)) }
                                }.onSuccess(start).onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                            }
                        },
                        enabled = itemId != null && !ctl.busy,
                        modifier = Modifier.fillMaxWidth().testTag("held_save"),
                    ) { Text(stringResource(if (itemId == null) R.string.held_pick_item_first else R.string.held_save)) }
                    GenerationResult(ctl, stringResource(R.string.held_test_hint))
                }
            }
        }
    }
}
