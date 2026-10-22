package com.bedrockmodstudio.core.model3d

import com.bedrockmodstudio.core.image.PixelImage

/** Usage d'un modèle : créature (entité) ou bloc (limité au volume d'un bloc). */
enum class ModelUse(val labelFr: String, val folder: String) {
    CREATURE("Créature", "entity"),
    BLOCK("Bloc 3D", "blocks"),
}

/**
 * Modèles de départ originaux. Chaque cube a sa couleur ; les UV « boîte » sont rangés automatiquement
 * dans une texture générée (que l'on peut ensuite redessiner dans l'éditeur de textures).
 */
enum class ModelTemplate(val labelFr: String, val use: ModelUse) {
    CUBE("Cube simple", ModelUse.CREATURE),
    HUMANOID("Humanoïde", ModelUse.CREATURE),
    QUADRUPED("Quadrupède", ModelUse.CREATURE),
    SLIME("Gelée", ModelUse.CREATURE),
    PEDESTAL("Piédestal", ModelUse.BLOCK),
    LANTERN("Lanterne", ModelUse.BLOCK),
    TABLE("Table", ModelUse.BLOCK),
    MUSHROOM("Champignon", ModelUse.BLOCK),
    ;

    /** Pièce d'un modèle : os, parent, pivot, puis cubes (origine, taille, couleur). */
    private class Part(val bone: String, val parent: String?, val pivot: Vec3, val cubes: List<Triple<Vec3, Vec3, Int>>)

    private fun v(x: Int, y: Int, z: Int) = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
    private fun c(o: Vec3, s: Vec3, color: Long) = Triple(o, s, color.toInt())

    private fun parts(main: Long, accent: Long): List<Part> = when (this) {
        CUBE -> listOf(Part("root", null, v(0, 0, 0), emptyList()), Part("body", "root", v(0, 8, 0), listOf(c(v(-4, 0, -4), v(8, 8, 8), main))))
        HUMANOID -> listOf(
            Part("root", null, v(0, 0, 0), emptyList()),
            Part("body", "root", v(0, 24, 0), listOf(c(v(-4, 12, -2), v(8, 12, 4), main))),
            Part("head", "body", v(0, 24, 0), listOf(c(v(-4, 24, -4), v(8, 8, 8), accent))),
            Part("right_arm", "body", v(-5, 22, 0), listOf(c(v(-8, 12, -2), v(4, 12, 4), main))),
            Part("left_arm", "body", v(5, 22, 0), listOf(c(v(4, 12, -2), v(4, 12, 4), main))),
            Part("right_leg", "root", v(-2, 12, 0), listOf(c(v(-4, 0, -2), v(4, 12, 4), accent))),
            Part("left_leg", "root", v(2, 12, 0), listOf(c(v(0, 0, -2), v(4, 12, 4), accent))),
        )
        QUADRUPED -> listOf(
            Part("root", null, v(0, 0, 0), emptyList()),
            Part("body", "root", v(0, 10, 0), listOf(c(v(-5, 6, -8), v(10, 8, 16), main))),
            Part("head", "body", v(0, 12, -8), listOf(c(v(-4, 8, -15), v(8, 8, 7), accent))),
            Part("leg_front_right", "root", v(-3, 6, -5), listOf(c(v(-5, 0, -7), v(4, 6, 4), accent))),
            Part("leg_front_left", "root", v(3, 6, -5), listOf(c(v(1, 0, -7), v(4, 6, 4), accent))),
            Part("leg_back_right", "root", v(-3, 6, 5), listOf(c(v(-5, 0, 3), v(4, 6, 4), accent))),
            Part("leg_back_left", "root", v(3, 6, 5), listOf(c(v(1, 0, 3), v(4, 6, 4), accent))),
        )
        SLIME -> listOf(
            Part("root", null, v(0, 0, 0), emptyList()),
            Part("body", "root", v(0, 0, 0), listOf(c(v(-6, 0, -6), v(12, 12, 12), main))),
            Part("eyes", "body", v(0, 8, 0), listOf(c(v(-4, 7, -7), v(2, 2, 1), 0xFF15181D), c(v(2, 7, -7), v(2, 2, 1), 0xFF15181D))),
        )
        PEDESTAL -> listOf(Part("bloc", null, v(0, 0, 0), listOf(
            c(v(-7, 0, -7), v(14, 3, 14), accent), c(v(-4, 3, -4), v(8, 9, 8), main), c(v(-6, 12, -6), v(12, 3, 12), accent),
        )))
        LANTERN -> listOf(Part("bloc", null, v(0, 0, 0), listOf(
            c(v(-5, 0, -5), v(10, 2, 10), accent), c(v(-4, 2, -4), v(8, 8, 8), main), c(v(-5, 10, -5), v(10, 2, 10), accent), c(v(-1, 12, -1), v(2, 3, 2), accent),
        )))
        TABLE -> listOf(Part("bloc", null, v(0, 0, 0), listOf(
            c(v(-8, 13, -8), v(16, 3, 16), main),
            c(v(-7, 0, -7), v(2, 13, 2), accent), c(v(5, 0, -7), v(2, 13, 2), accent), c(v(-7, 0, 5), v(2, 13, 2), accent), c(v(5, 0, 5), v(2, 13, 2), accent),
        )))
        MUSHROOM -> listOf(Part("bloc", null, v(0, 0, 0), listOf(
            c(v(-2, 0, -2), v(4, 8, 4), 0xFFE8E4D8), c(v(-6, 8, -6), v(12, 5, 12), main), c(v(-4, 13, -4), v(8, 2, 8), main),
        )))
    }

