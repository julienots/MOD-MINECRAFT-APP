package com.bedrockmodstudio.ui.model

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.bedrockmodstudio.core.render.Bounds
import com.bedrockmodstudio.core.render.GpuMesh
import com.bedrockmodstudio.ui.model.gl.GlBatch
import com.bedrockmodstudio.ui.model.gl.GlScene
import com.bedrockmodstudio.ui.model.gl.GpuCapture
import com.bedrockmodstudio.ui.model.gl.GpuSceneView
import com.bedrockmodstudio.ui.model.gl.Render3D
import com.bedrockmodstudio.ui.model.gl.Render3DToolbar
import com.bedrockmodstudio.core.model3d.GeoModel
import com.bedrockmodstudio.core.model3d.MeshBuilder
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.model3d.BonePose
import com.bedrockmodstudio.core.model3d.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Caméra orbitale : un doigt tourne, deux doigts zooment et déplacent. */
class OrbitCamera {
    var yaw by mutableFloatStateOf(35f)
    var pitch by mutableFloatStateOf(20f)
    var distance by mutableFloatStateOf(48f)
    var target by mutableStateOf(Vec3(0.0, 8.0, 0.0))

    fun frame(model: GeoModel) = frame(MeshBuilder.build(model))

    fun frame(faces: List<MeshFace>) {
        if (faces.isEmpty()) return
        val pts = faces.flatMap { it.corners }
        val min = Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z })
        val max = Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
        target = (min + max) * 0.5
        val r = sqrt((max.x - min.x) * (max.x - min.x) + (max.y - min.y) * (max.y - min.y) + (max.z - min.z) * (max.z - min.z))
        distance = (r * 1.6).toFloat().coerceIn(8f, 400f)
    }
}

data class Projected(val face: MeshFace, val pts: FloatArray, val depth: Double, val shade: Float)

/** Projection perspective de toutes les faces visibles, triées de la plus lointaine à la plus proche. */
fun project(faces: List<MeshFace>, cam: OrbitCamera, w: Int, h: Int, lighting: Boolean, lightAngle: Float): List<Projected> {
    val cy = cos(Math.toRadians(cam.yaw.toDouble())); val sy = sin(Math.toRadians(cam.yaw.toDouble()))
    val cp = cos(Math.toRadians(cam.pitch.toDouble())); val sp = sin(Math.toRadians(cam.pitch.toDouble()))
    fun view(p: Vec3): Vec3 {
        val d = p - cam.target
        val x1 = d.x * cy - d.z * sy; val z1 = d.x * sy + d.z * cy
        // Angle vertical positif = caméra au-dessus du modèle, qui regarde vers le bas.
        val y2 = d.y * cp + z1 * sp; val z2 = -d.y * sp + z1 * cp
        return Vec3(x1, y2, z2 + cam.distance)
    }
    val f = minOf(w, h) * 1.1
    val la = Math.toRadians(lightAngle.toDouble())
    val light = Vec3(cos(la) * 0.6, 0.8, sin(la) * 0.6)
    val out = ArrayList<Projected>(faces.size)
    for (face in faces) {
        val v = face.corners.map(::view)
        if (v.any { it.z < 1.0 }) continue
        val c = v.reduce { a, b -> a + b } * 0.25
        val e1 = v[1] - v[0]; val e2 = v[3] - v[0]
        val n = Vec3(e2.y * e1.z - e2.z * e1.y, e2.z * e1.x - e2.x * e1.z, e2.x * e1.y - e2.y * e1.x)
        if (n.x * c.x + n.y * c.y + n.z * c.z >= 0) continue // face tournée dos à la caméra
        val pts = FloatArray(8)
        v.forEachIndexed { i, p ->
            pts[i * 2] = (w / 2 + f * p.x / p.z).toFloat()
            pts[i * 2 + 1] = (h / 2 - f * p.y / p.z).toFloat()
        }
        val nn = face.normal; val len = sqrt(nn.x * nn.x + nn.y * nn.y + nn.z * nn.z).coerceAtLeast(1e-9)
        val dot = (nn.x * light.x + nn.y * light.y + nn.z * light.z) / len
        val shade = if (lighting) (0.55 + 0.45 * maxOf(0.0, dot)).toFloat() else 1f
        out += Projected(face, pts, c.z, shade)
    }
    return out.sortedByDescending { it.depth }
}

