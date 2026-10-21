package com.bedrockmodstudio.core.structure

import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.arr
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.nbt.Nbt

/** Modèles de départ pour une nouvelle structure (blocs vanilla, sans état complexe). */
enum class StructurePreset(val labelFr: String, val explanationFr: String) {
    EMPTY("Vide", "Une zone vide de 8 × 6 × 8 à remplir soi-même."),
    HOUSE("Petite maison", "Cabane en planches et rondins, fenêtres en verre, ouverture pour la porte."),
    TOWER("Tour de guet", "Tour de pierre de 4 étages avec créneaux et lanterne."),
    DUNGEON("Salle secrète", "Pièce en pierre moussue avec un coffre à butin, faite pour être enterrée."),
    ARCH("Arche de portail", "Cadre en obsidienne pleureuse, prêt à recevoir un bloc de portail du projet."),
    ;

    fun build(): StructureTemplate = when (this) {
        EMPTY -> StructureTemplate(8, 6, 8)
        HOUSE -> house()
        TOWER -> tower()
        DUNGEON -> dungeon(null)
        ARCH -> arch()
    }

    companion object {
        val AIR = BlockState(StructureTemplate.AIR)
        val PLANKS = BlockState("minecraft:oak_planks")
        val LOG = BlockState("minecraft:oak_log", Nbt.compound("pillar_axis" to Nbt.Str("y")))
        val COBBLE = BlockState("minecraft:cobblestone")
        val MOSSY = BlockState("minecraft:mossy_cobblestone")
        val BRICKS = BlockState("minecraft:stone_bricks")
        val GLASS = BlockState("minecraft:glass")
        val LANTERN = BlockState("minecraft:sea_lantern")
        val CRYING = BlockState("minecraft:crying_obsidian")

        private fun house() = StructureTemplate(7, 6, 7).apply {
            fill(0, 0, 0, 6, 0, 6, COBBLE)
            fill(0, 1, 0, 6, 3, 6, PLANKS)
            fill(1, 1, 1, 5, 3, 5, AIR)
            for ((x, z) in listOf(0 to 0, 6 to 0, 0 to 6, 6 to 6)) fill(x, 1, z, x, 3, z, LOG)
            fill(3, 1, 0, 3, 2, 0, AIR) // ouverture de porte
            set(1, 2, 0, GLASS); set(5, 2, 0, GLASS); set(0, 2, 3, GLASS); set(6, 2, 3, GLASS); set(3, 2, 6, GLASS)
            fill(0, 4, 0, 6, 4, 6, PLANKS)
            fill(1, 5, 1, 5, 5, 5, PLANKS)
            set(3, 3, 3, LANTERN)
        }

        private fun tower() = StructureTemplate(5, 14, 5).apply {
            fill(0, 0, 0, 4, 11, 4, BRICKS)
            fill(1, 1, 1, 3, 11, 3, AIR)
            fill(2, 1, 0, 2, 2, 0, AIR)
            for (y in listOf(4, 7, 10)) { fill(1, y - 1, 1, 3, y - 1, 3, PLANKS); set(2, y - 1, 2, AIR); set(0, y, 2, GLASS); set(4, y, 2, GLASS) }
            fill(0, 12, 0, 4, 12, 4, BRICKS)
            for ((x, z) in listOf(0 to 0, 2 to 0, 4 to 0, 0 to 2, 4 to 2, 0 to 4, 2 to 4, 4 to 4)) set(x, 13, z, BRICKS)
            set(2, 13, 2, LANTERN)
        }

        /** Salle avec coffre ; [lootTable] = chemin dans le pack de comportement (ex. loot_tables/bms/tresor.json). */
        fun dungeon(lootTable: String?) = StructureTemplate(7, 5, 7).apply {
            fill(0, 0, 0, 6, 4, 6, COBBLE)
            fill(1, 1, 1, 5, 3, 5, AIR)
            for (i in 0..6 step 2) { set(i, 0, 3, MOSSY); set(3, 4, i, MOSSY); set(0, 2, i, MOSSY) }
            set(3, 3, 3, LANTERN)
            setChest(3, 1, 4, lootTable, facing = "north")
        }

        private fun arch() = StructureTemplate(4, 5, 1).apply {
            fill(0, 0, 0, 3, 4, 0, CRYING)
            fill(1, 1, 0, 2, 3, 0, AIR)
        }
    }
}

/** Placement naturel lors de la génération de nouveaux tronçons du monde. */
data class NaturalSpawn(
    /** Étiquettes de biome Bedrock (ex. plains, forest, desert). Vide = tous les biomes de la passe. */
    val biomeTags: List<String> = listOf("plains"),
    /** Une chance sur N par tronçon de 16 × 16. */
    val oneIn: Int = 40,
    val buried: Boolean = false,
)

