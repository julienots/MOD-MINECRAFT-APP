package com.bedrockmodstudio.ui.mods

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.model3d.Animation
import com.bedrockmodstudio.core.model3d.Mat4
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.mods.ModCatalog
import com.bedrockmodstudio.core.render.Bounds
import com.bedrockmodstudio.core.render.DemoWorld
import com.bedrockmodstudio.core.render.GpuMesh
import com.bedrockmodstudio.ui.Routes
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.HelpText
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.model.MeshViewport
import com.bedrockmodstudio.ui.model.OrbitCamera
import com.bedrockmodstudio.ui.model.gl.GlBatch
import com.bedrockmodstudio.ui.model.gl.Render3D
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un contenu posé dans la vitrine : son aperçu, sa place sur le plateau et son animation de marche (s'il en a une). */
private class ShowcaseItem(val entry: ModCatalog.Entry, val preview: ModPreview, val place: Mat4, val animation: Animation?)

private class ShowcaseData(val world: DemoWorld.World, val atlas: Bitmap, val items: List<ShowcaseItem>)

/**
 * Vitrine 3D : tous les contenus du mod (créatures, objets, blocs) posés ensemble sur une île de démonstration,
 * avec ombres, ambiances (jour, coucher de soleil, nuit, Nether, End), animations de marche et photo HD.
 * Nécessite le moteur GPU (OpenGL ES 3.0).
 */
@Composable
fun ShowcaseScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val app = context.container
    remember { Render3D.load(context) }
    val gpu = Render3D.useGpu(context)
    val data by produceState<ShowcaseData?>(null, id) {
        value = withContext(Dispatchers.IO) {
            val files = app.store.files(id)
            val entries = runCatching { ModCatalog.scan(files).entries }.getOrDefault(emptyList()).take(MAX_ITEMS)
            val previews = entries.map { e -> e to runCatching { ModPreview.load(files, e) }.getOrNull() }.filter { it.second != null }
            val bounds = previews.map { Bounds.of(it.second!!.faces()) }
            val spacing = maxOf(40.0, bounds.maxOfOrNull { maxOf(it.max.x - it.min.x, it.max.z - it.min.z) } ?: 16.0) * 1.5
            val world = DemoWorld.build(DemoWorld.plateauFor(previews.size, spacing))
            val places = DemoWorld.placements(bounds, spacing)
            val atlasPng = DemoWorld.atlas().toPng()
            val atlas = BitmapFactory.decodeByteArray(atlasPng, 0, atlasPng.size, BitmapFactory.Options().apply { inScaled = false })
            val items = previews.mapIndexed { i, (e, p) ->
                val walk = p!!.animations.firstOrNull { a -> WALK.any { a.name.contains(it, ignoreCase = true) } } ?: p.animations.firstOrNull { it.loop }
                ShowcaseItem(e, p, places[i], walk)
            }
            ShowcaseData(world, atlas, items)
        }
    }
    var turntable by remember { mutableStateOf(true) }
    var animate by remember { mutableStateOf(true) }
    var time by remember { mutableStateOf(0.0) }

    Scaffold(topBar = { BmsTopBar("Vitrine 3D", subtitle = data?.let { "${it.items.size} contenus sur l'île" }, onBack = { nav.popBackStack() }) }) { padding ->
        val d = data
        if (d == null) { Box(Modifier.padding(padding)) { LoadingBox("Construction de l'île…") }; return@Scaffold }
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            if (!gpu) {
                InfoCard("La vitrine utilise le moteur 3D GPU (OpenGL ES 3.0). Il n'est pas disponible sur cet appareil ou il est désactivé dans les réglages.",
                    color = BmsColors.Warning)
                return@Column
            }
            if (d.items.isEmpty()) InfoCard("Ton mod ne contient encore aucune créature, aucun objet ni aucun bloc : l'île est vide. Crée du contenu puis reviens ici.")
            val camera = remember(d) {
                OrbitCamera().also { c ->
                    c.frame(d.world.faces); c.pitch = 28f; c.distance = (c.distance * 0.8f).coerceAtLeast(60f)
                    c.target = com.bedrockmodstudio.core.model3d.Vec3(0.0, 8.0, 0.0)
                }
            }
            LaunchedEffect(turntable, animate, d) {
                var last = 0L
                while (turntable || animate) {
                    withFrameNanos { now ->
                        if (last != 0L) {
                            val dt = (now - last) / 1e9
                            if (turntable) camera.yaw += (dt * 12).toFloat()
                            if (animate) time += dt
                        }
                        last = now
                    }
                }
            }
            // Les sommets animés sont recalculés au plus 30 fois par seconde.
            val frame = (time * 30).toLong()
            val batches = remember(d, if (animate) frame else -1L) {
                d.items.mapIndexed { i, it ->
                    val a = it.animation
                    val poses = if (animate && a != null) a.sample(if (a.length > 0) time % maxOf(a.length, 0.05) else 0.0) else emptyMap()
                    val faces: List<MeshFace> = it.preview.faces(poses)
                    GlBatch("item$i", GpuMesh.pack(faces, it.preview.textureWidth, it.preview.textureHeight, it.preview.texture != null, it.preview.faceColor),
                        it.preview.texture, null, it.place)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).testTag("showcase_view")) {
                MeshViewport(
                    d.world.faces, d.atlas, DemoWorld.ATLAS_WIDTH, DemoWorld.ATLAS_HEIGHT, camera,
                    lighting = true, lightAngle = 0f, modifier = Modifier.fillMaxSize(), extraBatches = batches,
                ) { turntable = false }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(turntable, { turntable = !turntable }, label = { Text("Tour de l'île") }, leadingIcon = { Icon(Icons.Filled.Autorenew, null) })
                FilterChip(animate, { animate = !animate }, label = { Text("Animations") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.items.forEach { it ->
                    AssistChip(onClick = { nav.navigate(Routes.modEntry(id, it.entry.kind.name, it.entry.id)) }, label = { Text(it.entry.name) })
                }
            }
            HelpText("Un doigt : tourner · deux doigts : zoomer et déplacer · boutons en haut à droite : ambiance, affichage, lumière, photo HD.")
        }
    }
}

private const val MAX_ITEMS = 24
private val WALK = listOf("walk", "move", "marche")