private fun inside(px: Float, py: Float, pts: FloatArray): Boolean {
    var hit = false
    var j = 3
    for (i in 0 until 4) {
        val xi = pts[i * 2]; val yi = pts[i * 2 + 1]; val xj = pts[j * 2]; val yj = pts[j * 2 + 1]
        if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) hit = !hit
        j = i
    }
    return hit
}

/**
 * Vue 3D du modèle. Le rendu se fait dans une image hors écran avec le moteur logiciel d'Android,
 * ce qui fonctionne de la même façon sur toutes les versions (les triangles texturés ne sont pas
 * pris en charge par l'accélération matérielle avant Android 10).
 */
@Composable
fun ModelViewport(
    model: GeoModel,
    poses: Map<String, BonePose>,
    texture: Bitmap?,
    selectedBone: Int?,
    selectedCube: Int?,
    camera: OrbitCamera,
    lighting: Boolean,
    lightAngle: Float,
    modifier: Modifier = Modifier,
    revision: Any? = null,
    onPaint: ((face: MeshFace, u: Double, v: Double) -> Unit)? = null,
    onSelect: (bone: Int, cube: Int) -> Unit,
) {
    val faces = remember(model, poses) { MeshBuilder.build(model, poses) }
    MeshViewport(
        faces, texture, model.textureWidth, model.textureHeight, camera, lighting, lightAngle, modifier,
        selected = { it.boneIndex == selectedBone && (selectedCube == null || it.cubeIndex == selectedCube) },
        selectionKey = selectedBone to selectedCube,
        revision = revision,
        onPaint = onPaint,
    ) { onSelect(it.boneIndex, it.cubeIndex) }
}

/** Point du monde → déplacement correspondant à un glissement de (dx, dy) pixels à l'écran, à la distance de la caméra. */
fun OrbitCamera.screenDelta(dx: Float, dy: Float, viewSize: IntSize): Vec3 {
    val cy = cos(Math.toRadians(yaw.toDouble())); val sy = sin(Math.toRadians(yaw.toDouble()))
    val cp = cos(Math.toRadians(pitch.toDouble())); val sp = sin(Math.toRadians(pitch.toDouble()))
    val k = distance / (minOf(viewSize.width, viewSize.height).coerceAtLeast(1) * 1.1)
    val right = Vec3(cy, 0.0, -sy)
    val up = Vec3(sp * sy, cp, sp * cy)
    return right * (dx * k) + up * (-dy * k)
}

/** Coordonnées de texture (pixels) du point (px, py) dans une face projetée, comme le dessin en éventail de deux triangles. */
fun uvAt(p: Projected, px: Float, py: Float): Pair<Double, Double>? {
    val pts = p.pts
    for ((b, c) in listOf(1 to 2, 2 to 3)) {
        val ax = pts[0]; val ay = pts[1]; val bx = pts[b * 2]; val by = pts[b * 2 + 1]; val cx = pts[c * 2]; val cyy = pts[c * 2 + 1]
        val det = (by - cyy) * (ax - cx) + (cx - bx) * (ay - cyy)
        if (abs(det) < 1e-6f) continue
        val l1 = ((by - cyy) * (px - cx) + (cx - bx) * (py - cyy)) / det
        val l2 = ((cyy - ay) * (px - cx) + (ax - cx) * (py - cyy)) / det
        val l3 = 1 - l1 - l2
        if (l1 >= -0.01f && l2 >= -0.01f && l3 >= -0.01f) {
            val ua = p.face.uvs[0]; val ub = p.face.uvs[b]; val uc = p.face.uvs[c]
            return (l1 * ua.first + l2 * ub.first + l3 * uc.first) to (l1 * ua.second + l2 * ub.second + l3 * uc.second)
        }
    }
    return null
}

/**
 * Vue 3D de faces quelconques : texturées si [texture] est fournie (sauf les faces auxquelles [faceColor] donne une couleur),
 * sinon colorées par [faceColor]. Deux doigts zooment et déplacent la vue. Un doigt :
 * - par défaut, tourne la vue ; un simple toucher sélectionne la face visible sous le doigt ;
 * - avec [onDragObject], déplace l'objet (déplacement dans le monde, à la profondeur de la caméra) ;
 * - avec [onPaint], peint : chaque point touché donne la face et la coordonnée de texture (pixels).
 * [revision] force un nouveau rendu quand la texture a été modifiée sur place.
 */
