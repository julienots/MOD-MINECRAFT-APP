package com.bedrockmodstudio.core.structure

import com.bedrockmodstudio.core.nbt.Nbt
import com.bedrockmodstudio.core.nbt.NbtCodec
import com.bedrockmodstudio.core.storage.UserFacingException

/** Un état de bloc de la palette (nom + états). */
data class BlockState(val name: String, val states: Nbt.Compound = Nbt.Compound(), val version: Int = StructureTemplate.BLOCK_VERSION)

/**
 * Structure Bedrock (.mcstructure). Ordre des blocs : index = (x × tailleY + y) × tailleZ + z
 * (z varie le plus vite), -1 = vide (le bloc existant du monde est conservé).
 * Source : https://wiki.bedrock.dev/nbt/mcstructure
 */
class StructureTemplate(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val primary: IntArray = IntArray(sizeX * sizeY * sizeZ) { -1 },
    /** Seconde couche (blocs « dans » le premier, ex. eau d'un bloc immergé) : conservée telle quelle. */
    val secondary: IntArray = IntArray(sizeX * sizeY * sizeZ) { -1 },
    val palette: MutableList<BlockState> = mutableListOf(),
    /** Données de bloc (coffres, etc.) par index de position, au format NBT. */
    val positionData: Nbt.Compound = Nbt.Compound(),
    /** Entités enregistrées dans la structure : conservées sans modification. */
    val entities: Nbt.ListTag = Nbt.ListTag(10, emptyList()),
    val origin: Triple<Int, Int, Int> = Triple(0, 0, 0),
) {
    init {
        require(sizeX in 1..MAX_SIZE && sizeY in 1..MAX_HEIGHT && sizeZ in 1..MAX_SIZE) {
            "Taille de structure invalide ($sizeX × $sizeY × $sizeZ ; maximum $MAX_SIZE × $MAX_HEIGHT × $MAX_SIZE)."
        }
        require(primary.size == volume && secondary.size == volume) { "Nombre de blocs incohérent." }
    }

    val volume get() = sizeX * sizeY * sizeZ

    companion object {
        const val MAX_SIZE = 64
        const val MAX_HEIGHT = 256
        /** Valeur de compatibilité des blocs relevée sur le wiki Bedrock pour 1.26.30 (0x01153C21 = 1.21.60.33). */
        const val BLOCK_VERSION = 18168865
        const val AIR = "minecraft:air"
    }

    fun inside(x: Int, y: Int, z: Int) = x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ
    fun index(x: Int, y: Int, z: Int) = (x * sizeY + y) * sizeZ + z

    /** Bloc à la position ; null si vide (-1). */
    operator fun get(x: Int, y: Int, z: Int): BlockState? = primary[index(x, y, z)].let { palette.getOrNull(it) }

    fun paletteIndex(state: BlockState): Int {
        val i = palette.indexOf(state)
        if (i >= 0) return i
        palette += state
        return palette.lastIndex
    }

    /** Pose un bloc (null = vide). Les données de bloc éventuelles de cette position sont retirées. */
    fun set(x: Int, y: Int, z: Int, state: BlockState?) {
        if (!inside(x, y, z)) return
        val i = index(x, y, z)
        primary[i] = state?.let(::paletteIndex) ?: -1
        positionData.map.remove(i.toString())
    }

    fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, state: BlockState?) {
        for (x in minOf(x0, x1)..maxOf(x0, x1)) for (y in minOf(y0, y1)..maxOf(y0, y1)) for (z in minOf(z0, z1)..maxOf(z0, z1)) set(x, y, z, state)
    }

    /** Coffre avec table de butin (champ LootTable du coffre : non vérifié en jeu). */
    fun setChest(x: Int, y: Int, z: Int, lootTable: String?, facing: String = "south") {
        set(x, y, z, BlockState("minecraft:chest", Nbt.compound("minecraft:cardinal_direction" to Nbt.Str(facing))))
        if (lootTable != null) {
            positionData[index(x, y, z).toString()] = Nbt.compound(
                "block_entity_data" to Nbt.compound(
                    "id" to Nbt.Str("Chest"),
                    "LootTable" to Nbt.Str(lootTable),
                    "isMovable" to Nbt.Byte(1),
                ),
            )
        }
    }

    /** Copie redimensionnée (les blocs hors des nouvelles limites sont perdus). */
    fun resized(nx: Int, ny: Int, nz: Int): StructureTemplate {
        val s = StructureTemplate(nx, ny, nz, palette = palette.toMutableList(), entities = entities, origin = origin)
        for (x in 0 until minOf(sizeX, nx)) for (y in 0 until minOf(sizeY, ny)) for (z in 0 until minOf(sizeZ, nz)) {
            s.primary[s.index(x, y, z)] = primary[index(x, y, z)]
            s.secondary[s.index(x, y, z)] = secondary[index(x, y, z)]
            positionData[index(x, y, z).toString()]?.let { s.positionData[s.index(x, y, z).toString()] = it }
        }
        return s
    }

    fun blockCount() = primary.count { it >= 0 && palette.getOrNull(it)?.name != AIR }
}

