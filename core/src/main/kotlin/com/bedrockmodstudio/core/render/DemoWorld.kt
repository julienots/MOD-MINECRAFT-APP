package com.bedrockmodstudio.core.render

import com.bedrockmodstudio.core.image.PixelCanvas
import com.bedrockmodstudio.core.model3d.Face
import com.bedrockmodstudio.core.model3d.Mat4
import com.bedrockmodstudio.core.model3d.MeshFace
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.core.mods.PreviewMeshes
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Petit monde de démonstration pour la vitrine 3D : une île de blocs (herbe, terre, pierre, sable, arbres)
 * avec un plateau plat au centre où sont posés les contenus du mod. Textures 16×16 dessinées ici (originales).
 * Unités : pixels de modèle (1 bloc = 16).
 */
object DemoWorld {
    const val TILE = 16
    enum class Tile { GRASS_TOP, GRASS_SIDE, DIRT, STONE, SAND, LOG_SIDE, LOG_TOP, LEAVES }
    const val ATLAS_WIDTH = TILE * 8
    const val ATLAS_HEIGHT = TILE

    class World(val faces: List<MeshFace>, val plateauTop: Double, val plateauRadius: Double, val radius: Double)

    /** Atlas 128×16 : une case par [Tile]. */
    fun atlas(): PixelCanvas {
        val c = PixelCanvas(ATLAS_WIDTH, ATLAS_HEIGHT, 0)
        fun tile(t: Tile, draw: (x0: Int) -> Unit) = draw(t.ordinal * TILE)
        val grass = 0xFF5FA03A.toInt(); val dirt = 0xFF866043.toInt(); val stone = 0xFF8A8A8A.toInt()
        tile(Tile.GRASS_TOP) { x0 -> c.noiseRect(x0, 0, TILE, TILE, grass, 11, 18) }
        tile(Tile.DIRT) { x0 -> c.noiseRect(x0, 0, TILE, TILE, dirt, 12, 16) }
        tile(Tile.GRASS_SIDE) { x0 ->
            c.noiseRect(x0, 0, TILE, TILE, dirt, 13, 16)
            for (x in 0 until TILE) {
                val h = 3 + ((x * 7 + 3) % 4) / 2
                c.noiseRect(x0 + x, 0, 1, h, grass, 14 + x, 18)
            }
        }
        tile(Tile.STONE) { x0 ->
            c.noiseRect(x0, 0, TILE, TILE, stone, 15, 14)
            for ((x, y) in listOf(3 to 4, 10 to 2, 7 to 11, 12 to 9)) c.fillRect(x0 + x, y, 2, 1, PixelCanvas.shade(stone, -30))
        }
        tile(Tile.SAND) { x0 -> c.noiseRect(x0, 0, TILE, TILE, 0xFFDCCF94.toInt(), 16, 10) }
        tile(Tile.LOG_SIDE) { x0 ->
            c.noiseRect(x0, 0, TILE, TILE, 0xFF6B5133.toInt(), 17, 10)
            for (x in listOf(2, 6, 11, 14)) c.fillRect(x0 + x, 0, 1, TILE, 0xFF4E3A24.toInt())
        }
        tile(Tile.LOG_TOP) { x0 ->
            c.noiseRect(x0, 0, TILE, TILE, 0xFFB38D5A.toInt(), 18, 10)
            for (r in listOf(2, 5)) for (i in r until TILE - r) {
                c[x0 + i, r] = 0xFF8C6B40.toInt(); c[x0 + i, TILE - 1 - r] = 0xFF8C6B40.toInt()
                c[x0 + r, i] = 0xFF8C6B40.toInt(); c[x0 + TILE - 1 - r, i] = 0xFF8C6B40.toInt()
            }
        }
        tile(Tile.LEAVES) { x0 ->
            c.noiseRect(x0, 0, TILE, TILE, 0xFF3C7A2C.toInt(), 19, 22)
            var r = 99
            repeat(34) { r = r * 1103515245 + 12345; c[x0 + ((r ushr 8) and 15), (r ushr 16) and 15] = 0 } // trous découpés
        }
        return c
    }

    private fun noise(x: Int, z: Int, seed: Int): Double {
        var h = x * 374761393 + z * 668265263 + seed * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
    }

    private fun smooth(x: Double, z: Double, seed: Int): Double {
        val x0 = floor(x).toInt(); val z0 = floor(z).toInt()
        val fx = x - x0; val fz = z - z0
        fun s(t: Double) = t * t * (3 - 2 * t)
        val a = noise(x0, z0, seed); val b = noise(x0 + 1, z0, seed); val c = noise(x0, z0 + 1, seed); val d = noise(x0 + 1, z0 + 1, seed)
        return (a + (b - a) * s(fx)) + ((c + (d - c) * s(fx)) - (a + (b - a) * s(fx))) * s(fz)
    }