@Composable
fun MeshViewport(
    faces: List<MeshFace>,
    texture: Bitmap?,
    textureWidth: Int,
    textureHeight: Int,
    camera: OrbitCamera,
    lighting: Boolean,
    lightAngle: Float,
    modifier: Modifier = Modifier,
    faceColor: ((MeshFace) -> Int?)? = null,
    selected: (MeshFace) -> Boolean = { false },
    selectionKey: Any? = null,
    revision: Any? = null,
    onDragObject: ((Vec3) -> Unit)? = null,
    onPaint: ((face: MeshFace, u: Double, v: Double) -> Unit)? = null,
    /** Objets supplémentaires dessinés seulement par le moteur GPU (décor, autres créatures). */
    extraBatches: List<GlBatch> = emptyList(),
    capture: GpuCapture? = null,
    showToolbar: Boolean = true,
    onTap: (MeshFace) -> Unit,
) {
    val context = LocalContext.current
    val gpu = Render3D.useGpu(context)
    val ownCapture = remember { GpuCapture() }
    val gpuCapture = capture ?: ownCapture
    var size by remember { mutableStateOf(IntSize.Zero) }
    val projected = remember(faces, size, camera.yaw, camera.pitch, camera.distance, camera.target, lighting, lightAngle, gpu) {
        if (size.width == 0 || gpu) emptyList() else project(faces, camera, size.width, size.height, lighting, lightAngle)
    }
    val frame = remember(projected, texture, selectionKey, size, faceColor, revision, gpu) {
        if (size.width == 0 || gpu) null else render(projected, texture, textureWidth, textureHeight, selected, faceColor, size)
    }
    // Moteur GPU : l'image vient d'OpenGL ; les touchers sont résolus par la projection logicielle (même caméra).
    val hitCache = remember { arrayOfNulls<Pair<Any, List<Projected>>>(1) }
    fun hits(): List<Projected> {
        if (!gpu) return projected
        val key = listOf(faces, size, camera.yaw, camera.pitch, camera.distance, camera.target)
        hitCache[0]?.let { (k, v) -> if (k == key) return v }
        return project(faces, camera, size.width, size.height, lighting, lightAngle).also { hitCache[0] = key to it }
    }
    // Un lambda (et non `::hits`) : deux références à la même fonction locale sont « égales » et l'état ne serait jamais mis à jour.
    val currentHits by rememberUpdatedState<() -> List<Projected>> { hits() }
    val vertices = remember(faces, texture, faceColor, selectionKey, gpu) {
        if (!gpu) null else GpuMesh.pack(faces, textureWidth, textureHeight, textured = texture != null, color = faceColor, highlight = selected)
    }
    val anySelected = remember(faces, selectionKey, gpu) { gpu && faces.any(selected) }
    val bounds = remember(faces, extraBatches, gpu) {
        if (!gpu) null else extraBatches.fold(Bounds.of(faces)) { acc, b -> acc.union(b.bounds) }
    }
    val currentTap by rememberUpdatedState(onTap)
    val currentDrag by rememberUpdatedState(onDragObject)
    val currentPaint by rememberUpdatedState(onPaint)
    val dragMode = onDragObject != null
    val paintMode = onPaint != null
    fun paintAt(pos: Offset) {
        val paint = currentPaint ?: return
        val hit = currentHits().lastOrNull { inside(pos.x, pos.y, it.pts) } ?: return
        val uv = uvAt(hit, pos.x, pos.y) ?: return
        paint(hit.face, uv.first, uv.second)
    }
    Box(modifier.onSizeChanged { size = it }) {
    if (gpu && vertices != null && bounds != null) {
        val scene = GlScene(
            batches = listOf(GlBatch("main", vertices, texture, revision)) + extraBatches,
            yaw = camera.yaw.toDouble(), pitch = camera.pitch.toDouble(), distance = camera.distance.toDouble(), target = camera.target,
            bounds = bounds, environment = Render3D.environment, mode = Render3D.mode, shadows = Render3D.shadows && lighting,
            ground = Render3D.ground, sunAzimuth = Render3D.sunAzimuth.toDouble() + lightAngle, animated = anySelected,
        )
        GpuSceneView(scene, Modifier.matchParentSize(), gpuCapture)
    }
    Canvas(
        Modifier.matchParentSize().testTag("model_viewport").pointerInput(dragMode, paintMode) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var moved = false
                var multi = false
                var last = down.position
                if (paintMode) paintAt(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        multi = true; moved = true
                        camera.distance = (camera.distance / ev.calculateZoom()).coerceIn(4f, 600f)
                        val pan = ev.calculatePan()
                        val k = camera.distance / 600.0
                        val yr = Math.toRadians(camera.yaw.toDouble())
                        // Déplacement le long de l'axe « droite » de la vue (cos, 0, −sin) : le modèle suit les doigts.
                        camera.target = camera.target + Vec3(-pan.x * k * cos(yr), pan.y * k, pan.x * k * sin(yr))
                    } else if (!multi) {
                        val p = pressed[0].position
                        val d = p - last
                        if (abs(p.x - down.position.x) + abs(p.y - down.position.y) > 12f) moved = true
                        when {
                            paintMode -> if (d.x != 0f || d.y != 0f) paintAt(p)
                            dragMode -> if (moved) currentDrag?.invoke(camera.screenDelta(d.x, d.y, size))
                            moved -> {
                                camera.yaw += d.x * 0.4f
                                camera.pitch = (camera.pitch + d.y * 0.4f).coerceIn(-89f, 89f)
                            }
                        }
                        last = p
                    }
                    ev.changes.forEach { it.consume() }
                }
                if (!moved && !paintMode) {
                    // Toucher sans glisser : sélection de la face la plus proche sous le doigt.
                    val hit = currentHits().lastOrNull { inside(down.position.x, down.position.y, it.pts) }
                    if (hit != null) currentTap(hit.face)
                }
            }
        },
    ) {
        frame?.let { drawImage(it.asImageBitmap(), Offset.Zero) }
    }
    if (gpu && showToolbar) Render3DToolbar(gpuCapture, Modifier.align(Alignment.TopEnd))
    }
}

