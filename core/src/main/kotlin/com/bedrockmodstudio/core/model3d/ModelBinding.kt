package com.bedrockmodstudio.core.model3d

import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.arr
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.storage.UserFacingException

/**
 * Création d'un modèle de départ et association à une créature ou à un bloc du projet.
 * Tout est rendu sous forme de [GeneratedContent] : l'application demande confirmation avant de remplacer un fichier.
 *
 * Blocs (Microsoft Learn, minecraft:geometry et minecraft:material_instances ; wiki.bedrock.dev, Block Models) :
 * modèle dans `RP/models/blocks`, identifiant sans « : », géométrie et matériaux toujours fournis ensemble,
 * texture désignée par son nom court de `terrain_texture.json`. Par prudence, le modèle reste dans le volume
 * d'un bloc et les boîtes de collision/sélection sont écrites explicitement.
 */
object ModelBinding {
    data class Target(val id: String, val path: String)

    fun modelPath(use: ModelUse, name: String) = "$RP/models/${use.folder}/$name.geo.json"
    fun texturePath(use: ModelUse, name: String) = "textures/${if (use == ModelUse.BLOCK) "blocks" else "entity"}/$name"
    fun geometryId(ns: String, name: String) = "geometry.$ns.$name"

    /** Nouveau modèle (fichier .geo.json + texture PNG). */
    fun create(ns: String, name: String, template: ModelTemplate, mainColor: Int, accentColor: Int): GeneratedContent {
        val (model, img) = template.build(geometryId(ns, name), mainColor, accentColor)
        return GeneratedContent().apply {
            text(modelPath(template.use, name), GeoCodec.write(GeoFile("1.12.0", listOf(model), legacy = false)))
            bytes("$RP/${texturePath(template.use, name)}.png", img.toPng())
        }
    }

    /** Créatures (fichiers client) et blocs du projet auxquels un modèle peut être associé. */
    fun targets(files: ProjectFiles, use: ModelUse): List<Target> {
        val (dir, root) = if (use == ModelUse.CREATURE) "$RP/entity" to "minecraft:client_entity" else "$BP/blocks" to "minecraft:block"
        if (!files.exists(dir)) return emptyList()
        return files.walk(dir).filter { it.endsWith(".json") }.mapNotNull { p ->
            val v = runCatching { JsonParser.parse(files.readText(p)).valueOrNull() }.getOrNull() ?: return@mapNotNull null
            v.path(root, "description", "identifier")?.asString?.let { Target(it, p) }
        }.sortedBy { it.id }
    }

    /** Boîte englobante du modèle en pixels (min, max). */
    fun bounds(model: GeoModel): Pair<Vec3, Vec3> {
        val pts = MeshBuilder.build(model).flatMap { it.corners }
        if (pts.isEmpty()) return Vec3(-8.0, 0.0, -8.0) to Vec3(8.0, 16.0, 8.0)
        return Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) to Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
    }

    /** Vérifie qu'un modèle de bloc tient dans un bloc (x et z entre -8 et 8, y entre 0 et 16). */
    fun blockProblems(model: GeoModel): List<String> {
        val (min, max) = bounds(model)
        val eps = 1e-6
        return buildList {
            if (min.x < -8 - eps || max.x > 8 + eps || min.z < -8 - eps || max.z > 8 + eps || min.y < -eps || max.y > 16 + eps) {
                add("Le modèle dépasse le volume d'un bloc (de -8 à 8 en X et Z, de 0 à 16 en hauteur). Réduis ou déplace des cubes.")
            }
        }
    }

    /** Remplace le modèle et la texture d'une créature (fichier client), en gardant le reste. */
    fun bindCreature(files: ProjectFiles, target: Target, geometryId: String, texture: String): GeneratedContent {
        val root = JsonParser.parseOrThrow(files.readText(target.path)) as? JsonObject ?: throw UserFacingException("Fichier de créature illisible.")
        val desc = root.obj("minecraft:client_entity")?.obj("description") ?: throw UserFacingException("Ce fichier n'est pas une créature (client_entity).")
        val geo = desc.obj("geometry") ?: JsonObject().also { desc["geometry"] = it }
        val tex = desc.obj("textures") ?: JsonObject().also { desc["textures"] = it }
        geo["default"] = com.bedrockmodstudio.core.json.JsonString(geometryId)
        tex["default"] = com.bedrockmodstudio.core.json.JsonString(texture)
        return GeneratedContent().apply { text(target.path, JsonWriter.write(root) + "\n") }
    }

    /** Donne une forme 3D à un bloc : géométrie, matériau, texture déclarée dans l'atlas et boîtes ajustées au modèle. */
    fun bindBlock(files: ProjectFiles, target: Target, model: GeoModel, texture: String): GeneratedContent {
        blockProblems(model).firstOrNull()?.let { throw UserFacingException(it) }
        val root = JsonParser.parseOrThrow(files.readText(target.path)) as? JsonObject ?: throw UserFacingException("Fichier de bloc illisible.")
        val comps = root.obj("minecraft:block")?.obj("components") ?: throw UserFacingException("Ce fichier n'est pas un bloc.")
        val key = "bms_model_" + texture.substringAfterLast('/')
        val (min, max) = bounds(model)
        fun r(d: Double) = Math.round(d * 100) / 100.0
        val box = obj("origin" to arr(r(min.x), r(min.y), r(min.z)), "size" to arr(r(max.x - min.x), r(max.y - min.y), r(max.z - min.z)))
        comps["minecraft:geometry"] = com.bedrockmodstudio.core.json.JsonString(model.identifier)
        comps["minecraft:material_instances"] = obj("*" to obj("texture" to key, "render_method" to "alpha_test"))
        comps["minecraft:collision_box"] = box
        comps["minecraft:selection_box"] = obj("origin" to box["origin"], "size" to box["size"])
        // Une forme non pleine laisse passer la lumière.
        comps["minecraft:light_dampening"] = com.bedrockmodstudio.core.json.JsonNumber(0)
        return GeneratedContent().apply {
            text(target.path, JsonWriter.write(root) + "\n")
            terrainTexture(key, texture)
        }
    }
}