    /**
     * Construit l'île. [plateau] : rayon (en blocs) de la zone plate du centre, assez grande pour les contenus.
     * Seules les faces visibles (au contact de l'air) sont produites.
     */
    fun build(plateau: Int, seed: Int = 7): World {
        val radius = plateau + 6
        val base = 3 // hauteur du plateau, en blocs au-dessus du fond
        val height = HashMap<Long, Int>()
        fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        for (x in -radius..radius) for (z in -radius..radius) {
            val d = sqrt((x * x + z * z).toDouble())
            if (d > radius + 0.5) continue
            val edge = ((radius - d) / 2.5).coerceIn(0.0, 1.0)
            val hills = if (d <= plateau + 0.5) 0 else (smooth(x / 4.0, z / 4.0, seed) * 4 * ((d - plateau) / (radius - plateau)).coerceIn(0.0, 1.0)).toInt()
            val h = max(1, ((base + hills) * edge).toInt())
            height[key(x, z)] = if (d <= plateau + 0.5) base else h
        }
        val solid = HashMap<Long, MutableMap<Int, Tile>>() // colonne → (y → matière dominante)
        for ((k, h) in height) {
            val col = HashMap<Int, Tile>()
            val x = (k shr 32).toInt(); val z = k.toInt()
            val d = sqrt((x * x + z * z).toDouble())
            val beach = d > radius - 1.6
            for (y in 0 until h) col[y] = when {
                y == h - 1 -> if (beach) Tile.SAND else Tile.GRASS_TOP
                y >= h - 3 -> if (beach) Tile.SAND else Tile.DIRT
                else -> Tile.STONE
            }
            solid[k] = col
        }
        // Quelques arbres dans les collines.
        val trees = ArrayList<Triple<Int, Int, Int>>()
        for ((k, h) in height) {
            val x = (k shr 32).toInt(); val z = k.toInt()
            val d = sqrt((x * x + z * z).toDouble())
            if (d > plateau + 3 && d < radius - 2.5 && noise(x, z, seed + 5) > 0.975) trees += Triple(x, h, z)
        }
        for ((x, h, z) in trees) {
            for (y in h until h + 4) solid.getOrPut(key(x, z)) { HashMap() }[y] = Tile.LOG_SIDE
            for (dx in -2..2) for (dz in -2..2) for (dy in 2..4) {
                if (dx * dx + dz * dz + (dy - 3) * (dy - 3) > 5) continue
                val col = solid.getOrPut(key(x + dx, z + dz)) { HashMap() }
                if (col[h + dy] == null) col[h + dy] = Tile.LEAVES
            }
        }
        fun at(x: Int, y: Int, z: Int): Tile? = solid[key(x, z)]?.get(y)
        fun opaque(t: Tile?) = t != null && t != Tile.LEAVES

        val faces = ArrayList<MeshFace>()
        val s = TILE.toDouble()
        val yOffset = -base * s // le dessus du plateau est à y = 0
        for ((k, col) in solid) {
            val x = (k shr 32).toInt(); val z = k.toInt()
            for ((y, t) in col) {
                val a = Vec3(x * s - 8, y * s + yOffset, z * s - 8)
                val b = a + Vec3(s, s, s)
                val corners = PreviewMeshes.cubeCorners(a, b)
                for ((face, pts) in corners) {
                    val nb = when (face) {
                        Face.UP -> at(x, y + 1, z); Face.DOWN -> at(x, y - 1, z)
                        Face.EAST -> at(x + 1, y, z); Face.WEST -> at(x - 1, y, z)
                        Face.SOUTH -> at(x, y, z + 1); Face.NORTH -> at(x, y, z - 1)
                    }
                    if (opaque(nb) || (t == Tile.LEAVES && nb == Tile.LEAVES)) continue
                    val tile = when (t) {
                        Tile.GRASS_TOP -> when (face) { Face.UP -> Tile.GRASS_TOP; Face.DOWN -> Tile.DIRT; else -> Tile.GRASS_SIDE }
                        Tile.LOG_SIDE -> if (face == Face.UP || face == Face.DOWN) Tile.LOG_TOP else Tile.LOG_SIDE
                        else -> t
                    }
                    val u0 = tile.ordinal * s
                    val uvs = listOf(u0 + s to 0.0, u0 to 0.0, u0 to s, u0 + s to s)
                    val e1 = pts[1] - pts[0]; val e2 = pts[3] - pts[0]
                    faces += MeshFace(-1, tile.ordinal, face, pts, uvs, RenderMath.cross(e2, e1))
                }
            }
        }
        return World(faces, 0.0, plateau * s, radius * s)
    }

    /**
     * Emplacements des contenus sur le plateau : une grille centrée, espacée de [spacing] pixels.
     * Renvoie une matrice par contenu, qui pose le bas de sa boîte englobante sur le plateau, centrée.
     */
    fun placements(bounds: List<Bounds>, spacing: Double): List<Mat4> {
        val n = bounds.size
        if (n == 0) return emptyList()
        val cols = kotlin.math.ceil(sqrt(n.toDouble())).toInt()
        val rows = (n + cols - 1) / cols
        return bounds.mapIndexed { i, b ->
            val cx = (i % cols - (cols - 1) / 2.0) * spacing
            val cz = (i / cols - (rows - 1) / 2.0) * spacing
            val c = b.center
            Mat4.translate(Vec3(cx - c.x, -b.min.y, cz - c.z))
        }
    }

    /** Rayon du plateau (en blocs) qui accueille [n] contenus espacés de [spacing] pixels. */
    fun plateauFor(n: Int, spacing: Double): Int {
        val cols = kotlin.math.ceil(sqrt(max(1, n).toDouble()))
        return max(4, kotlin.math.ceil(cols * spacing / TILE * 0.75 + 1).toInt())
    }
}