private val shaderCache = java.util.WeakHashMap<Bitmap, BitmapShader>()

/** Image fixe d'un maillage (vignettes), calculée hors du fil principal. */
fun renderStill(
    faces: List<MeshFace>, texture: Bitmap?, textureWidth: Int, textureHeight: Int, width: Int, height: Int,
    yaw: Float = 35f, pitch: Float = 22f, faceColor: ((MeshFace) -> Int)? = null, background: Int = 0xFF15181D.toInt(),
): Bitmap {
    val cam = OrbitCamera()
    cam.frame(faces)
    cam.yaw = yaw; cam.pitch = pitch
    val projected = project(faces, cam, width, height, lighting = true, lightAngle = 45f)
    return render(projected, texture, textureWidth, textureHeight, { false }, faceColor, IntSize(width, height), background)
}

private fun render(
    projected: List<Projected>, texture: Bitmap?, texW: Int, texH: Int, selected: (MeshFace) -> Boolean, faceColor: ((MeshFace) -> Int?)?, size: IntSize,
    background: Int = 0xFF15181D.toInt(),
): Bitmap {
    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(background)
    // Sol : petite grille de repère sous l'origine.
    val texPaint = Paint().apply {
        isAntiAlias = false; isFilterBitmap = false
        if (texture != null) shader = synchronized(shaderCache) { shaderCache.getOrPut(texture) { BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } }
    }
    val flat = Paint(Paint.ANTI_ALIAS_FLAG)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFFFD166.toInt() }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x40000000 }
    val sx = if (texture != null) texture.width.toFloat() / texW else 1f
    val sy = if (texture != null) texture.height.toFloat() / texH else 1f
    for (p in projected) {
        val shadeByte = (p.shade * 255).toInt().coerceIn(0, 255)
        val tint = (0xFF shl 24) or (shadeByte shl 16) or (shadeByte shl 8) or shadeByte
        val flatColor = faceColor?.invoke(p.face)
        if (texture != null && flatColor == null) {
            val texs = FloatArray(8)
            p.face.uvs.forEachIndexed { i, (u, v) -> texs[i * 2] = (u * sx).toFloat(); texs[i * 2 + 1] = (v * sy).toFloat() }
            canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLE_FAN, 8, p.pts, 0, texs, 0, IntArray(4) { tint }, 0, null, 0, 0, texPaint)
        } else {
            val base = flatColor ?: 0xFF8FA3B8.toInt()
            fun ch(c: Int) = ((c and 0xFF) * p.shade).toInt().coerceIn(0, 255)
            flat.color = (0xFF shl 24) or (ch(base shr 16) shl 16) or (ch(base shr 8) shl 8) or ch(base)
            canvas.drawPath(quad(p.pts), flat)
        }
        canvas.drawPath(quad(p.pts), edge)
        if (selected(p.face)) canvas.drawPath(quad(p.pts), outline)
    }
    return bmp
}

private fun quad(pts: FloatArray) = Path().apply {
    moveTo(pts[0], pts[1]); lineTo(pts[2], pts[3]); lineTo(pts[4], pts[5]); lineTo(pts[6], pts[7]); close()
}
