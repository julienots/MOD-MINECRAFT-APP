package com.bedrockmodstudio.ui.model

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.model3d.AnimationCodec
import com.bedrockmodstudio.core.model3d.Channel
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
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
import java.io.File
import java.util.Locale

private fun f(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')

@Composable
fun ModelEditorScreen(nav: NavHostController, id: String, path: String) {
    val context = LocalContext.current
    val app = context.container
    val vm: ModelEditorViewModel = viewModel(key = "geo/$id/$path") { ModelEditorViewModel(app.store.files(id), path, app.errorLog) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val camera = remember { OrbitCamera() }
    var framed by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var askLeave by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val savedMsg = stringResource(R.string.editor_saved)

    BackHandler(enabled = vm.dirty) { askLeave = true }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(vm.model) { val m = vm.model; if (m != null && !framed) { camera.frame(m); framed = true } }
    // Au retour de l'éditeur de textures, la vue 3D montre aussitôt la texture modifiée.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.reloadTexture() }
    val use = if (path.contains("/models/blocks/")) com.bedrockmodstudio.core.model3d.ModelUse.BLOCK else com.bedrockmodstudio.core.model3d.ModelUse.CREATURE
    val (bindCtl, startBind) = com.bedrockmodstudio.ui.common.rememberGenerator(id, "Associer un modèle 3D")
    val targets by androidx.compose.runtime.produceState(emptyList<com.bedrockmodstudio.core.model3d.ModelBinding.Target>(), bindCtl.written) {
        value = withContext(Dispatchers.IO) { runCatching { com.bedrockmodstudio.core.model3d.ModelBinding.targets(app.store.files(id), use) }.getOrDefault(emptyList()) }
    }
    fun bind(t: com.bedrockmodstudio.core.model3d.ModelBinding.Target) {
        val m = vm.model ?: return
        val tex = vm.texturePath?.removePrefix("resource_pack/")?.removeSuffix(".png")
        if (tex == null) { scope.launch { snackbar.showSnackbar(context.getString(R.string.model_bind_no_texture)) }; return }
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val files = app.store.files(id)
                    if (use == com.bedrockmodstudio.core.model3d.ModelUse.BLOCK) com.bedrockmodstudio.core.model3d.ModelBinding.bindBlock(files, t, m, tex)
                    else com.bedrockmodstudio.core.model3d.ModelBinding.bindCreature(files, t, m.identifier, tex)
                }
            }
            r.onSuccess { startBind(it) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }
    fun save(leave: Boolean = false) = vm.save { ok -> scope.launch(Dispatchers.Main.immediate) { if (ok) { snackbar.showSnackbar(savedMsg); if (leave) nav.popBackStack() } } }

    Scaffold(topBar = {
        BmsTopBar(path.substringAfterLast('/') + if (vm.dirty) " •" else "", subtitle = vm.model?.identifier,
            onBack = { if (vm.dirty) askLeave = true else nav.popBackStack() },
            actions = {
                IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                IconButton(onClick = { save() }, enabled = vm.dirty) { Icon(Icons.Filled.Save, stringResource(R.string.save)) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_reframe)) }, onClick = { menu = false; vm.model?.let(camera::frame) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.model_export_obj)) }, onClick = {
                        menu = false
                        val file = vm.exportObj(File(context.cacheDir, "exports"))
                        if (file != null) {
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                }
            })
    }) { padding ->
        val m = vm.model
        when {
            vm.error != null -> Column(Modifier.padding(padding).padding(16.dp)) { InfoCard(vm.error!!, color = BmsColors.Error) }
            m == null -> LoadingBox()
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                ModelViewport(m, vm.poses, vm.texture, vm.selBone, vm.selCube, camera, vm.lighting, vm.lightAngle,
                    Modifier.fillMaxWidth().weight(1f)) { b, c -> vm.select(b, c); if (tab == 0) tab = 1 }
                TabRow(selectedTabIndex = tab) {
                    listOf(R.string.model_tab_structure, R.string.model_tab_properties, R.string.model_tab_texture, R.string.model_tab_animation)
                        .forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) }) }
                }
                Column(Modifier.fillMaxWidth().weight(1.1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    when (tab) {
                        0 -> StructureTab(vm)
                        1 -> PropertiesTab(vm)
                        2 -> {
                            TextureTab(vm) { p -> nav.navigate(Routes.texture(id, p)) }
                            BindSection(use, targets, vm.dirty, ::bind)
                            com.bedrockmodstudio.ui.common.GenerationResult(bindCtl, stringResource(R.string.model_bind_hint))
                        }
                        else -> AnimationTab(vm)
                    }
                }
            }
        }
    }
    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_body)) },
        confirmButton = { TextButton(onClick = { askLeave = false; save(leave = true) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { askLeave = false; nav.popBackStack() }) { Text(stringResource(R.string.editor_discard), color = BmsColors.Error) }
                TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun StructureTab(vm: ModelEditorViewModel) {
    val m = vm.model ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = vm::addBone) { Text(stringResource(R.string.model_add_bone)) }
        OutlinedButton(onClick = vm::addCube) { Text(stringResource(R.string.model_add_cube)) }
        OutlinedButton(onClick = { vm.duplicateSelected(false) }, enabled = vm.selBone != null) { Text(stringResource(R.string.duplicate)) }
        OutlinedButton(onClick = { vm.duplicateSelected(true) }, enabled = vm.selBone != null) { Text(stringResource(R.string.model_mirror)) }
        OutlinedButton(onClick = { confirmDelete = true }, enabled = vm.selBone != null) { Text(stringResource(R.string.delete), color = BmsColors.Error) }
    }
    HelpText(stringResource(R.string.model_structure_help))
    fun depth(name: String?): Int { var d = 0; var p = name; while (p != null && d < 32) { p = m.bone(p)?.parent; d++ }; return d }
    m.bones.forEachIndexed { bi, b ->
        val indent = (depth(b.parent) * 16).dp
        val selected = vm.selBone == bi && vm.selCube == null
        Text("🦴 ${b.name}", Modifier.fillMaxWidth().clickable { vm.select(bi, null) }
            .background(if (selected) BmsColors.Secondary.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(start = indent + 4.dp, top = 6.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyLarge)
        b.cubes.forEachIndexed { ci, c ->
            val cs = vm.selBone == bi && vm.selCube == ci
            Text("▢ Cube ${ci + 1}  (${f(c.size.x)}×${f(c.size.y)}×${f(c.size.z)})", Modifier.fillMaxWidth().clickable { vm.select(bi, ci) }
                .background(if (cs) BmsColors.Secondary.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                .padding(start = indent + 28.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.model_delete_title),
        stringResource(if (vm.selCube != null) R.string.model_delete_cube else R.string.model_delete_bone),
        confirmLabel = stringResource(R.string.delete), destructive = true,
        onConfirm = { confirmDelete = false; vm.deleteSelected() }, onDismiss = { confirmDelete = false })
}

/** Champ numérique avec boutons − et + (pas réglable). */
@Composable
private fun Stepper(label: String, value: Double, step: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(f(value)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onChange(value - step) }) { Text("−") }
        OutlinedTextField(text, { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onChange) }, Modifier.width(92.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        TextButton(onClick = { onChange(value + step) }) { Text("+") }
    }
}

@Composable
private fun VecEditor(title: String, v: Vec3, step: Double, onChange: (Vec3) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Stepper("X", v.x, step) { onChange(v.copy(x = it)) }
    Stepper("Y", v.y, step) { onChange(v.copy(y = it)) }
    Stepper("Z", v.z, step) { onChange(v.copy(z = it)) }
}

@Composable
private fun PropertiesTab(vm: ModelEditorViewModel) {
    val m = vm.model ?: return
    val bi = vm.selBone
    var step by remember { mutableStateOf(1.0) }
    if (bi == null || bi !in m.bones.indices) { HelpText(stringResource(R.string.model_select_hint)); return }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.model_step))
        listOf(1.0, 0.5, 0.1).forEach { s -> FilterChip(selected = step == s, onClick = { step = s }, label = { Text(f(s)) }) }
    }
    val b = m.bones[bi]
    val ci = vm.selCube
    if (ci == null || ci !in b.cubes.indices) {
        var name by remember(bi, b.name) { mutableStateOf(b.name) }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.model_bone_name)) }, singleLine = true,
            supportingText = {
                val clash = name != b.name && m.bone(name) != null
                Text(if (clash) stringResource(R.string.model_name_taken) else stringResource(R.string.model_name_help))
            })
        TextButton(enabled = name.isNotBlank() && name != b.name && m.bone(name) == null, onClick = { vm.updateBone { it.copy(name = name.trim()) } }) {
            Text(stringResource(R.string.rename))
        }
        Text(stringResource(R.string.model_parent), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = b.parent == null, onClick = { vm.updateBone { it.copy(parent = null) } }, label = { Text(stringResource(R.string.model_no_parent)) })
            m.bones.filter { it.name != b.name }.forEach { p ->
                FilterChip(selected = b.parent == p.name, onClick = { vm.updateBone { it.copy(parent = p.name) } }, label = { Text(p.name) })
            }
        }
        VecEditor(stringResource(R.string.model_pivot), b.pivot, step) { v -> vm.updateBone { it.copy(pivot = v) } }
        VecEditor(stringResource(R.string.model_rotation), b.rotation, step * 15) { v -> vm.updateBone { it.copy(rotation = v) } }
        m.validate().takeIf { it.isNotEmpty() }?.let { InfoCard(it.joinToString("\n"), color = BmsColors.Warning) }
    } else {
        val c = b.cubes[ci]
        Text(stringResource(R.string.model_cube_of, ci + 1, b.name), style = MaterialTheme.typography.titleMedium)
        VecEditor(stringResource(R.string.model_origin), c.origin, step) { v -> vm.updateCube { it.copy(origin = v) } }
        VecEditor(stringResource(R.string.model_size), c.size, step) { v -> vm.updateCube { it.copy(size = Vec3(maxOf(0.0, v.x), maxOf(0.0, v.y), maxOf(0.0, v.z))) } }
        Text(stringResource(R.string.model_uv), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        if (c.faceUv != null) HelpText(stringResource(R.string.model_face_uv))
        else {
            Stepper("U", c.uv.first, 1.0) { u -> vm.updateCube { it.copy(uv = u to it.uv.second) } }
            Stepper("V", c.uv.second, 1.0) { v -> vm.updateCube { it.copy(uv = it.uv.first to v) } }
            HelpText(stringResource(R.string.model_uv_help, f(c.size.z * 2 + c.size.x * 2), f(c.size.z + c.size.y)))
        }
        Stepper("±", c.inflate, step / 2) { v -> vm.updateCube { it.copy(inflate = v) } }
        HelpText(stringResource(R.string.model_inflate_help))
        VecEditor(stringResource(R.string.model_cube_rotation), c.rotation ?: Vec3.ZERO, step * 15) { v ->
            vm.updateCube { it.copy(rotation = if (v == Vec3.ZERO) null else v, pivot = it.pivot ?: (it.origin + it.size * 0.5)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.model_cube_mirror), Modifier.weight(1f)); Switch(c.mirror, { v -> vm.updateCube { it.copy(mirror = v) } })
        }
    }
}

