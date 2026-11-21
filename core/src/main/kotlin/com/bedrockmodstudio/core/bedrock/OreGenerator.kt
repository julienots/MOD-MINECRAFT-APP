package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers

/**
 * Monde où le minerai apparaît. [biomeTag] : étiquette portée par les biomes vanilla (vérifiée dans
 * Mojang/bedrock-samples v1.26.60.5 : 73 biomes « overworld », 5 « nether », 1 « the_end »).
 */
enum class OreWorld(val labelFr: String, val biomeTag: String, val replaces: List<String>, val baseColor: Int, val minY: Int, val maxY: Int) {
    OVERWORLD("Monde normal", "overworld",
        listOf("minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff"),
        0xFF7F7F7F.toInt(), -64, 320),
    NETHER("Nether", "nether", listOf("minecraft:netherrack"), 0xFF6E3533.toInt(), 0, 128),
    END("End", "the_end", listOf("minecraft:end_stone"), 0xFFDDDF9E.toInt(), 0, 256),
}

/** Ce que donne le minerai quand on le casse. */
enum class OreDrop(val labelFr: String) {
    SELF("Le bloc de minerai (à faire cuire au four)"),
    GEM("Une gemme (comme le diamant)"),
}

data class OreSpec(
    val displayName: String,
    val shortId: String = Identifiers.slug(displayName).replace('.', '_').ifEmpty { "minerai" },
    val world: OreWorld = OreWorld.OVERWORLD,
    /** Couleur des éclats de minerai. */
    val oreColor: Int = 0xFF26C6DA.toInt(),
    /** Couleur de la roche autour (par défaut celle du monde). */
    val stoneColor: Int = world.baseColor,
    /** Nombre maximal de blocs par filon (le diamant vanilla en a environ 8). */
    val veinSize: Int = 8,
    /** Filons tentés par tronçon de 16 × 16 blocs. */
    val veinsPerChunk: Int = 4,
    val minY: Int = -48,
    val maxY: Int = 16,
    val hardness: Double = 3.0,
    val drop: OreDrop = OreDrop.GEM,
    /** Nom de la gemme ou du lingot obtenu. */
    val resultName: String = "",
    val resultShortId: String = Identifiers.slug(resultName).replace('.', '_').ifEmpty { "${shortId}_gemme" },
    val dropMin: Int = 1,
    val dropMax: Int = 1,
    /** Ajouter une recette de cuisson minerai → résultat (four et haut fourneau). */
    val smelting: Boolean = true,
) {
    /** Identifiant du bloc : se termine toujours par `_ore`, comme les minerais vanilla. */
    val oreBlockId get() = if (shortId.endsWith("_ore")) shortId else "${shortId}_ore"

    fun validate(): List<String> = buildList {
        if (displayName.isBlank()) add("Le nom affiché est vide.")
        if (!Identifiers.isValidName(shortId)) add("L'identifiant « $shortId » ne doit contenir que des minuscules, chiffres et _.")
        if (veinSize !in 1..64) add("La taille d'un filon doit être comprise entre 1 et 64 blocs.")
        if (veinsPerChunk !in 1..64) add("Le nombre de filons doit être compris entre 1 et 64 par tronçon.")
        if (minY > maxY) add("La hauteur minimale doit être inférieure à la hauteur maximale.")
        if (minY < world.minY || maxY > world.maxY) add("Dans ${world.labelFr}, les hauteurs vont de ${world.minY} à ${world.maxY}.")
        if (hardness !in 0.0..100.0) add("La dureté doit être comprise entre 0 et 100 secondes.")
        if (resultName.isBlank()) add("Donne un nom à la gemme ou au lingot obtenu.")
        if (!Identifiers.isValidName(resultShortId)) add("L'identifiant « $resultShortId » ne doit contenir que des minuscules, chiffres et _.")
        if (resultShortId == oreBlockId) add("La gemme et le minerai doivent avoir des identifiants différents.")
        if (dropMin < 1 || dropMax < dropMin || dropMax > 64) add("Quantité laissée invalide (1 à 64).")
    }
}

