package com.bedrockmodstudio.core.render

import com.bedrockmodstudio.core.model3d.Mat4
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.model3d.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mathématiques du moteur 3D GPU (OpenGL ES 3 / WebGL 2). Les matrices sont des [Mat4] (ligne × colonne),
 * converties en tableaux colonne-majeure par [gl] au moment de l'envoi au GPU.
 *
 * La caméra reproduit EXACTEMENT la projection du moteur logiciel (`project()` de l'application) :
 * mêmes angles, même distance, même focale (1,1 × le plus petit côté de la vue, en pixels). Un toucher
 * peut donc être résolu par le moteur logiciel pendant que l'image est dessinée par le GPU.
 */
object RenderMath {

    fun gl(m: Mat4): FloatArray = FloatArray(16) { i -> m.m[(i % 4) * 4 + i / 4].toFloat() }

    /** Vue orbitale : rotation de [yaw] autour de Y, puis de [pitch] autour de X (positif = vue de dessus), puis recul de [distance]. */
    fun view(yaw: Double, pitch: Double, distance: Double, target: Vec3): Mat4 {
        val cy = cos(Math.toRadians(yaw)); val sy = sin(Math.toRadians(yaw))
        val cp = cos(Math.toRadians(pitch)); val sp = sin(Math.toRadians(pitch))
        val r0 = doubleArrayOf(cy, 0.0, -sy)
        val r1 = doubleArrayOf(sp * sy, cp, sp * cy)
        val r2 = doubleArrayOf(cp * sy, -sp, cp * cy)
        fun dotT(r: DoubleArray) = -(r[0] * target.x + r[1] * target.y + r[2] * target.z)
        return Mat4(doubleArrayOf(
            r0[0], r0[1], r0[2], dotT(r0),
            r1[0], r1[1], r1[2], dotT(r1),
            r2[0], r2[1], r2[2], dotT(r2) + distance,
            0.0, 0.0, 0.0, 1.0,
        ))
    }

    /** Position de l'œil dans le monde (inverse de [view]). */
    fun eye(yaw: Double, pitch: Double, distance: Double, target: Vec3): Vec3 {
        val cy = cos(Math.toRadians(yaw)); val sy = sin(Math.toRadians(yaw))
        val cp = cos(Math.toRadians(pitch)); val sp = sin(Math.toRadians(pitch))
        // Transposée de la rotation appliquée à (0, 0, −distance).
        return target + Vec3(cp * sy, -sp, cp * cy) * -distance
    }

    /** Focale du moteur logiciel, en pixels. */
    fun focal(width: Int, height: Int) = minOf(width, height) * 1.1

    /**
     * Perspective dont l'axe de profondeur est +Z (devant la caméra), comme le moteur logiciel :
     * x_ndc = 2f·x / (w·z), y_ndc = 2f·y / (h·z), profondeur −1 au plan [near] et +1 au plan [far].
     */
    fun perspective(width: Int, height: Int, near: Double, far: Double): Mat4 {
        val f = focal(width, height)
        val a = (far + near) / (far - near)
        val b = -2 * far * near / (far - near)
        return Mat4(doubleArrayOf(
            2 * f / width, 0.0, 0.0, 0.0,
            0.0, 2 * f / height, 0.0, 0.0,
            0.0, 0.0, a, b,
            0.0, 0.0, 1.0, 0.0,
        ))
    }

    fun ortho(l: Double, r: Double, b: Double, t: Double, n: Double, f: Double) = Mat4(doubleArrayOf(
        2 / (r - l), 0.0, 0.0, -(r + l) / (r - l),
        0.0, 2 / (t - b), 0.0, -(t + b) / (t - b),
        0.0, 0.0, 2 / (f - n), -(f + n) / (f - n),
        0.0, 0.0, 0.0, 1.0,
    ))

    /** Vue « regarde vers » dont l'axe de profondeur est +Z (même convention que [view]). */
    fun lookAt(eye: Vec3, center: Vec3, up: Vec3 = Vec3(0.0, 1.0, 0.0)): Mat4 {
        val z = normalize(center - eye)
        var x = cross(up, z)
        if (length(x) < 1e-6) x = cross(Vec3(0.0, 0.0, 1.0), z)
        x = normalize(x)
        val y = cross(z, x)
        return Mat4(doubleArrayOf(
            x.x, x.y, x.z, -dot(x, eye),
            y.x, y.y, y.z, -dot(y, eye),
            z.x, z.y, z.z, -dot(z, eye),
            0.0, 0.0, 0.0, 1.0,
        ))
    }

    /** Matrice lumière (ortho) qui couvre une sphère de centre [center] et de rayon [radius], pour les ombres. */
    fun lightMatrix(sunDir: Vec3, center: Vec3, radius: Double): Mat4 {
        val r = max(radius, 1.0)
        val eye = center + normalize(sunDir) * (r * 2)
        return ortho(-r, r, -r, r, 0.1, r * 4) * lookAt(eye, center)
    }

    /** Direction du soleil à partir d'un angle horizontal et d'une hauteur (degrés). */
    fun sunDirection(azimuth: Double, elevation: Double): Vec3 {
        val a = Math.toRadians(azimuth); val e = Math.toRadians(elevation)
        return normalize(Vec3(cos(a) * cos(e), sin(e), sin(a) * cos(e)))
    }

    /** Inverse générale 4×4 (méthode des cofacteurs). */
    fun inverse(mat: Mat4): Mat4 {
        val m = mat.m
        val inv = DoubleArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        val k = if (det == 0.0) 0.0 else 1.0 / det
        return Mat4(DoubleArray(16) { inv[it] * k })
    }

    /** Point → coordonnées clip (x, y, z, w). */
    fun clip(m: Mat4, p: Vec3): DoubleArray {
        val a = m.m
        return DoubleArray(4) { r -> a[r * 4] * p.x + a[r * 4 + 1] * p.y + a[r * 4 + 2] * p.z + a[r * 4 + 3] }
    }

    fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
    fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    fun length(a: Vec3) = sqrt(dot(a, a))
    fun normalize(a: Vec3): Vec3 { val l = length(a); return if (l < 1e-12) Vec3(0.0, 1.0, 0.0) else a * (1.0 / l) }
}

/** Boîte englobante d'un ensemble de faces. */
data class Bounds(val min: Vec3, val max: Vec3) {
    val center get() = (min + max) * 0.5
    val radius get() = RenderMath.length(max - min) * 0.5

    fun union(o: Bounds) = Bounds(
        Vec3(minOf(min.x, o.min.x), minOf(min.y, o.min.y), minOf(min.z, o.min.z)),
        Vec3(maxOf(max.x, o.max.x), maxOf(max.y, o.max.y), maxOf(max.z, o.max.z)),
    )

    fun transformed(m: Mat4): Bounds {
        val pts = listOf(
            Vec3(min.x, min.y, min.z), Vec3(max.x, min.y, min.z), Vec3(min.x, max.y, min.z), Vec3(max.x, max.y, min.z),
            Vec3(min.x, min.y, max.z), Vec3(max.x, min.y, max.z), Vec3(min.x, max.y, max.z), Vec3(max.x, max.y, max.z),
        ).map(m::apply)
        return Bounds(Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }), Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z }))
    }

    companion object {
        val UNIT = Bounds(Vec3(-8.0, 0.0, -8.0), Vec3(8.0, 16.0, 8.0))

        fun of(faces: List<MeshFace>): Bounds {
            if (faces.isEmpty()) return UNIT
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
            for (f in faces) for (p in f.corners) {
                if (p.x < x0) x0 = p.x; if (p.y < y0) y0 = p.y; if (p.z < z0) z0 = p.z
                if (p.x > x1) x1 = p.x; if (p.y > y1) y1 = p.y; if (p.z > z1) z1 = p.z
            }
            return Bounds(Vec3(x0, y0, z0), Vec3(x1, y1, z1))
        }
    }
}

