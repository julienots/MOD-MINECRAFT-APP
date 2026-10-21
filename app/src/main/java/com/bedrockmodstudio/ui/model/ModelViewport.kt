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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
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
        val y2 = d.y * cp - z1 * sp; val z2 = d.y * sp + z1 * cp
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
    onSelect: (bone: Int, cube: Int) -> Unit,
) {
    val faces = remember(model, poses) { MeshBuilder.build(model, poses) }
    MeshViewport(
        faces, texture, model.textureWidth, model.textureHeight, camera, lighting, lightAngle, modifier,
        selected = { it.boneIndex == selectedBone && (selectedCube == null || it.cubeIndex == selectedCube) },
        selectionKey = selectedBone to selectedCube,
    ) { onSelect(it.boneIndex, it.cubeIndex) }
}

/**
 * Vue 3D de faces quelconques : texturées si [texture] est fournie, sinon colorées par [faceColor].
 * Un doigt tourne, deux doigts zooment et déplacent, un simple toucher sélectionne la face visible sous le doigt.
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
    faceColor: ((MeshFace) -> Int)? = null,
    selected: (MeshFace) -> Boolean = { false },
    selectionKey: Any? = null,
    onTap: (MeshFace) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val projected = remember(faces, size, camera.yaw, camera.pitch, camera.distance, camera.target, lighting, lightAngle) {
        if (size.width == 0) emptyList() else project(faces, camera, size.width, size.height, lighting, lightAngle)
    }
    val frame = remember(projected, texture, selectionKey, size, faceColor) {
        if (size.width == 0) null else render(projected, texture, textureWidth, textureHeight, selected, faceColor, size)
    }
    Canvas(
        modifier.testTag("model_viewport").onSizeChanged { size = it }.pointerInput(faces) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var moved = false
                var multi = false
                var last = down.position
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
                        camera.target = camera.target + Vec3(-pan.x * k * cos(yr), pan.y * k, -pan.x * k * sin(yr))
                    } else if (!multi) {
                        val p = pressed[0].position
                        val d = p - last
                        if (abs(p.x - down.position.x) + abs(p.y - down.position.y) > 12f) moved = true
                        if (moved) {
                            camera.yaw += d.x * 0.4f
                            camera.pitch = (camera.pitch + d.y * 0.4f).coerceIn(-89f, 89f)
                        }
                        last = p
                    }
                    ev.changes.forEach { it.consume() }
                }
                if (!moved) {
                    // Toucher sans glisser : sélection de la face la plus proche sous le doigt.
                    val hit = projected.lastOrNull { inside(down.position.x, down.position.y, it.pts) }
                    if (hit != null) onTap(hit.face)
                }
            }
        },
    ) {
        frame?.let { drawImage(it.asImageBitmap(), Offset.Zero) }
    }
}

private val shaderCache = HashMap<Bitmap, BitmapShader>()

private fun render(
    projected: List<Projected>, texture: Bitmap?, texW: Int, texH: Int, selected: (MeshFace) -> Boolean, faceColor: ((MeshFace) -> Int)?, size: IntSize,
): Bitmap {
    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(0xFF15181D.toInt())
    // Sol : petite grille de repère sous l'origine.
    val texPaint = Paint().apply {
        isAntiAlias = false; isFilterBitmap = false
        if (texture != null) shader = shaderCache.getOrPut(texture) { BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }
    val flat = Paint(Paint.ANTI_ALIAS_FLAG)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFFFD166.toInt() }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x40000000 }
    val sx = if (texture != null) texture.width.toFloat() / texW else 1f
    val sy = if (texture != null) texture.height.toFloat() / texH else 1f
    for (p in projected) {
        val shadeByte = (p.shade * 255).toInt().coerceIn(0, 255)
        val tint = (0xFF shl 24) or (shadeByte shl 16) or (shadeByte shl 8) or shadeByte
        if (texture != null) {
            val texs = FloatArray(8)
            p.face.uvs.forEachIndexed { i, (u, v) -> texs[i * 2] = (u * sx).toFloat(); texs[i * 2 + 1] = (v * sy).toFloat() }
            canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLE_FAN, 8, p.pts, 0, texs, 0, IntArray(4) { tint }, 0, null, 0, 0, texPaint)
        } else {
            val base = faceColor?.invoke(p.face) ?: 0xFF8FA3B8.toInt()
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