/**
 * Minerai complet : bloc, génération dans le monde (feature + feature rule), gemme ou lingot, cuisson.
 *
 * Formats relevés dans la documentation officielle de Mojang/bedrock-samples v1.26.60.5 (`documentation/Features.html`)
 * et dans MicrosoftDocs/minecraft-creator (`feature_rule_conditions.md`) :
 * - `minecraft:ore_feature` (format 1.13.0) : `count` + `replace_rules` [{ `places_block`, `may_replace` }] ;
 *   un fichier par feature, nom de fichier = nom de la feature ;
 * - `minecraft:feature_rules` (format 1.13.0) : `description.places_feature`, `conditions.placement_pass`
 *   (`underground_pass`), `minecraft:biome_filter` par `has_biome_tag`, `distribution` (iterations, x/y/z en
 *   `uniform` + `extent`, `coordinate_eval_order`).
 */
object OreGenerator {
    const val FEATURE_FORMAT = "1.13.0"

    fun featureName(spec: OreSpec) = "${spec.oreBlockId}_feature"
    fun ruleName(spec: OreSpec) = "${spec.oreBlockId}_rule"

    fun generate(namespace: String, spec: OreSpec, profile: BedrockVersionProfile): GeneratedContent {
        val errors = spec.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val oreId = "$namespace:${spec.oreBlockId}"
        val resultId = "$namespace:${spec.resultShortId}"
        val c = GeneratedContent()

        val gemDrop = spec.drop == OreDrop.GEM
        c.addAll(BlockGenerator.generate(namespace, BlockSpec(
            displayName = spec.displayName, shortId = spec.oreBlockId, style = BlockStyle.ORE,
            mainColor = spec.stoneColor, accentColor = spec.oreColor, hardness = spec.hardness, explosionResistance = 3.0,
            dropItem = if (gemDrop) resultId else null, dropMin = spec.dropMin, dropMax = spec.dropMax,
        ), profile))
        c.addAll(ItemGenerator.generate(namespace, ItemSpec(
            displayName = spec.resultName.trim(), shortId = spec.resultShortId, kind = ItemKind.SIMPLE,
            mainColor = spec.oreColor, accentColor = spec.stoneColor,
        ), profile))
        if (spec.smelting) {
            c.addAll(RecipeGenerator.generate(namespace, RecipeSpec.Furnace(
                shortId = "${spec.oreBlockId}_cuisson", input = oreId, output = resultId, blastFurnace = true,
            ), profile))
        }
        c.text("$BP/features/${featureName(spec)}.json", JsonWriter.write(feature("$namespace:${featureName(spec)}", oreId, spec)) + "\n")
        c.text("$BP/feature_rules/${ruleName(spec)}.json", JsonWriter.write(rule(namespace, spec)) + "\n")
        return c
    }

    fun feature(id: String, oreId: String, s: OreSpec): JsonValue = obj(
        "format_version" to FEATURE_FORMAT,
        "minecraft:ore_feature" to obj(
            "description" to obj("identifier" to id),
            "count" to s.veinSize,
            "replace_rules" to listOf(obj("places_block" to oreId, "may_replace" to s.world.replaces)),
        ),
    )

    fun rule(ns: String, s: OreSpec): JsonValue = obj(
        "format_version" to FEATURE_FORMAT,
        "minecraft:feature_rules" to obj(
            "description" to obj("identifier" to "$ns:${ruleName(s)}", "places_feature" to "$ns:${featureName(s)}"),
            "conditions" to obj(
                "placement_pass" to "underground_pass",
                "minecraft:biome_filter" to listOf(obj("test" to "has_biome_tag", "operator" to "==", "value" to s.world.biomeTag)),
            ),
            "distribution" to obj(
                "iterations" to s.veinsPerChunk,
                "coordinate_eval_order" to "zyx",
                "x" to obj("distribution" to "uniform", "extent" to listOf(0, 16)),
                "y" to obj("distribution" to "uniform", "extent" to listOf(s.minY, s.maxY)),
                "z" to obj("distribution" to "uniform", "extent" to listOf(0, 16)),
            ),
        ),
    )
}