    /** Construit le modèle et sa texture originale (couleurs principale et secondaire au choix). */
    fun build(identifier: String, mainColor: Int = 0xFF8FA3B8.toInt(), accentColor: Int = 0xFF5D6D7E.toInt()): Pair<GeoModel, PixelImage> {
        val ps = parts(mainColor.toLong() and 0xFFFFFFFF, accentColor.toLong() and 0xFFFFFFFF)
        val all = ps.flatMap { p -> p.cubes.map { it } }
        val rects = UvPacker.pack(all.map { (_, s, _) -> UvPacker.boxSize(s) })
        val tw = rects.texWidth; val th = rects.texHeight
        val img = PixelImage(tw, th)
        var k = 0
        val bones = ps.map { p ->
            Bone(p.bone, p.parent, p.pivot, cubes = p.cubes.map { (o, s, color) ->
                val (u, vv) = rects.positions[k]
                paintBox(img, u, vv, s, color)
                k++
                Cube(o, s, u.toDouble() to vv.toDouble())
            })
        }
        return GeoModel(identifier, tw, th, bones,
            visibleBoundsWidth = if (use == ModelUse.BLOCK) 1.0 else 2.0, visibleBoundsHeight = if (use == ModelUse.BLOCK) 1.0 else 2.5,
            visibleBoundsOffset = if (use == ModelUse.BLOCK) Vec3(0.0, 0.5, 0.0) else Vec3(0.0, 1.0, 0.0)) to img
    }

    /** Peint le patron « boîte » d'un cube : une teinte par face pour donner du relief, bord plus sombre. */
    private fun paintBox(img: PixelImage, u: Int, v: Int, s: Vec3, color: Int) {
        val rects = MeshBuilder.boxUv(u.toDouble(), v.toDouble(), Math.floor(s.x), Math.floor(s.y), Math.floor(s.z))
        for ((face, r) in rects) {
            val shade = when (face) { Face.UP -> 1.15; Face.DOWN -> 0.7; Face.NORTH, Face.SOUTH -> 1.0; else -> 0.85 }
            val x0 = r[0].toInt(); val y0 = r[1].toInt(); val w = r[2].toInt(); val h = r[3].toInt()
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
                if (!img.inside(x, y)) continue
                val edge = x == x0 || y == y0 || x == x0 + w - 1 || y == y0 + h - 1
                val noise = (((x * 73856093) xor (y * 19349663)) and 7) - 3
                img[x, y] = tint(color, shade * (if (edge) 0.8 else 1.0), noise)
            }
        }
    }

    private fun tint(c: Int, f: Double, noise: Int): Int {
        fun ch(sh: Int) = (((c shr sh) and 0xFF) * f + noise).toInt().coerceIn(0, 255) shl sh
        return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    companion object {
        fun forUse(use: ModelUse) = entries.filter { it.use == use }
    }
}

/** Rangement des patrons « boîte » dans une texture (rangées successives), sans chevauchement. */
object UvPacker {
    class Result(val positions: List<Pair<Int, Int>>, val texWidth: Int, val texHeight: Int)

    /** Largeur × hauteur du patron d'un cube (w, h, d) : 2(d + w) × (d + h). */
    fun boxSize(s: Vec3): Pair<Int, Int> {
        val w = Math.floor(s.x).toInt(); val h = Math.floor(s.y).toInt(); val d = Math.floor(s.z).toInt()
        return (2 * (d + w)).coerceAtLeast(1) to (d + h).coerceAtLeast(1)
    }

    fun pack(sizes: List<Pair<Int, Int>>): Result {
        var width = 16
        while (width < (sizes.maxOfOrNull { it.first } ?: 1)) width *= 2
        while (true) {
            val pos = ArrayList<Pair<Int, Int>>()
            var x = 0; var y = 0; var rowH = 0
            // Les plus hauts d'abord pour des rangées compactes ; l'ordre d'origine est rétabli à la fin.
            val order = sizes.indices.sortedByDescending { sizes[it].second }
            val placed = arrayOfNulls<Pair<Int, Int>>(sizes.size)
            for (i in order) {
                val (w, h) = sizes[i]
                if (x + w > width) { x = 0; y += rowH; rowH = 0 }
                placed[i] = x to y
                x += w; rowH = maxOf(rowH, h)
            }
            val used = y + rowH
            var height = 16
            while (height < used) height *= 2
            // Texture à peu près carrée (format habituel de Minecraft).
            if (height <= width * 2 || width >= 1024) {
                pos += placed.map { it!! }
                return Result(pos, width, height)
            }
            width *= 2
        }
    }
}
