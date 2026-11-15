package com.bedrockmodstudio.core.mods

import com.bedrockmodstudio.core.json.arr
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model3d.Bone
import com.bedrockmodstudio.core.model3d.Cube
import com.bedrockmodstudio.core.model3d.Face
import com.bedrockmodstudio.core.model3d.GeoModel
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.model3d.Vec3

/** Formes 3D d'aperçu pour les contenus qui n'ont pas de modèle : blocs pleins et objets (icônes en relief). */
object PreviewMeshes {

    /** Disposition de l'image assemblée pour un bloc plein : dessus | dessous | côté, 16×16 chacun. */
    const val BLOCK_ATLAS_WIDTH = 48
    const val BLOCK_ATLAS_HEIGHT = 16

    /** Bloc plein d'un mètre : utilise une image 48×16 (dessus, dessous, côté). */
    fun blockCube(): GeoModel {
        fun f(u: Int) = obj("uv" to arr(u, 0), "uv_size" to arr(16, 16))
        val faceUv = obj(
            "up" to f(0), "down" to f(16),
            "north" to f(32), "south" to f(32), "east" to f(32), "west" to f(32),
        )
        return GeoModel(
            identifier = "geometry.bms_preview_block", textureWidth = BLOCK_ATLAS_WIDTH, textureHeight = BLOCK_ATLAS_HEIGHT,
            bones = listOf(Bone("block", cubes = listOf(Cube(Vec3(-8.0, 0.0, -8.0), Vec3(16.0, 16.0, 16.0), faceUv = faceUv)))),
        )
    }

    /** Maillage coloré : [MeshFace.cubeIndex] est l'indice de la couleur dans [colors]. */
    class ColoredMesh(val faces: List<MeshFace>, val colors: IntArray)

    /**
     * Icône d'objet en relief, comme un objet tenu en main : chaque pixel visible devient un petit cube.
     * Seules les faces extérieures sont produites (les faces entre deux pixels pleins sont inutiles).
     * [pixels] en ARGB, ligne par ligne. Les icônes plus grandes que 32×32 sont échantillonnées.
     */
    fun itemVoxels(pixels: IntArray, width: Int, height: Int): ColoredMesh {
        val step = maxOf(1, maxOf(width, height) / 32)
        val w = width / step; val h = height / step
        fun px(x: Int, y: Int): Int = if (x !in 0 until w || y !in 0 until h) 0 else pixels[(y * step) * width + x * step]
        fun solid(x: Int, y: Int) = (px(x, y) ushr 24) > 40
        val palette = LinkedHashMap<Int, Int>()
        val faces = ArrayList<MeshFace>()
        val scale = 16.0 / maxOf(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!solid(x, y)) continue
            val c = px(x, y) or (0xFF shl 24)
            val ci = palette.getOrPut(c) { palette.size }
            val a = Vec3((x - w / 2.0) * scale, (h - y - 1) * scale, -0.5 * scale)
            val b = a + Vec3(scale, scale, scale)
            val neighbours = mapOf(
                Face.NORTH to false, Face.SOUTH to false,
                Face.EAST to solid(x + 1, y), Face.WEST to solid(x - 1, y),
                Face.UP to solid(x, y - 1), Face.DOWN to solid(x, y + 1),
            )
            for ((face, corners) in cubeCorners(a, b)) {
                if (neighbours[face] == true) continue
                val e1 = corners[1] - corners[0]; val e2 = corners[3] - corners[0]
                val n = Vec3(e2.y * e1.z - e2.z * e1.y, e2.z * e1.x - e2.x * e1.z, e2.x * e1.y - e2.y * e1.x)
                faces += MeshFace(0, ci, face, corners, listOf(0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0), n)
            }
        }
        return ColoredMesh(faces, palette.keys.toIntArray())
    }

    /** Coins de chaque face d'une boîte, dans le même ordre que [com.bedrockmodstudio.core.model3d.MeshBuilder]. */
    fun cubeCorners(a: Vec3, b: Vec3): Map<Face, List<Vec3>> = mapOf(
        Face.NORTH to listOf(Vec3(b.x, b.y, a.z), Vec3(a.x, b.y, a.z), Vec3(a.x, a.y, a.z), Vec3(b.x, a.y, a.z)),
        Face.SOUTH to listOf(Vec3(a.x, b.y, b.z), Vec3(b.x, b.y, b.z), Vec3(b.x, a.y, b.z), Vec3(a.x, a.y, b.z)),
        Face.EAST to listOf(Vec3(b.x, b.y, b.z), Vec3(b.x, b.y, a.z), Vec3(b.x, a.y, a.z), Vec3(b.x, a.y, b.z)),
        Face.WEST to listOf(Vec3(a.x, b.y, a.z), Vec3(a.x, b.y, b.z), Vec3(a.x, a.y, b.z), Vec3(a.x, a.y, a.z)),
        Face.UP to listOf(Vec3(a.x, b.y, a.z), Vec3(b.x, b.y, a.z), Vec3(b.x, b.y, b.z), Vec3(a.x, b.y, b.z)),
        Face.DOWN to listOf(Vec3(a.x, a.y, b.z), Vec3(b.x, a.y, b.z), Vec3(b.x, a.y, a.z), Vec3(a.x, a.y, a.z)),
    )
}