data class StructureSpec(
    val displayName: String,
    val shortId: String = Identifiers.slug(displayName).replace('.', '_').ifEmpty { "structure" },
    val natural: NaturalSpawn? = null,
    /** Contenu du coffre à butin (objet → quantité max) ; null = pas de table de butin générée. */
    val chestLoot: List<Pair<String, Int>>? = null,
) {
    fun validate(): List<String> = buildList {
        if (displayName.isBlank()) add("Le nom de la structure est vide.")
        if (!Regex("^[a-z0-9_]{1,40}$").matches(shortId)) add("L'identifiant « $shortId » ne doit contenir que des minuscules, chiffres et _ (40 au maximum).")
        natural?.let { n ->
            if (n.oneIn !in 1..10_000) add("La rareté doit être comprise entre 1 et 10 000.")
            n.biomeTags.filterNot { Regex("^[a-z0-9_]+$").matches(it) }.forEach { add("Étiquette de biome invalide « $it ».") }
        }
        chestLoot?.forEach { (item, n) ->
            if (!Identifiers.isValidIdentifier(item) && !(item.startsWith("minecraft:") && Identifiers.isValidName(item.removePrefix("minecraft:")))) add("Objet de butin invalide « $item ».")
            if (n !in 1..64) add("Quantité de butin pour $item : entre 1 et 64.")
        }
    }
}

/**
 * Fichiers d'une structure :
 * - `structures/<espace>/<nom>.mcstructure` → identifiant « <espace>:<nom> » (commande /structure, scripts) ;
 * - placement naturel facultatif : `features/<nom>_feature.json` (minecraft:structure_template_feature)
 *   et `feature_rules/<nom>_rule.json` (minecraft:feature_rules, format 1.13.0) ;
 * - table de butin facultative pour le coffre : `loot_tables/<espace>/<nom>_coffre.json`.
 * Sources : https://learn.microsoft.com/minecraft/creator/reference/content/featuresreference/ et https://wiki.bedrock.dev/nbt/mcstructure
 */
object StructureGenerator {
    fun structurePath(ns: String, shortId: String) = "$BP/structures/$ns/$shortId.mcstructure"
    fun lootPath(ns: String, shortId: String) = "loot_tables/$ns/${shortId}_coffre.json"

    /** Identifiant d'une structure d'après son chemin dans le pack (dossier racine = espace « mystructure »). */
    fun idForPath(path: String): String? {
        val rel = path.removePrefix("$BP/").takeIf { it.startsWith("structures/") && it.endsWith(".mcstructure") } ?: return null
        val parts = rel.removePrefix("structures/").removeSuffix(".mcstructure").split('/')
        return if (parts.size == 1) "mystructure:${parts[0]}" else "${parts.first()}:${parts.drop(1).joinToString("/")}"
    }

    fun generate(ns: String, spec: StructureSpec, template: StructureTemplate): GeneratedContent {
        val errors = spec.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val c = GeneratedContent()
        val loot = spec.chestLoot
        if (loot != null) {
            val path = lootPath(ns, spec.shortId)
            for (i in template.primary.indices) {
                if (template.palette.getOrNull(template.primary[i])?.name == "minecraft:chest") {
                    val x = i / (template.sizeY * template.sizeZ); val y = (i / template.sizeZ) % template.sizeY; val z = i % template.sizeZ
                    val facing = (template[x, y, z]?.states?.get("minecraft:cardinal_direction") as? Nbt.Str)?.v ?: "south"
                    template.setChest(x, y, z, path, facing)
                }
            }
            c.text("$BP/$path", JsonWriter.write(obj("pools" to arr(
                obj("rolls" to obj("min" to 2, "max" to 5), "entries" to loot.map { (item, n) ->
                    obj("type" to "item", "name" to item, "weight" to 10, "functions" to arr(obj("function" to "set_count", "count" to obj("min" to 1, "max" to n))))
                }),
            ))))
        }
        c.bytes(structurePath(ns, spec.shortId), StructureCodec.write(template))
        c.lang["bms.structure.${spec.shortId}"] = spec.displayName
        spec.natural?.let { n -> naturalFiles(c, ns, spec.shortId, n) }
        return c
    }

    fun naturalFiles(c: GeneratedContent, ns: String, shortId: String, n: NaturalSpawn) {
        val feature = "${shortId}_feature"
        val rule = "${shortId}_rule"
        val constraints = if (n.buried) obj() else obj("grounded" to obj(), "unburied" to obj())
        c.text("$BP/features/$feature.json", JsonWriter.write(obj(
            "format_version" to "1.13.0",
            "minecraft:structure_template_feature" to obj(
                "description" to obj("identifier" to "$ns:$feature"),
                "structure_name" to "$ns:$shortId",
                "adjustment_radius" to 4,
                "facing_direction" to "random",
                "constraints" to constraints,
            ),
        )))
        val conditions = obj("placement_pass" to if (n.buried) "underground_pass" else "surface_pass")
        if (n.biomeTags.isNotEmpty()) {
            val tests = n.biomeTags.map { obj("test" to "has_biome_tag", "operator" to "==", "value" to it) }
            conditions["minecraft:biome_filter"] = if (tests.size == 1) arr(tests[0]) else arr(obj("any_of" to arr(*tests.toTypedArray())))
        }
        c.text("$BP/feature_rules/$rule.json", JsonWriter.write(obj(
            "format_version" to "1.13.0",
            "minecraft:feature_rules" to obj(
                "description" to obj("identifier" to "$ns:$rule", "places_feature" to "$ns:$feature"),
                "conditions" to conditions,
                "distribution" to obj(
                    "iterations" to 1,
                    "scatter_chance" to obj("numerator" to 1, "denominator" to n.oneIn),
                    "x" to obj("extent" to arr(0, 15), "distribution" to "uniform"),
                    "y" to if (n.buried) obj("extent" to arr(-40, 40), "distribution" to "uniform") else "q.heightmap(v.worldx, v.worldz)",
                    "z" to obj("extent" to arr(0, 15), "distribution" to "uniform"),
                ),
            ),
        )))
    }
}