@Composable
private fun BindSection(
    use: com.bedrockmodstudio.core.model3d.ModelUse, targets: List<com.bedrockmodstudio.core.model3d.ModelBinding.Target>, dirty: Boolean,
    onBind: (com.bedrockmodstudio.core.model3d.ModelBinding.Target) -> Unit,
) {
    SectionTitle(stringResource(if (use == com.bedrockmodstudio.core.model3d.ModelUse.BLOCK) R.string.model_bind_block else R.string.model_bind_creature))
    HelpText(stringResource(if (use == com.bedrockmodstudio.core.model3d.ModelUse.BLOCK) R.string.model_bind_block_help else R.string.model_bind_creature_help))
    if (dirty) InfoCard(stringResource(R.string.model_bind_save_first), color = BmsColors.Warning)
    else if (targets.isEmpty()) HelpText(stringResource(R.string.model_bind_none))
    else targets.forEach { t ->
        OutlinedButton(onClick = { onBind(t) }, modifier = Modifier.fillMaxWidth()) { Text(t.id) }
    }
}

@Composable
private fun TextureTab(vm: ModelEditorViewModel, onEditTexture: (String) -> Unit) {
    val m = vm.model ?: return
    SectionTitle(stringResource(R.string.model_texture_used))
    Text(vm.texturePath ?: stringResource(R.string.model_no_texture), color = if (vm.texturePath == null) BmsColors.Warning else MaterialTheme.colorScheme.onSurface)
    vm.texture?.let { t ->
        if (t.width != m.textureWidth || t.height != m.textureHeight) InfoCard(stringResource(R.string.model_texture_size_mismatch, t.width, t.height, m.textureWidth, m.textureHeight), color = BmsColors.Warning)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.texturePath?.let { p -> OutlinedButton(onClick = { onEditTexture(p) }) { Text(stringResource(R.string.tex_edit)) } }
        OutlinedButton(onClick = vm::reloadTexture, enabled = vm.texturePath != null) { Text(stringResource(R.string.model_reload_texture)) }
    }
    Text(stringResource(R.string.model_pick_texture), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    vm.textures.forEach { p ->
        Text(p, Modifier.fillMaxWidth().clickable { vm.loadTexture(p) }.padding(vertical = 6.dp),
            color = if (p == vm.texturePath) BmsColors.Primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    SectionTitle(stringResource(R.string.model_texture_size))
    Stepper("L", m.textureWidth.toDouble(), 16.0) { v -> vm.updateModel { it.copy(textureWidth = v.toInt().coerceIn(1, 4096)) } }
    Stepper("H", m.textureHeight.toDouble(), 16.0) { v -> vm.updateModel { it.copy(textureHeight = v.toInt().coerceIn(1, 4096)) } }
    SectionTitle(stringResource(R.string.model_lighting))
    Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.model_shading), Modifier.weight(1f)); Switch(vm.lighting, { vm.lighting = it }) }
    Text(stringResource(R.string.model_light_angle, vm.lightAngle.toInt()))
    Slider(vm.lightAngle, { vm.lightAngle = it }, valueRange = 0f..360f)
}

