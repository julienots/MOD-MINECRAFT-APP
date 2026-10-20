package com.bedrockmodstudio.core.model

import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.obj
import java.util.UUID

/** Version d'un pack au format Bedrock `[majeur, mineur, correctif]`. */
data class PackVersion(val major: Int, val minor: Int, val patch: Int) {
    fun toList() = listOf(major, minor, patch)
    fun bumpPatch() = copy(patch = patch + 1)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        val INITIAL = PackVersion(1, 0, 0)
        fun fromJson(v: JsonValue?): PackVersion? {
            val a = v as? JsonArray ?: return null
            if (a.size != 3) return null
            val n = a.items.map { it.asInt ?: return null }
            return PackVersion(n[0], n[1], n[2])
        }
    }
}

/** UUID d'un pack : en-tête + module(s). Générés une seule fois puis conservés pour toujours. */
data class PackIds(
    val headerUuid: String,
    val dataModuleUuid: String,
    val scriptModuleUuid: String? = null,
) {
    companion object {
        fun generate(withScript: Boolean = false) = PackIds(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            if (withScript) UUID.randomUUID().toString() else null,
        )
    }
}

/**
 * Métadonnées propres à l'éditeur (fichier `project.json` à la racine du projet).
 * Les fichiers Bedrock eux-mêmes restent la source de vérité du contenu.
 */
data class ProjectMeta(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    /** Espace de noms des identifiants, ex. `monmod` dans `monmod:dragon`. */
    val namespace: String,
    val targetVersion: String,
    val packVersion: PackVersion,
    val behaviorPack: PackIds,
    val resourcePack: PackIds,
    val createdAt: Long,
    val updatedAt: Long,
    /** Le projet provient d'un import : ses fichiers d'origine sont conservés dans `.imported/`. */
    val imported: Boolean = false,
) {
    fun toJson(): JsonObject = obj(
        "bms_project_format" to CURRENT_FORMAT,
        "id" to id,
        "name" to name,
        "description" to description,
        "author" to author,
        "namespace" to namespace,
        "target_version" to targetVersion,
        "pack_version" to packVersion.toList(),
        "behavior_pack" to idsToJson(behaviorPack),
        "resource_pack" to idsToJson(resourcePack),
        "created_at" to createdAt,
        "updated_at" to updatedAt,
        "imported" to imported,
    )

    companion object {
        const val CURRENT_FORMAT = 1
        const val FILE_NAME = "project.json"

        private fun idsToJson(ids: PackIds) = obj(
            "header_uuid" to ids.headerUuid,
            "data_module_uuid" to ids.dataModuleUuid,
            "script_module_uuid" to ids.scriptModuleUuid,
        )

        private fun idsFromJson(o: JsonObject?): PackIds? {
            o ?: return null
            return PackIds(
                o.str("header_uuid") ?: return null,
                o.str("data_module_uuid") ?: return null,
                o.str("script_module_uuid"),
            )
        }

        fun fromJson(v: JsonValue): ProjectMeta? {
            val o = v as? JsonObject ?: return null
            return ProjectMeta(
                id = o.str("id") ?: return null,
                name = o.str("name") ?: return null,
                description = o.str("description") ?: "",
                author = o.str("author") ?: "",
                namespace = o.str("namespace") ?: return null,
                targetVersion = o.str("target_version") ?: return null,
                packVersion = PackVersion.fromJson(o["pack_version"]) ?: PackVersion.INITIAL,
                behaviorPack = idsFromJson(o.obj("behavior_pack")) ?: return null,
                resourcePack = idsFromJson(o.obj("resource_pack")) ?: return null,
                createdAt = (o["created_at"]?.asDouble ?: 0.0).toLong(),
                updatedAt = (o["updated_at"]?.asDouble ?: 0.0).toLong(),
                imported = o["imported"]?.asBoolean ?: false,
            )
        }
    }
}

object Identifiers {
    private val NAME = Regex("^[a-z0-9_.]+$")
    private val NAMESPACE = Regex("^[a-z0-9_]+$")

    /** Transforme un nom libre (« Mon Super Mod ! ») en espace de noms valide (« mon_super_mod »). */
    fun toNamespace(name: String): String {
        val n = slug(name).replace('.', '_')
        return when {
            n.isEmpty() -> "monmod"
            n == "minecraft" || n == "minecon" -> "${n}_mod"
            n[0].isDigit() -> "m_$n"
            else -> n.take(32)
        }
    }

    fun slug(text: String): String {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        return normalized.replace(Regex("[^a-z0-9_.]+"), "_").trim('_').replace(Regex("_+"), "_")
    }

    fun isValidNamespace(ns: String) = NAMESPACE.matches(ns) && ns != "minecraft" && ns != "minecon"
    fun isValidName(name: String) = NAME.matches(name)

    /** `monmod:dragon` → true. Vérifie la forme exigée par Bedrock pour les contenus personnalisés. */
    fun isValidIdentifier(id: String): Boolean {
        val parts = id.split(':')
        return parts.size == 2 && NAMESPACE.matches(parts[0]) && isValidName(parts[1])
    }

    fun isValidUuid(text: String?): Boolean =
        text != null && Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(text)
}
