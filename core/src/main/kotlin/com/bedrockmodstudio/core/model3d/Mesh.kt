package com.bedrockmodstudio.core.model3d

import kotlin.math.cos
import kotlin.math.sin

/** Matrice 4×4 (colonne-majeure non nécessaire : usage interne, multiplication classique ligne × colonne). */
class Mat4(val m: DoubleArray = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }) {
    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += m[i * 4 + k] * o.m[k * 4 + j]
            r[i * 4 + j] = s
        }
        return Mat4(r)
    }

    fun apply(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3],
        m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7],
        m[8] * v.x + m[9] * v.y + m[10] * v.z + m[11],
    )

    companion object {
        fun translate(t: Vec3) = Mat4().also { it.m[3] = t.x; it.m[7] = t.y; it.m[11] = t.z }
        fun rotX(deg: Double): Mat4 { val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            return Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0)) }
        fun rotY(deg: Double): Mat4 { val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            return Mat4(doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0)) }
        fun rotZ(deg: Double): Mat4 { val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            return Mat4(doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)) }
        fun scale(k: Vec3) = Mat4().also { it.m[0] = k.x; it.m[5] = k.y; it.m[10] = k.z }

        /** Rotation d'Euler autour d'un pivot (ordre Z, puis Y, puis X, comme Bedrock). */
        fun rotateAround(pivot: Vec3, rot: Vec3): Mat4 =
            translate(pivot) * rotZ(rot.z) * rotY(rot.y) * rotX(rot.x) * translate(pivot * -1.0)
    }
}

enum class Face { NORTH, SOUTH, EAST, WEST, UP, DOWN }

/** Face d'un cube prête à dessiner : 4 sommets (repère du modèle) et leurs coordonnées de texture en pixels. */
data class MeshFace(
    val boneIndex: Int,
    val cubeIndex: Int,
    val face: Face,
    val corners: List<Vec3>,
    val uvs: List<Pair<Double, Double>>,
    val normal: Vec3,
)

/** Transformations animées d'un os à un instant donné (degrés, pixels, facteur). */
data class BonePose(val rotation: Vec3 = Vec3.ZERO, val position: Vec3 = Vec3.ZERO, val scale: Vec3 = Vec3(1.0, 1.0, 1.0))

object MeshBuilder {

    /**
     * Patron « boîte » de Bedrock pour un cube de taille (w, h, d) au point (u, v) :
     * dessus (u+d, v), dessous (u+d+w, v), puis sur la rangée v+d : est, nord (avant), ouest, sud.
     */
    fun boxUv(u: Double, v: Double, w: Double, h: Double, d: Double): Map<Face, DoubleArray> = mapOf(
        Face.UP to doubleArrayOf(u + d, v, w, d),
        Face.DOWN to doubleArrayOf(u + d + w, v, w, d),
        Face.EAST to doubleArrayOf(u, v + d, d, h),
        Face.NORTH to doubleArrayOf(u + d, v + d, w, h),
        Face.WEST to doubleArrayOf(u + d + w, v + d, d, h),
        Face.SOUTH to doubleArrayOf(u + 2 * d + w, v + d, w, h),
    )

    /** Matrice monde de chaque os (parent d'abord), en tenant compte de la pose animée. */
    fun boneMatrices(model: GeoModel, poses: Map<String, BonePose> = emptyMap()): Map<String, Mat4> {
        val result = HashMap<String, Mat4>()
        fun compute(b: Bone, depth: Int): Mat4 {
            result[b.name]?.let { return it }
            val parent = if (b.parent != null && depth < 64) model.bone(b.parent)?.let { compute(it, depth + 1) } ?: Mat4() else Mat4()
            val pose = poses[b.name] ?: BonePose()
            val rot = b.rotation + pose.rotation
            // Bedrock : la position animée déplace l'os ; l'échelle s'applique autour du pivot.
            val local = Mat4.translate(pose.position) * Mat4.rotateAround(b.pivot, rot) *
                Mat4.translate(b.pivot) * Mat4.scale(pose.scale) * Mat4.translate(b.pivot * -1.0)
            return (parent * local).also { result[b.name] = it }
        }
        model.bones.forEach { compute(it, 0) }
        return result
    }

