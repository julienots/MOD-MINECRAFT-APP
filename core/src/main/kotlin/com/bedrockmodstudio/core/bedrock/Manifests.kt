package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.jsonOf
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.PackVersion
import com.bedrockmodstudio.core.model.ProjectMeta

object Manifests {
    const val APP_TOOL_NAME = "bedrock_mod_studio"
    const val APP_VERSION = "0.1.0"
    const val SCRIPT_ENTRY = "scripts/main.js"

    fun behaviorPack(meta: ProjectMeta, profile: BedrockVersionProfile, withScripts: Boolean): JsonObject {
        val v = meta.packVersion.toList()
        val modules = mutableListOf<Any>(
            obj("description" to "${meta.name} — comportements", "type" to "data",
                "uuid" to meta.behaviorPack.dataModuleUuid, "version" to v),
        )
        val deps = mutableListOf<Any>(obj("uuid" to meta.resourcePack.headerUuid, "version" to v))
        if (withScripts) {
            val scriptUuid = requireNotNull(meta.behaviorPack.scriptModuleUuid) { "UUID du module de script manquant" }
            modules += obj("description" to "${meta.name} — scripts", "type" to "script", "language" to "javascript",
                "uuid" to scriptUuid, "version" to v, "entry" to SCRIPT_ENTRY)
            deps += obj("module_name" to "@minecraft/server", "version" to profile.scriptServerVersion)
        }
        return obj(
            "format_version" to profile.manifestFormatVersion,
            "header" to header(meta, meta.behaviorPack.headerUuid, profile, "Behavior Pack"),
            "modules" to modules,
            "dependencies" to deps,
            "metadata" to metadata(meta),
        )
    }

    fun resourcePack(meta: ProjectMeta, profile: BedrockVersionProfile): JsonObject = obj(
        "format_version" to profile.manifestFormatVersion,
        "header" to header(meta, meta.resourcePack.headerUuid, profile, "Resource Pack"),
        "modules" to listOf(
            obj("description" to "${meta.name} — ressources", "type" to "resources",
                "uuid" to meta.resourcePack.dataModuleUuid, "version" to meta.packVersion.toList()),
        ),
        "metadata" to metadata(meta),
    )

    private fun header(meta: ProjectMeta, uuid: String, profile: BedrockVersionProfile, kind: String) = obj(
        "name" to "${meta.name} ($kind)",
        "description" to meta.description.ifBlank { "Créé avec Bedrock Mod Studio" },
        "uuid" to uuid,
        "version" to meta.packVersion.toList(),
        "min_engine_version" to profile.minEngineVersion,
    )

    private fun metadata(meta: ProjectMeta) = obj(
        "authors" to listOf(meta.author.ifBlank { "Bedrock Mod Studio" }),
        "generated_with" to obj(APP_TOOL_NAME to listOf(APP_VERSION)),
    )

    /**
     * Met à jour la version d'un manifeste existant sans le régénérer
     * (il peut avoir été modifié à la main ou provenir d'un import) :
     * en-tête, modules, et dépendances qui pointent vers les packs du projet.
     */
    fun applyVersion(manifest: JsonObject, version: PackVersion, projectHeaderUuids: Set<String>): JsonObject {
        manifest.obj("header")?.set("version", jsonOf(version.toList()))
        manifest.arr("modules")?.items?.forEach { (it as? JsonObject)?.set("version", jsonOf(version.toList())) }
        manifest.arr("dependencies")?.items?.forEach { d ->
            val dep = d as? JsonObject ?: return@forEach
            val uuid = (dep["uuid"] as? JsonString)?.value
            if (uuid != null && uuid in projectHeaderUuids) dep["version"] = jsonOf(version.toList())
        }
        return manifest
    }
}