object StructureCodec {
    fun write(s: StructureTemplate): ByteArray {
        val paletteTag = Nbt.ListTag(10, s.palette.map { b ->
            Nbt.compound("name" to Nbt.Str(b.name), "states" to b.states, "version" to Nbt.Int(b.version))
        })
        val root = Nbt.compound(
            "format_version" to Nbt.Int(1),
            "size" to Nbt.intList(s.sizeX, s.sizeY, s.sizeZ),
            "structure" to Nbt.compound(
                "block_indices" to Nbt.ListTag(9, listOf(
                    Nbt.ListTag(3, s.primary.map { Nbt.Int(it) }),
                    Nbt.ListTag(3, s.secondary.map { Nbt.Int(it) }),
                )),
                "entities" to s.entities,
                "palette" to Nbt.compound("default" to Nbt.compound(
                    "block_palette" to paletteTag,
                    "block_position_data" to s.positionData,
                )),
            ),
            "structure_world_origin" to Nbt.intList(s.origin.first, s.origin.second, s.origin.third),
        )
        return NbtCodec.write(root)
    }

    fun read(bytes: ByteArray): StructureTemplate {
        val (_, root) = NbtCodec.read(bytes)
        fun bad(msg: String): Nothing = throw UserFacingException("Structure illisible : $msg")
        val size = (root["size"] as? Nbt.ListTag)?.items?.map { (it as? Nbt.Int)?.v ?: bad("taille invalide") } ?: bad("taille absente")
        if (size.size != 3) bad("taille invalide")
        val structure = root["structure"] as? Nbt.Compound ?: bad("section « structure » absente")
        val layers = (structure["block_indices"] as? Nbt.ListTag)?.items ?: bad("« block_indices » absent")
        if (layers.size != 2) bad("« block_indices » doit contenir deux couches")
        val volume = size[0] * size[1] * size[2]
        fun layer(t: Nbt): IntArray {
            val l = (t as? Nbt.ListTag)?.items ?: bad("couche de blocs invalide")
            if (l.size != volume) bad("la couche de blocs ne correspond pas à la taille")
            return IntArray(volume) { (l[it] as? Nbt.Int)?.v ?: 0 }
        }
        val def = ((structure["palette"] as? Nbt.Compound)?.get("default") as? Nbt.Compound) ?: Nbt.Compound()
        val palette = ((def["block_palette"] as? Nbt.ListTag)?.items ?: emptyList()).map { e ->
            val c = e as? Nbt.Compound ?: bad("entrée de palette invalide")
            BlockState((c["name"] as? Nbt.Str)?.v ?: bad("bloc sans nom"), c["states"] as? Nbt.Compound ?: Nbt.Compound(),
                (c["version"] as? Nbt.Int)?.v ?: StructureTemplate.BLOCK_VERSION)
        }.toMutableList()
        val origin = (root["structure_world_origin"] as? Nbt.ListTag)?.items?.map { (it as? Nbt.Int)?.v ?: 0 } ?: listOf(0, 0, 0)
        if (size.any { it !in 1..StructureTemplate.MAX_HEIGHT } || size[0] > StructureTemplate.MAX_SIZE || size[2] > StructureTemplate.MAX_SIZE) {
            bad("dimensions ${size.joinToString(" × ")} trop grandes pour l'éditeur (le fichier reste utilisable tel quel)")
        }
        return StructureTemplate(size[0], size[1], size[2], layer(layers[0]), layer(layers[1]), palette,
            def["block_position_data"] as? Nbt.Compound ?: Nbt.Compound(),
            structure["entities"] as? Nbt.ListTag ?: Nbt.ListTag(10, emptyList()),
            Triple(origin.getOrElse(0) { 0 }, origin.getOrElse(1) { 0 }, origin.getOrElse(2) { 0 }))
    }
}

/** Faces visibles d'une structure pour l'aperçu 3D (1 bloc = 1 unité ; les faces cachées par un voisin plein sont omises). */
object StructureMesh {
    /** Nombre maximal de blocs dessinés (le reste est ignoré dans l'aperçu, pas dans le fichier). */
    const val MAX_CUBES = 12_000

