package com.bedrockmodstudio.ui.structure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.arr
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.nbt.Nbt
import com.bedrockmodstudio.core.structure.BlockState
import com.bedrockmodstudio.core.structure.NaturalSpawn
import com.bedrockmodstudio.core.structure.StructureCodec
import com.bedrockmodstudio.core.structure.StructureGenerator
import com.bedrockmodstudio.core.structure.StructureMesh
import com.bedrockmodstudio.core.structure.StructurePreset
import com.bedrockmodstudio.core.structure.StructureSpec
import com.bedrockmodstudio.core.structure.StructureTemplate
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.ActionTile
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.ChoiceChips
import com.bedrockmodstudio.ui.common.EmptyState
import com.bedrockmodstudio.ui.common.GenerationResult
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LabeledSlider
import com.bedrockmodstudio.ui.common.SectionTitle
import com.bedrockmodstudio.ui.common.rememberGenerator
import com.bedrockmodstudio.ui.model.MeshViewport
import com.bedrockmodstudio.ui.model.OrbitCamera
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Blocs vanilla proposés dans la palette (identifiants Bedrock aplatis, sans état obligatoire). */
private val COMMON_BLOCKS = listOf(
    StructurePreset.PLANKS to "Planches", StructurePreset.LOG to "Rondin", StructurePreset.COBBLE to "Pierre taillée",
    StructurePreset.MOSSY to "Pierre moussue", StructurePreset.BRICKS to "Pierre taillée en briques", BlockState("minecraft:stone") to "Pierre",
    StructurePreset.GLASS to "Verre", BlockState("minecraft:dirt") to "Terre", BlockState("minecraft:grass_block") to "Herbe",
    BlockState("minecraft:sand") to "Sable", BlockState("minecraft:obsidian") to "Obsidienne", StructurePreset.CRYING to "Obsidienne pleureuse",
    StructurePreset.LANTERN to "Lanterne marine", BlockState("minecraft:glowstone") to "Pierre lumineuse", BlockState("minecraft:white_wool") to "Laine blanche",
    BlockState("minecraft:gold_block") to "Bloc d'or",
)

private val BIOME_TAGS = listOf(
    "plains" to "Plaines", "forest" to "Forêts", "desert" to "Déserts", "taiga" to "Taïgas", "jungle" to "Jungles",
    "savanna" to "Savanes", "swamp" to "Marais", "mountains" to "Montagnes", "mesa" to "Mesas", "ocean" to "Océans",
)

/** Contenus de coffre proposés (objets vanilla). */
private val LOOTS = listOf(
    "Trésor" to listOf("minecraft:diamond" to 3, "minecraft:gold_ingot" to 8, "minecraft:emerald" to 5, "minecraft:iron_ingot" to 10),
    "Provisions" to listOf("minecraft:bread" to 6, "minecraft:apple" to 5, "minecraft:cooked_beef" to 4, "minecraft:torch" to 16),
    "Aventurier" to listOf("minecraft:iron_sword" to 1, "minecraft:bow" to 1, "minecraft:arrow" to 24, "minecraft:golden_apple" to 2),
)

/** Outil de pinceau : un bloc, « vide » (garde le terrain existant) ou un coffre à butin. */
private sealed class Brush {
    data class Block(val state: BlockState) : Brush()
    data object Void : Brush()
    data object Chest : Brush()
}

private class EditorState(var template: StructureTemplate) {
    var version by mutableIntStateOf(0)
    var layer by mutableIntStateOf(0)
    var brush by mutableStateOf<Brush>(Brush.Block(StructurePreset.PLANKS))
    var dirty by mutableStateOf(false)
    fun changed() { version++; dirty = true }
}

@Composable
fun StructureEditorScreen(nav: NavHostController, id: String, path: String) {
    if (path.isBlank()) StructureListScreen(nav, id) else StructureEditor(nav, id, path)
}