/**
 * Sommets prêts pour le GPU : 6 sommets (2 triangles) par face, [STRIDE] flottants chacun :
 * position (3), normale (3), UV normalisées (2), teinte RVB + « texturé » (4), coin du quad (2), surbrillance (1).
 */
object GpuMesh {
    const val STRIDE = 15
    const val FLOATS_PER_FACE = STRIDE * 6
    private val CORNER_ORDER = intArrayOf(0, 1, 2, 0, 2, 3)
    private val QUAD = arrayOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)

    /**
     * [color] : couleur unie d'une face (ARGB) ou null pour la texture ; [textured] faux = tout en couleur unie
     * (gris si [color] ne donne rien). [highlight] : faces sélectionnées.
     */
    fun pack(
        faces: List<MeshFace>,
        textureWidth: Int,
        textureHeight: Int,
        textured: Boolean,
        color: ((MeshFace) -> Int?)? = null,
        highlight: (MeshFace) -> Boolean = { false },
    ): FloatArray {
        val out = FloatArray(faces.size * FLOATS_PER_FACE)
        val tw = textureWidth.coerceAtLeast(1).toFloat(); val th = textureHeight.coerceAtLeast(1).toFloat()
        var o = 0
        for (f in faces) {
            val n = RenderMath.normalize(f.normal)
            val flat = color?.invoke(f)
            val useTex = textured && flat == null
            val c = flat ?: 0xFF8FA3B8.toInt()
            val r = if (useTex) 1f else ((c shr 16) and 0xFF) / 255f
            val g = if (useTex) 1f else ((c shr 8) and 0xFF) / 255f
            val b = if (useTex) 1f else (c and 0xFF) / 255f
            val hi = if (highlight(f)) 1f else 0f
            for (k in CORNER_ORDER) {
                val p = f.corners[k]; val uv = f.uvs[k]; val q = QUAD[k]
                out[o++] = p.x.toFloat(); out[o++] = p.y.toFloat(); out[o++] = p.z.toFloat()
                out[o++] = n.x.toFloat(); out[o++] = n.y.toFloat(); out[o++] = n.z.toFloat()
                out[o++] = uv.first.toFloat() / tw; out[o++] = uv.second.toFloat() / th
                out[o++] = r; out[o++] = g; out[o++] = b; out[o++] = if (useTex) 1f else 0f
                out[o++] = q.first; out[o++] = q.second
                out[o++] = hi
            }
        }
        return out
    }
}
