package com.bedrockmodstudio.core.bedrock

/**
 * Profil d'une version de Minecraft Bedrock ciblée.
 *
 * Toutes les valeurs ci-dessous ont été relevées dans les sources officielles le 20/10/2026 :
 * - dépôt `Mojang/bedrock-samples`, tags `v1.26.30.5`, `v1.26.40.05`, `v1.26.50.4`
 *   (manifestes, `format_version` des entités, objets, modèles, animations, recettes) ;
 * - paquets npm `@minecraft/server` et `@minecraft/server-ui` (versions stables publiées le même jour
 *   que la version du jeu correspondante, et présence de `DimensionRegistry.registerCustomDimension`).
 *
 * Exceptions assumées, pour les fichiers *générés* par l'application :
 * - entité client : "1.10.0", le format documenté pour `minecraft:client_entity` avec
 *   `scripts`/`animations` (les fichiers vanilla en 1.26.0 utilisent des champs que nous ne générons pas) ;
 * - géométrie : "1.12.0", le format `minecraft:geometry` en tableau (les fichiers vanilla en 1.8.0 utilisent
 *   l'ancien format par clés `geometry.xxx`, que la validation sait aussi lire).
 *
 * Ne jamais ajouter un profil sans l'avoir vérifié de la même manière.
 */
data class BedrockVersionProfile(
    val id: String,
    val label: String,
    val minEngineVersion: List<Int>,
    val manifestFormatVersion: Int,
    val entityBehaviorFormat: String,
    val entityClientFormat: String,
    val itemFormat: String,
    val recipeFormat: String,
    val geometryFormat: String,
    val animationFormat: String,
    val renderControllerFormat: String,
    val spawnRulesFormat: String,
    val scriptServerVersion: String,
    val scriptServerUiVersion: String,
    val features: Set<BedrockFeature>,
    val source: String,
) {
    fun supports(feature: BedrockFeature) = feature in features
}

enum class BedrockFeature(val labelFr: String) {
    SCRIPT_API("API de scripts (@minecraft/server)"),
    SCRIPT_UI_FORMS("Formulaires d'interface (@minecraft/server-ui)"),
    CUSTOM_COMMANDS("Commandes personnalisées par script"),
    CUSTOM_DIMENSIONS("Dimensions personnalisées (générateur vide)"),
    DATA_DRIVEN_BIOMES_FILES("Fichiers de biomes data-driven"),
}

object BedrockVersions {
    private val commonFeatures = setOf(
        BedrockFeature.SCRIPT_API,
        BedrockFeature.SCRIPT_UI_FORMS,
        BedrockFeature.CUSTOM_COMMANDS,
        BedrockFeature.CUSTOM_DIMENSIONS,
        BedrockFeature.DATA_DRIVEN_BIOMES_FILES,
    )

    val V1_26_30 = BedrockVersionProfile(
        id = "1.26.30", label = "Minecraft 1.26.30",
        minEngineVersion = listOf(1, 26, 30), manifestFormatVersion = 2,
        entityBehaviorFormat = "1.26.20", entityClientFormat = "1.10.0", itemFormat = "1.26.30",
        recipeFormat = "1.20.10", geometryFormat = "1.12.0", animationFormat = "1.8.0",
        renderControllerFormat = "1.8.0", spawnRulesFormat = "1.8.0",
        scriptServerVersion = "2.8.0", scriptServerUiVersion = "2.1.0",
        features = commonFeatures,
        source = "bedrock-samples v1.26.30.5 ; @minecraft/server 2.8.0 (16/06/2026)",
    )

    val V1_26_40 = V1_26_30.copy(
        id = "1.26.40", label = "Minecraft 1.26.40",
        minEngineVersion = listOf(1, 26, 40),
        scriptServerVersion = "2.9.0", scriptServerUiVersion = "2.1.0",
        source = "bedrock-samples v1.26.40.05 ; @minecraft/server 2.9.0 (04/08/2026)",
    )

    val V1_26_50 = V1_26_30.copy(
        id = "1.26.50", label = "Minecraft 1.26.50 (actuelle)",
        minEngineVersion = listOf(1, 26, 50),
        entityBehaviorFormat = "1.26.50",
        scriptServerVersion = "2.10.0", scriptServerUiVersion = "2.2.0",
        source = "bedrock-samples v1.26.50.4 ; @minecraft/server 2.10.0 (15/09/2026)",
    )

    val ALL: List<BedrockVersionProfile> = listOf(V1_26_50, V1_26_40, V1_26_30)
    val DEFAULT: BedrockVersionProfile = V1_26_50

    fun byId(id: String?): BedrockVersionProfile? = ALL.firstOrNull { it.id == id }

    /** Profil le plus récent dont la version minimale est ≤ à celle demandée (utile à l'import). */
    fun closestFor(minEngine: List<Int>?): BedrockVersionProfile {
        if (minEngine == null) return DEFAULT
        return ALL.filter { compare(it.minEngineVersion, minEngine) <= 0 }.maxWithOrNull { a, b ->
            compare(a.minEngineVersion, b.minEngineVersion)
        } ?: ALL.minWith { a, b -> compare(a.minEngineVersion, b.minEngineVersion) }
    }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