@Composable
private fun StructureListScreen(nav: NavHostController, id: String) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var name by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(StructurePreset.HOUSE) }
    var refresh by remember { mutableIntStateOf(0) }
    val list by produceState<List<String>?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.store.files(id).walk("behavior_pack/structures").filter { it.endsWith(".mcstructure") } }.getOrDefault(emptyList())
        }
    }
    Scaffold(topBar = { BmsTopBar(stringResource(R.string.structures_title), onBack = { nav.popBackStack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(stringResource(R.string.structures_intro))
            SectionTitle(stringResource(R.string.structures_new))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("structure_name"), label = { Text(stringResource(R.string.structures_name)) }, singleLine = true)
            ChoiceChips(StructurePreset.entries, preset, { it.labelFr }) { preset = it }
            HelpText(preset.explanationFr)
            val shortId = Identifiers.slug(name).replace('.', '_').take(40)
            Button(
                onClick = {
                    scope.launch(Dispatchers.Main.immediate) {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val ns = app.store.load(id).namespace
                                val files = app.store.files(id)
                                val p = StructureGenerator.structurePath(ns, shortId)
                                if (files.exists(p)) throw IllegalStateException("La structure « $shortId » existe déjà.")
                                files.write(p, StructureCodec.write(preset.build()), "Nouvelle structure")
                                app.store.touch(id)
                                p
                            }
                        }
                        r.onSuccess { nav.navigate(Routes.structure(id, it)) }.onFailure { snackbar.showSnackbar(it.message ?: "Erreur") }
                        refresh++
                    }
                },
                enabled = shortId.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("structure_create"),
            ) { Text(stringResource(R.string.structures_create)) }
            SectionTitle(stringResource(R.string.structures_existing))
            val l = list
            if (l != null && l.isEmpty()) EmptyState(stringResource(R.string.structures_empty))
            l?.forEach { p ->
                ActionTile(Icons.Filled.ViewInAr, StructureGenerator.idForPath(p) ?: p, p, Modifier.fillMaxWidth()) { nav.navigate(Routes.structure(id, p)) }
            }
        }
    }
}

@Composable
private fun StructureEditor(nav: NavHostController, id: String, path: String) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var state by remember { mutableStateOf<EditorState?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var namespace by remember { mutableStateOf("") }
    var projectBlocks by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(path) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                namespace = app.store.load(id).namespace
                projectBlocks = ProjectIndex.scan(app.store.files(id)).filter { it.kind == ProjectIndex.Kind.BLOCK }.map { it.id }
                StructureCodec.read(app.store.files(id).readBytes(path))
            }
        }
        r.onSuccess { state = EditorState(it) }.onFailure { loadError = it.message ?: "Structure illisible." }
    }
    val structureId = StructureGenerator.idForPath(path) ?: path
    val shortId = path.substringAfterLast('/').removeSuffix(".mcstructure")
    var loot by remember { mutableStateOf<Int?>(null) }

    fun save() {
        val st = state ?: return
        scope.launch(Dispatchers.Main.immediate) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val files = app.store.files(id)
                    var kept = false
                    val chosen = loot?.let { LOOTS[it] }
                    if (chosen != null) {
                        val lootPath = StructureGenerator.lootPath(namespace, shortId)
                        for (i in st.template.primary.indices) {
                            if (st.template.palette.getOrNull(st.template.primary[i])?.name != "minecraft:chest") continue
                            val x = i / (st.template.sizeY * st.template.sizeZ); val y = (i / st.template.sizeZ) % st.template.sizeY; val z = i % st.template.sizeZ
                            st.template.setChest(x, y, z, lootPath, "south")
                        }
                        // Une table existante (peut-être modifiée à la main) n'est jamais remplacée.
                        if (files.exists("behavior_pack/$lootPath")) kept = true
                        else files.writeText("behavior_pack/$lootPath", JsonWriter.write(obj("pools" to arr(obj(
                            "rolls" to obj("min" to 2, "max" to 5),
                            "entries" to chosen.second.map { (item, n) ->
                                obj("type" to "item", "name" to item, "weight" to 10, "functions" to arr(obj("function" to "set_count", "count" to obj("min" to 1, "max" to n))))
                            },
                        )))), "Table de butin de structure")
                    }
                    files.write(path, StructureCodec.write(st.template), "Éditeur de structures")
                    app.store.touch(id)
                    kept
                }
            }
            r.onSuccess { kept ->
                st.dirty = false
                snackbar.showSnackbar(context.getString(if (kept) R.string.structure_saved_loot_kept else R.string.editor_saved))
            }.onFailure { app.errorLog.log("Structure", it); snackbar.showSnackbar(it.message ?: "Erreur") }
        }
    }

    Scaffold(topBar = {
        BmsTopBar(structureId, subtitle = state?.let { "${it.template.sizeX} × ${it.template.sizeY} × ${it.template.sizeZ}" + if (it.dirty) " • " + stringResource(R.string.structure_unsaved) else "" },
            onBack = { nav.popBackStack() }) {
            IconButton(onClick = ::save, enabled = state != null, modifier = Modifier.testTag("structure_save")) { Icon(Icons.Filled.Save, stringResource(R.string.save)) }
        }
    }) { padding ->
        val st = state
        if (st == null) {
            Column(Modifier.padding(padding).padding(16.dp)) { loadError?.let { InfoCard(it, color = BmsColors.Error) } }
            return@Scaffold
        }
        var tab by remember { mutableIntStateOf(0) }
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf(R.string.structure_tab_layers, R.string.structure_tab_3d, R.string.structure_tab_generation)
                    .forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) }) }
            }
            when (tab) {
                0 -> LayersTab(st, projectBlocks)
                1 -> PreviewTab(st)
                else -> GenerationTab(id, namespace, shortId, st, loot) { loot = it }
            }
        }
    }
}

