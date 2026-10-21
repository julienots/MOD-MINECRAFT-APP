package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.obj

/**
 * Entrée à ajouter dans un fichier JSON partagé par plusieurs contenus (atlas de textures, blocks.json).
 * Le fichier est créé à partir de [template] s'il n'existe pas ; sinon seule la clé [key] est ajoutée
 * sous [objectPath], le reste du fichier étant conservé.
 */
data class JsonMerge(
    val path: String,
    val template: () -> JsonObject,
    val objectPath: List<String>,
    val key: String,
    val value: JsonValue,
)

/**
 * Résultat d'un générateur : des fichiers (chemins relatifs à la racine du projet,
 * ex. `behavior_pack/entities/dragon.json`), des entrées de fichiers partagés et des traductions.
 */
class GeneratedContent {
    val files = LinkedHashMap<String, ByteArray>()
    val merges = mutableListOf<JsonMerge>()
    /** Traductions communes à toutes les langues, écrites dans en_US et fr_FR si absentes. */
    val lang = LinkedHashMap<String, String>()
    /** Modules de script (chemins relatifs à `behavior_pack/scripts/`) à importer depuis le point d'entrée. */
    val scriptModules = mutableListOf<String>()
    /** Le contenu utilise les formulaires `@minecraft/server-ui`. */
    var needsServerUi = false
    /** Fonctionnalité Bedrock indispensable (vérifiée contre la version ciblée avant installation). */
    val requiredFeatures = mutableSetOf<BedrockFeature>()

    fun text(path: String, content: String) { files[path] = content.toByteArray(Charsets.UTF_8) }
    fun bytes(path: String, content: ByteArray) { files[path] = content }

    /** Ajoute un module de script dans `behavior_pack/scripts/` et le déclare pour import. */
    fun script(modulePath: String, code: String) {
        text("$BP/scripts/$modulePath", code)
        if (modulePath !in scriptModules) scriptModules += modulePath
        requiredFeatures += BedrockFeature.SCRIPT_API
    }

    /** Ajoute une texture d'objet dans `textures/item_texture.json`. */
    fun itemTexture(key: String, texturePath: String) {
        merges += JsonMerge("$RP/textures/item_texture.json", ::itemAtlasTemplate, listOf("texture_data"), key, obj("textures" to texturePath))
    }

    /** Ajoute une texture de bloc dans `textures/terrain_texture.json`. */
    fun terrainTexture(key: String, texturePath: String) {
        merges += JsonMerge("$RP/textures/terrain_texture.json", ::terrainAtlasTemplate, listOf("texture_data"), key, obj("textures" to texturePath))
    }

    /** Son d'un bloc dans `blocks.json`. */
    fun blockSound(blockId: String, sound: String) {
        merges += JsonMerge("$RP/blocks.json", { obj("format_version" to "1.19.30") }, emptyList(), blockId, obj("sound" to sound))
    }

    /** Fusionne un autre contenu généré dans celui-ci (ex. objet + sa recette). */
    fun addAll(other: GeneratedContent) {
        files.putAll(other.files); merges += other.merges; lang.putAll(other.lang)
        scriptModules += other.scriptModules.filter { it !in scriptModules }
        needsServerUi = needsServerUi || other.needsServerUi
        requiredFeatures += other.requiredFeatures
    }

    companion object {
        const val BP = "behavior_pack"
        const val RP = "resource_pack"
        val LANG_FILES = listOf("$RP/texts/en_US.lang", "$RP/texts/fr_FR.lang")

        fun itemAtlasTemplate() = obj(
            "resource_pack_name" to "bedrock_mod_studio", "texture_name" to "atlas.items", "texture_data" to obj(),
        )

        fun terrainAtlasTemplate() = obj(
            "resource_pack_name" to "bedrock_mod_studio", "texture_name" to "atlas.terrain",
            "padding" to 8, "num_mip_levels" to 4, "texture_data" to obj(),
        )
    }
}