    class Result(val faces: List<com.bedrockmodstudio.core.model3d.MeshFace>, val blockIndex: IntArray, val truncated: Boolean)

    fun build(s: StructureTemplate): Result {
        fun solid(x: Int, y: Int, z: Int) = s.inside(x, y, z) && s.primary[s.index(x, y, z)].let { it >= 0 && s.palette.getOrNull(it)?.name != StructureTemplate.AIR }
        val cubes = ArrayList<com.bedrockmodstudio.core.model3d.Cube>()
        val indices = ArrayList<Int>()
        var truncated = false
        loop@ for (x in 0 until s.sizeX) for (y in 0 until s.sizeY) for (z in 0 until s.sizeZ) {
            if (!solid(x, y, z)) continue
            if (solid(x - 1, y, z) && solid(x + 1, y, z) && solid(x, y - 1, z) && solid(x, y + 1, z) && solid(x, y, z - 1) && solid(x, y, z + 1)) continue
            if (cubes.size >= MAX_CUBES) { truncated = true; break@loop }
            cubes += com.bedrockmodstudio.core.model3d.Cube(com.bedrockmodstudio.core.model3d.Vec3(x.toDouble(), y.toDouble(), z.toDouble()), com.bedrockmodstudio.core.model3d.Vec3(1.0, 1.0, 1.0))
            indices += s.index(x, y, z)
        }
        val model = com.bedrockmodstudio.core.model3d.GeoModel("geometry.structure", bones = listOf(com.bedrockmodstudio.core.model3d.Bone("blocs", cubes = cubes)))
        val faces = com.bedrockmodstudio.core.model3d.MeshBuilder.build(model).filter { f ->
            val c = cubes[f.cubeIndex].origin
            val x = c.x.toInt(); val y = c.y.toInt(); val z = c.z.toInt()
            !when (f.face) {
                com.bedrockmodstudio.core.model3d.Face.NORTH -> solid(x, y, z - 1)
                com.bedrockmodstudio.core.model3d.Face.SOUTH -> solid(x, y, z + 1)
                com.bedrockmodstudio.core.model3d.Face.EAST -> solid(x + 1, y, z)
                com.bedrockmodstudio.core.model3d.Face.WEST -> solid(x - 1, y, z)
                com.bedrockmodstudio.core.model3d.Face.UP -> solid(x, y + 1, z)
                com.bedrockmodstudio.core.model3d.Face.DOWN -> solid(x, y - 1, z)
            }
        }
        return Result(faces, indices.toIntArray(), truncated)
    }

    /** Couleur d'aperçu d'un bloc (approximative ; couleur stable dérivée du nom pour les blocs inconnus). */
    fun colorOf(name: String): Int {
        val n = name.substringAfter(':')
        val known = listOf(
            "air" to 0x00000000, "glass" to 0xFFBFE6F0, "water" to 0xFF3F76E4, "lava" to 0xFFE0661A, "chest" to 0xFFA0702A,
            "planks" to 0xFFA8834F, "log" to 0xFF6B5132, "wood" to 0xFF6B5132, "leaves" to 0xFF4A8F2C, "mossy" to 0xFF6E8A55,
            "cobblestone" to 0xFF8A8A8A, "stone_bricks" to 0xFF7B7B7B, "stone" to 0xFF8F8F8F, "deepslate" to 0xFF505055,
            "dirt" to 0xFF8B5E3C, "grass" to 0xFF6BA03E, "moss" to 0xFF5B8A2E, "sand" to 0xFFDCCF9C, "gravel" to 0xFF857F7C,
            "crying_obsidian" to 0xFF4B1B8A, "obsidian" to 0xFF1E1530, "lantern" to 0xFFFFE9A8, "glowstone" to 0xFFF0C060,
            "torch" to 0xFFFFC94A, "wool" to 0xFFE8E8E8, "brick" to 0xFF975A48, "quartz" to 0xFFECE6DF, "gold" to 0xFFF5D547,
            "iron" to 0xFFD8D8D8, "diamond" to 0xFF62D9D1, "emerald" to 0xFF3AC56A, "ice" to 0xFF9CC2F5, "snow" to 0xFFF5FBFB,
            "netherrack" to 0xFF6E2B2B, "end_stone" to 0xFFDCDF9E, "terracotta" to 0xFFA0644A, "concrete" to 0xFF9E9E9E,
        )
        known.firstOrNull { n.contains(it.first) }?.let { return it.second.toInt() }
        val h = name.hashCode()
        return (0xFF shl 24) or ((0x50 + (h and 0x7F)) shl 16) or ((0x50 + ((h shr 8) and 0x7F)) shl 8) or (0x50 + ((h shr 16) and 0x7F))
    }
}