@Composable
private fun LayersTab(st: EditorState, projectBlocks: List<String>) {
    @Suppress("UNUSED_EXPRESSION") st.version // relecture à chaque modification
    val t = st.template
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (st.layer > 0) st.layer-- }, modifier = Modifier.testTag("layer_down")) { Text("−") }
            Text(stringResource(R.string.structure_layer, st.layer, t.sizeY - 1), Modifier.weight(1f).padding(horizontal = 8.dp))
            OutlinedButton(onClick = { if (st.layer < t.sizeY - 1) st.layer++ }, modifier = Modifier.testTag("layer_up")) { Text("+") }
        }
        HelpText(stringResource(R.string.structure_grid_help))
        LayerGrid(st)
        SectionTitle(stringResource(R.string.structure_palette))
        BrushPalette(st, projectBlocks)
        SectionTitle(stringResource(R.string.structure_tools))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { t.fill(0, st.layer, 0, t.sizeX - 1, st.layer, t.sizeZ - 1, (st.brush as? Brush.Block)?.state); st.changed() }) {
                Text(stringResource(R.string.structure_fill_layer))
            }
            OutlinedButton(onClick = { t.fill(0, st.layer, 0, t.sizeX - 1, st.layer, t.sizeZ - 1, null); st.changed() }) { Text(stringResource(R.string.structure_clear_layer)) }
            OutlinedButton(onClick = {
                if (st.layer > 0) { for (x in 0 until t.sizeX) for (z in 0 until t.sizeZ) t.primary[t.index(x, st.layer, z)] = t.primary[t.index(x, st.layer - 1, z)]; st.changed() }
            }) { Text(stringResource(R.string.structure_copy_below)) }
        }
        SectionTitle(stringResource(R.string.structure_size))
        SizeEditor(st)
        Text(stringResource(R.string.structure_count, t.blockCount(), t.palette.size), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LayerGrid(st: EditorState) {
    val t = st.template
    val version = st.version
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    fun paint(x: Int, z: Int) {
        when (val b = st.brush) {
            is Brush.Block -> t.set(x, st.layer, z, b.state)
            Brush.Void -> t.set(x, st.layer, z, null)
            Brush.Chest -> t.setChest(x, st.layer, z, null)
        }
        st.changed()
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(t.sizeX.toFloat() / t.sizeZ).background(surface).testTag("structure_grid")
            .pointerInput(t, st.layer) {
                detectTapGestures { o ->
                    val x = (o.x / size.width * t.sizeX).toInt().coerceIn(0, t.sizeX - 1)
                    val z = (o.y / size.height * t.sizeZ).toInt().coerceIn(0, t.sizeZ - 1)
                    paint(x, z)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") version
            val cw = size.width / t.sizeX; val ch = size.height / t.sizeZ
            for (x in 0 until t.sizeX) for (z in 0 until t.sizeZ) {
                val here = t[x, st.layer, z]
                val below = if (st.layer > 0) t[x, st.layer - 1, z] else null
                val tl = Offset(x * cw, z * ch); val sz = Size(cw, ch)
                when {
                    here != null && here.name != StructureTemplate.AIR -> drawRect(Color(StructureMesh.colorOf(here.name)), tl, sz)
                    here != null -> drawRect(Color(0x33FFFFFF), tl, sz) // air : efface le terrain
                    below != null && below.name != StructureTemplate.AIR -> drawRect(Color(StructureMesh.colorOf(below.name)).copy(alpha = 0.25f), tl, sz)
                }
                if (here?.name == "minecraft:chest") drawRect(Color(0xFFFFD166), Offset(tl.x + cw * 0.3f, tl.y + ch * 0.3f), Size(cw * 0.4f, ch * 0.4f))
                drawRect(Color(0x30000000), tl, sz, style = Stroke(1f))
            }
        }
    }
    Text(stringResource(R.string.structure_north_up), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BrushPalette(st: EditorState, projectBlocks: List<String>) {
    var custom by remember { mutableStateOf("") }
    val options = buildList {
        add(Brush.Void to stringResource(R.string.structure_brush_void))
        add(Brush.Block(BlockState(StructureTemplate.AIR)) to stringResource(R.string.structure_brush_air))
        add(Brush.Chest to stringResource(R.string.structure_brush_chest))
        projectBlocks.forEach { add(Brush.Block(BlockState(it)) to it.substringAfter(':')) }
        COMMON_BLOCKS.forEach { (b, l) -> add(Brush.Block(b) to l) }
        st.template.palette.filter { p -> p.name != StructureTemplate.AIR && p.name != "minecraft:chest" && COMMON_BLOCKS.none { it.first == p } && p.name !in projectBlocks }
            .forEach { add(Brush.Block(it) to it.name.substringAfter(':')) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (b, label) ->
            FilterChip(selected = st.brush == b, onClick = { st.brush = b }, label = { Text(label) }, leadingIcon = {
                val c = when (b) { is Brush.Block -> StructureMesh.colorOf(b.state.name); Brush.Chest -> 0xFFA0702A.toInt(); Brush.Void -> 0 }
                Box(Modifier.size(14.dp).background(Color(c)).border(1.dp, MaterialTheme.colorScheme.outline))
            })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(custom, { custom = it.trim().lowercase() }, Modifier.weight(1f), label = { Text(stringResource(R.string.structure_custom_block)) },
            singleLine = true, placeholder = { Text("minecraft:bookshelf") })
        Spacer(Modifier.width(8.dp))
        val valid = Identifiers.isValidIdentifier(custom) || (custom.startsWith("minecraft:") && Identifiers.isValidName(custom.removePrefix("minecraft:")))
        Button(onClick = { st.brush = Brush.Block(BlockState(custom)) }, enabled = valid) { Text(stringResource(R.string.structure_use_block)) }
    }
    HelpText(stringResource(R.string.structure_states_help))
}

@Composable
private fun SizeEditor(st: EditorState) {
    val t = st.template
    @Suppress("UNUSED_EXPRESSION") st.version
    fun resize(nx: Int, ny: Int, nz: Int) {
        if (nx !in 1..StructureTemplate.MAX_SIZE || ny !in 1..StructureTemplate.MAX_HEIGHT || nz !in 1..StructureTemplate.MAX_SIZE) return
        st.template = t.resized(nx, ny, nz)
        st.layer = st.layer.coerceAtMost(ny - 1)
        st.changed()
    }
    for ((label, value, set) in listOf<Triple<String, Int, (Int) -> Unit>>(
        Triple("X (est)", t.sizeX) { resize(it, t.sizeY, t.sizeZ) },
        Triple("Y (hauteur)", t.sizeY) { resize(t.sizeX, it, t.sizeZ) },
        Triple("Z (sud)", t.sizeZ) { resize(t.sizeX, t.sizeY, it) },
    )) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label : $value", Modifier.weight(1f))
            OutlinedButton(onClick = { set(value - 1) }) { Text("−") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { set(value + 1) }) { Text("+") }
        }
    }
    HelpText(stringResource(R.string.structure_size_help))
}

@Composable
private fun PreviewTab(st: EditorState) {
    val version = st.version
    val mesh = remember(version, st.template) { StructureMesh.build(st.template) }
    val camera = remember { OrbitCamera() }
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(mesh) { if (!framed) { camera.frame(mesh.faces); framed = true } }
    var picked by remember { mutableStateOf<String?>(null) }
    var light by remember { mutableFloatStateOf(45f) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val palette = st.template.palette
        val colors = remember(mesh) { IntArray(mesh.blockIndex.size) { i -> StructureMesh.colorOf(palette.getOrNull(st.template.primary[mesh.blockIndex[i]])?.name ?: "") } }
        MeshViewport(
            mesh.faces, null, 16, 16, camera, lighting = true, lightAngle = light,
            modifier = Modifier.fillMaxWidth().weight(1f),
            faceColor = { colors[it.cubeIndex] },
        ) { f ->
            val i = mesh.blockIndex[f.cubeIndex]
            val x = i / (st.template.sizeY * st.template.sizeZ); val y = (i / st.template.sizeZ) % st.template.sizeY; val z = i % st.template.sizeZ
            picked = "${palette.getOrNull(st.template.primary[i])?.name} ($x, $y, $z)"
            st.layer = y
        }
        Text(picked ?: stringResource(R.string.structure_preview_help), style = MaterialTheme.typography.bodySmall)
        if (mesh.truncated) InfoCard(stringResource(R.string.structure_preview_truncated, StructureMesh.MAX_CUBES))
        LabeledSlider(stringResource(R.string.model_light_angle, light.toInt()), null, light, 0f..360f) { light = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { camera.frame(mesh.faces) }) { Text(stringResource(R.string.model_reframe)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun GenerationTab(id: String, namespace: String, shortId: String, st: EditorState, loot: Int?, onLoot: (Int?) -> Unit) {
    val (ctl, start) = rememberGenerator(id, "Génération naturelle de structure")
    var natural by remember { mutableStateOf(true) }
    var tags by remember { mutableStateOf(setOf("plains")) }
    var oneIn by remember { mutableFloatStateOf(40f) }
    var buried by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.structure_loot_title))
        @Suppress("UNUSED_EXPRESSION") st.version
        val chests = st.template.palette.indexOfFirst { it.name == "minecraft:chest" }.let { pi -> pi >= 0 && st.template.primary.any { it == pi } }
        HelpText(stringResource(if (chests) R.string.structure_loot_help else R.string.structure_loot_no_chest))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = loot == null, onClick = { onLoot(null) }, label = { Text(stringResource(R.string.structure_loot_none)) })
            LOOTS.forEachIndexed { i, (label, _) -> FilterChip(selected = loot == i, onClick = { onLoot(i) }, label = { Text(label) }) }
        }
        HelpText(stringResource(R.string.structure_loot_unverified))

        SectionTitle(stringResource(R.string.structure_natural_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.structure_natural_switch), Modifier.weight(1f))
            Switch(natural, { natural = it }, Modifier.testTag("natural_switch"))
        }
        if (natural) {
            Text(stringResource(R.string.structure_biomes))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BIOME_TAGS.forEach { (tag, label) ->
                    FilterChip(selected = tag in tags, onClick = { tags = if (tag in tags) tags - tag else tags + tag }, label = { Text(label) })
                }
            }
            HelpText(stringResource(R.string.structure_biomes_help))
            LabeledSlider(stringResource(R.string.structure_rarity, oneIn.roundToInt()), stringResource(R.string.structure_rarity_help), oneIn, 1f..400f) { oneIn = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.structure_buried), Modifier.weight(1f))
                Switch(buried, { buried = it })
            }
            InfoCard(stringResource(R.string.structure_natural_limits))
            Button(onClick = {
                val c = GeneratedContent()
                StructureGenerator.naturalFiles(c, namespace, shortId, NaturalSpawn(tags.toList(), oneIn.roundToInt(), buried))
                val errors = StructureSpec(shortId, shortId, NaturalSpawn(tags.toList(), oneIn.roundToInt(), buried)).validate()
                if (errors.isEmpty()) start(c)
            }, enabled = !ctl.busy, modifier = Modifier.fillMaxWidth().testTag("natural_apply")) { Text(stringResource(R.string.structure_natural_apply)) }
            GenerationResult(ctl, stringResource(R.string.structure_test_hint, "$namespace:$shortId"))
        } else {
            HelpText(stringResource(R.string.structure_manual_hint, "$namespace:$shortId"))
        }
    }
}
