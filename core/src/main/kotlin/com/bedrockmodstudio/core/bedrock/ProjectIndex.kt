package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.structure.StructureGenerator

/** Contenus définis dans un projet (pour proposer ses propres objets dans les recettes, etc.). */
object ProjectIndex {
    enum class Kind(val labelFr: String) { ENTITY("Créature"), ITEM("Objet"), BLOCK("Bloc") }

    data class Entry(val id: String, val kind: Kind, val path: String)

    fun scan(files: ProjectFiles): List<Entry> {
        val out = mutableListOf<Entry>()
        for ((dir, root, kind) in listOf(
            Triple("behavior_pack/entities", "minecraft:entity", Kind.ENTITY),
            Triple("behavior_pack/items", "minecraft:item", Kind.ITEM),
            Triple("behavior_pack/blocks", "minecraft:block", Kind.BLOCK),
        )) {
            if (!files.exists(dir)) continue
            for (path in files.walk(dir).filter { it.endsWith(".json") }) {
                val v = runCatching { JsonParser.parse(files.readText(path)).valueOrNull() }.getOrNull() ?: continue
                val id = v.path(root, "description", "identifier")?.asString ?: continue
                out += Entry(id, kind, path)
            }
        }
        return out.sortedBy { it.id }
    }

    /** Identifiants utilisables comme objets : objets, blocs et œufs d'apparition des créatures. */
    fun usableItems(files: ProjectFiles): List<String> = scan(files).map {
        if (it.kind == Kind.ENTITY) "${it.id}_spawn_egg" else it.id
    }

    /** Identifiants des structures du projet (fichiers .mcstructure de behavior_pack/structures). */
    fun structures(files: ProjectFiles): List<String> =
        if (!files.exists("behavior_pack/structures")) emptyList()
        else files.walk("behavior_pack/structures").mapNotNull { StructureGenerator.idForPath(it) }.sorted()
}
