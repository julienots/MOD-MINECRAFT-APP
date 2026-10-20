package com.bedrockmodstudio.core.bedrock

/**
 * Résultat d'un générateur : des fichiers (chemins relatifs à la racine du projet,
 * ex. `behavior_pack/entities/dragon.json`) et des traductions à fusionner.
 */
class GeneratedContent {
    val files = LinkedHashMap<String, ByteArray>()
    /** Traductions communes à toutes les langues, écrites dans en_US et fr_FR si absentes. */
    val lang = LinkedHashMap<String, String>()

    fun text(path: String, content: String) { files[path] = content.toByteArray(Charsets.UTF_8) }
    fun bytes(path: String, content: ByteArray) { files[path] = content }

    companion object {
        const val BP = "behavior_pack"
        const val RP = "resource_pack"
        val LANG_FILES = listOf("$RP/texts/en_US.lang", "$RP/texts/fr_FR.lang")
    }
}