@Composable
private fun AnimationTab(vm: ModelEditorViewModel) {
    val m = vm.model ?: return
    var newDialog by remember { mutableStateOf(false) }
    HelpText(stringResource(R.string.anim_file, vm.animPath))
    val list = vm.anims?.animations ?: emptyList()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = vm.animIndex == null, onClick = { vm.animIndex = null; vm.time = 0.0 }, label = { Text(stringResource(R.string.anim_none)) })
        list.forEachIndexed { i, a -> FilterChip(selected = vm.animIndex == i, onClick = { vm.animIndex = i; vm.time = 0.0 }, label = { Text(a.name.substringAfterLast('.')) }) }
        AssistChip(onClick = { newDialog = true }, label = { Text(stringResource(R.string.anim_new)) })
    }
    val a = vm.animation
    if (a == null) { HelpText(stringResource(R.string.anim_help)); if (newDialog) NewAnimDialog(vm) { newDialog = false }; return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::togglePlay) { Icon(if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.anim_play)) }
        Text(stringResource(R.string.anim_time, f(Math.round(vm.time * 100) / 100.0), f(a.length)))
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.anim_loop)); Switch(a.loop, vm::setLoop)
    }
    Slider(vm.time.toFloat(), { vm.time = it.toDouble() }, valueRange = 0f..a.length.toFloat().coerceAtLeast(0.05f))
    Stepper("⏱", a.length, 0.25) { vm.setLength(it) }
    HelpText(stringResource(R.string.anim_length_help))
    a.bones.values.flatMap { it.raw.keys }.takeIf { it.isNotEmpty() }?.let { InfoCard(stringResource(R.string.anim_molang_kept)) }

    val bi = vm.selBone
    if (bi == null || bi !in m.bones.indices) { HelpText(stringResource(R.string.anim_select_bone)); if (newDialog) NewAnimDialog(vm) { newDialog = false }; return }
    val bone = m.bones[bi].name
    SectionTitle(stringResource(R.string.anim_bone, bone))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Channel.entries.forEach { c -> FilterChip(selected = vm.channel == c, onClick = { vm.channel = c }, label = { Text(c.labelFr) }) }
    }
    val pose = vm.poses[bone]
    val current = when (vm.channel) { Channel.ROTATION -> pose?.rotation ?: Vec3.ZERO; Channel.POSITION -> pose?.position ?: Vec3.ZERO; Channel.SCALE -> pose?.scale ?: Vec3(1.0, 1.0, 1.0) }
    var value by remember(bone, vm.channel, vm.time, vm.animIndex) { mutableStateOf(current) }
    VecEditor(stringResource(R.string.anim_value), value, if (vm.channel == Channel.ROTATION) 15.0 else if (vm.channel == Channel.SCALE) 0.1 else 1.0) { value = it }
    OutlinedButton(onClick = { vm.keyframe(value) }, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.anim_add_keyframe)) }
    val kfs = a.bones[bone]?.keyframes?.get(vm.channel) ?: emptyList()
    if (kfs.isEmpty()) HelpText(stringResource(R.string.anim_no_keyframe))
    kfs.forEach { k ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${f(k.time)} s : [${f(k.value.x)}, ${f(k.value.y)}, ${f(k.value.z)}]", Modifier.weight(1f).clickable { vm.time = k.time })
            TextButton(onClick = { vm.deleteKeyframe(k.time) }) { Text(stringResource(R.string.delete)) }
        }
    }
    if (newDialog) NewAnimDialog(vm) { newDialog = false }
}

/** Panneau de création d'animation, affiché dans l'onglet (pas de fenêtre superposée). */
@Composable
private fun NewAnimDialog(vm: ModelEditorViewModel, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf("walk") }
    var name by remember { mutableStateOf("marche") }
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.anim_new), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_name)) }, singleLine = true)
            Text(stringResource(R.string.anim_template), Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimationCodec.TEMPLATES.forEach { (k, label) -> FilterChip(selected = kind == k, onClick = { kind = k; name = label.lowercase(Locale.FRANCE) }, label = { Text(label) }) }
            }
            HelpText(stringResource(R.string.anim_template_help))
            Row {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.newAnimation(kind, name); onDismiss() }) { Text(stringResource(R.string.anim_create)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