    fun build(model: GeoModel, poses: Map<String, BonePose> = emptyMap()): List<MeshFace> {
        val mats = boneMatrices(model, poses)
        val out = ArrayList<MeshFace>()
        model.bones.forEachIndexed { bi, bone ->
            val bm = mats[bone.name] ?: Mat4()
            bone.cubes.forEachIndexed { ci, c ->
                val i = c.inflate
                val a = c.origin - Vec3(i, i, i)
                val b = c.origin + c.size + Vec3(i, i, i)
                val cubeM = if (c.rotation != null) Mat4.rotateAround(c.pivot ?: (c.origin + c.size * 0.5), c.rotation) else Mat4()
                val m = bm * cubeM
                fun p(x: Double, y: Double, z: Double) = m.apply(Vec3(x, y, z))
                // Coins dans l'ordre : haut-gauche, haut-droite, bas-droite, bas-gauche, vus de l'extérieur.
                val faces = mapOf(
                    Face.NORTH to listOf(p(b.x, b.y, a.z), p(a.x, b.y, a.z), p(a.x, a.y, a.z), p(b.x, a.y, a.z)),
                    Face.SOUTH to listOf(p(a.x, b.y, b.z), p(b.x, b.y, b.z), p(b.x, a.y, b.z), p(a.x, a.y, b.z)),
                    Face.EAST to listOf(p(b.x, b.y, b.z), p(b.x, b.y, a.z), p(b.x, a.y, a.z), p(b.x, a.y, b.z)),
                    Face.WEST to listOf(p(a.x, b.y, a.z), p(a.x, b.y, b.z), p(a.x, a.y, b.z), p(a.x, a.y, a.z)),
                    Face.UP to listOf(p(a.x, b.y, a.z), p(b.x, b.y, a.z), p(b.x, b.y, b.z), p(a.x, b.y, b.z)),
                    Face.DOWN to listOf(p(a.x, a.y, b.z), p(b.x, a.y, b.z), p(b.x, a.y, a.z), p(a.x, a.y, a.z)),
                )
                val rects = faceRects(c)
                for ((face, corners) in faces) {
                    val r = rects[face] ?: continue
                    val (u0, v0, w, h) = r.toList()
                    val mirror = c.mirror || bone.mirror
                    val uvs = if (mirror) listOf(u0 to v0, u0 + w to v0, u0 + w to v0 + h, u0 to v0 + h)
                    else listOf(u0 + w to v0, u0 to v0, u0 to v0 + h, u0 + w to v0 + h)
                    val e1 = corners[1] - corners[0]; val e2 = corners[3] - corners[0]
                    // Normale sortante : e2 × e1 (les coins sont listés dans le sens horaire vus de l'extérieur).
                    val n = Vec3(e2.y * e1.z - e2.z * e1.y, e2.z * e1.x - e2.x * e1.z, e2.x * e1.y - e2.y * e1.x)
                    // L'orientation de la texture sur les faces dessus/dessous est approximative dans l'aperçu.
                    out += MeshFace(bi, ci, face, corners, uvs, n)
                }
            }
        }
        return out
    }

    /** Rectangles de texture (u, v, largeur, hauteur) par face : patron boîte ou UV par face. */
    fun faceRects(c: Cube): Map<Face, DoubleArray> {
        val fu = c.faceUv ?: return boxUv(c.uv.first, c.uv.second, Math.floor(c.size.x), Math.floor(c.size.y), Math.floor(c.size.z))
        val res = HashMap<Face, DoubleArray>()
        for (f in Face.entries) {
            val o = fu.obj(f.name.lowercase()) ?: continue
            val uv = Vec3.of(com.bedrockmodstudio.core.json.JsonArray(((o.arr("uv")?.items ?: emptyList()) + com.bedrockmodstudio.core.json.JsonNumber(0)).take(3)))
            val sz = Vec3.of(com.bedrockmodstudio.core.json.JsonArray(((o.arr("uv_size")?.items ?: emptyList()) + com.bedrockmodstudio.core.json.JsonNumber(0)).take(3)))
            res[f] = doubleArrayOf(uv.x, uv.y, sz.x, sz.y)
        }
        return res
    }

    /** Export OBJ (format d'échange universel) : un objet par os, coordonnées en blocs, UV normalisées. */
    fun toObj(model: GeoModel): String {
        val sb = StringBuilder("# Exporté par Bedrock Mod Studio — ${model.identifier}\n")
        val faces = build(model)
        var vertex = 1
        for ((boneIndex, group) in faces.groupBy { it.boneIndex }) {
            sb.append("o ").append(model.bones[boneIndex].name).append('\n')
            for (f in group) {
                f.corners.forEach { sb.append("v ${fmt(it.x / 16)} ${fmt(it.y / 16)} ${fmt(it.z / 16)}\n") }
                f.uvs.forEach { (u, v) -> sb.append("vt ${fmt(u / model.textureWidth)} ${fmt(1 - v / model.textureHeight)}\n") }
                sb.append("f ${(0 until 4).joinToString(" ") { "${vertex + it}/${vertex + it}" }}\n")
                vertex += 4
            }
        }
        return sb.toString()
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.ROOT, "%.5f", d)
}
