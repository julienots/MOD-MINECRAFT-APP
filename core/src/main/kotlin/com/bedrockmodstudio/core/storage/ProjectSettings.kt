package com.bedrockmodstudio.core.storage

import com.bedrockmodstudio.core.bedrock.BedrockVersionProfile
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.jsonOf
import com.bedrockmodstudio.core.model.ProjectMeta

/**
 * Modifie les paramètres d'un projet et répercute les changements dans les manifestes
 * (nom, description, version minimale, version de l'API de scripts), avec historique.
 */
object ProjectSettings {

    data class Result(val meta: ProjectMeta, val updatedFiles: List<String>, val skipped: List<String>)

    fun update(
        store: ProjectStore,
        id: String,
        name: String,
        description: String,
        author: String,
        profile: BedrockVersionProfile,
    ): Result {
        if (name.isBlank()) throw UserFacingException("Le nom du projet est vide.")
        val old = store.load(id)
        val files = store.files(id)
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((path, kind) in listOf("behavior_pack/manifest.json" to "Behavior Pack", "resource_pack/manifest.json" to "Resource Pack")) {
            if (!files.exists(path)) continue
            val manifest = (JsonParser.parse(files.readText(path)) as? JsonParseResult.Success)?.value as? JsonObject
            if (manifest == null) { skipped += "$path (JSON invalide : corrige-le d'abord)"; continue }
            val header = manifest.obj("header") ?: run { skipped += "$path (pas de section header)"; null } ?: continue
            // Le nom n'est remplacé que s'il suit le modèle généré, pour ne pas écraser un nom choisi à la main.
            val currentName = header.str("name")
            if (currentName == null || currentName == "${old.name} ($kind)") header["name"] = JsonString("${name.trim()} ($kind)")
            val currentDesc = header.str("description")
            if (currentDesc == null || currentDesc == old.description || currentDesc == "Créé avec Bedrock Mod Studio") {
                header["description"] = JsonString(description.trim().ifBlank { "Créé avec Bedrock Mod Studio" })
            }
            header["min_engine_version"] = jsonOf(profile.minEngineVersion)
            manifest.arr("dependencies")?.items?.forEach { d ->
                val dep = d as? JsonObject ?: return@forEach
                when (dep.str("module_name")) {
                    "@minecraft/server" -> if (dep.str("version")?.contains("beta") != true) dep["version"] = JsonString(profile.scriptServerVersion)
                    "@minecraft/server-ui" -> if (dep.str("version")?.contains("beta") != true) dep["version"] = JsonString(profile.scriptServerUiVersion)
                }
            }
            val text = JsonWriter.write(manifest) + "\n"
            if (text != files.readText(path)) {
                files.writeText(path, text, reason = "Paramètres du projet")
                updated += path
            }
        }
        val meta = old.copy(name = name.trim(), description = description.trim(), author = author.trim(), targetVersion = profile.id)
        store.saveMeta(meta)
        return Result(store.load(id), updated, skipped)
    }
}
