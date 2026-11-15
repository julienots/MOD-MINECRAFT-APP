package com.bedrockmodstudio.core.mods

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.bedrock.LangFile
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.storage.ProjectFiles

/**
 * Vue « catalogue » d'un mod : chaque créature, objet et bloc avec ce qu'il faut pour l'afficher en 3D
 * (modèle, texture, animations) et ses réglages principaux. Fonctionne aussi bien sur les projets créés
 * dans l'application que sur les addons importés (formats anciens et récents tolérés).
 *
 * Tout est en lecture seule : les modifications passent par [QuickEdit].
 */
object ModCatalog {

    enum class Kind(val labelFr: String, val pluralFr: String) {
        ENTITY("Créature", "Créatures"), ITEM("Objet", "Objets"), BLOCK("Bloc", "Blocs")
    }

    /** Textures d'un bloc par face (chemins de projet vers des .png, ou null si introuvable). */
    data class BlockFaces(val up: String?, val down: String?, val side: String?) {
        val any: String? get() = side ?: up ?: down
    }

    data class Entry(
        val id: String,
        val kind: Kind,
        /** Nom affiché (traduction trouvée, sinon déduit de l'identifiant). */
        val name: String,
        /** Fichier du behavior pack (comportement). */
        val behaviorPath: String?,
        /** Fichier d'apparence (client_entity) pour les créatures. */
        val clientPath: String?,
        val geometryId: String?,
        val geometryPath: String?,
        /** Texture principale (.png du projet), si trouvée. */
        val texturePath: String?,
        val blockFaces: BlockFaces? = null,
        /** Animations déclarées par l'apparence : nom court → (identifiant, fichier). */
        val animations: List<AnimationRef> = emptyList(),
        val stats: Map<QuickEdit.Stat, Double> = emptyMap(),
        val problems: List<String> = emptyList(),
    ) {
        val namespace: String get() = id.substringBefore(':', "")
        val shortId: String get() = id.substringAfter(':')
    }

    data class AnimationRef(val shortName: String, val id: String, val path: String?)

    data class Catalog(val entries: List<Entry>) {
        fun of(kind: Kind) = entries.filter { it.kind == kind }
        fun byId(id: String, kind: Kind? = null) = entries.firstOrNull { it.id == id && (kind == null || it.kind == kind) }
    }

    fun scan(files: ProjectFiles): Catalog {
        val ctx = Context(files)
        val out = mutableListOf<Entry>()
        out += ctx.entities()
        out += ctx.items()
        out += ctx.blocks()
        return Catalog(out.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() })))
    }

    /** Lit un JSON du projet ; null si absent ou cassé. */
    internal fun readJson(files: ProjectFiles, path: String): JsonValue? =
        if (!files.exists(path)) null else runCatching { JsonParser.parse(files.readText(path)).valueOrNull() }.getOrNull()

    internal fun jsonFiles(files: ProjectFiles, dir: String): List<String> =
        if (!files.exists(dir)) emptyList() else files.walk(dir).filter { it.endsWith(".json") }

    /** Nom lisible à partir d'un identifiant : « monmod:dragon_rouge » → « Dragon rouge ». */
    fun prettyName(id: String): String = id.substringAfter(':').replace('_', ' ').replace('.', ' ').trim()
        .replaceFirstChar { it.uppercase() }

    /** Clés de traduction possibles d'un contenu, dans l'ordre où Minecraft les cherche. */
    fun langKeys(kind: Kind, id: String): List<String> = when (kind) {
        Kind.ENTITY -> listOf("entity.$id.name")
        Kind.ITEM -> listOf("item.$id", "item.$id.name")
        Kind.BLOCK -> listOf("tile.$id.name")
    }

    private class Context(val files: ProjectFiles) {
        val langs: List<LangFile> = listOf("fr_FR", "en_US").mapNotNull { l ->
            val p = "$RP/texts/$l.lang"
            if (files.exists(p)) runCatching { LangFile(files.readText(p)) }.getOrNull() else null
        }

        /** identifiant de géométrie → fichier. */
        val geometries: Map<String, String> by lazy {
            val m = HashMap<String, String>()
            for (p in jsonFiles(files, "$RP/models")) {
                val geo = runCatching { GeoCodec.parse(files.readText(p)) }.getOrNull() ?: continue
                geo.models.forEach { m.putIfAbsent(it.identifier, p) }
            }
            m
        }

        /** identifiant d'animation → fichier. */
        val animationFiles: Map<String, String> by lazy {
            val m = HashMap<String, String>()
            for (p in jsonFiles(files, "$RP/animations")) {
                val anims = readJson(files, p)?.path("animations") as? JsonObject ?: continue
                anims.entries.forEach { m.putIfAbsent(it.key, p) }
            }
            m
        }

        val itemAtlas: JsonObject? by lazy { readJson(files, "$RP/textures/item_texture.json")?.path("texture_data") as? JsonObject }
        val terrainAtlas: JsonObject? by lazy { readJson(files, "$RP/textures/terrain_texture.json")?.path("texture_data") as? JsonObject }
        val blocksJson: JsonObject? by lazy { readJson(files, "$RP/blocks.json") as? JsonObject }

        fun name(kind: Kind, id: String): String {
            for (k in langKeys(kind, id)) for (l in langs) l[k]?.takeIf { it.isNotBlank() }?.let { return it.replace(Regex("§."), "") }
            return prettyName(id)
        }

        /** « textures/entity/x » → chemin réel du .png dans le resource pack, s'il existe. */
        fun png(texture: String?): String? {
            texture ?: return null
            val base = "$RP/${texture.removeSuffix(".png").removeSuffix(".tga")}"
            return "$base.png".takeIf { files.exists(it) }
        }

        fun atlasTexture(atlas: JsonObject?, key: String?): String? {
            key ?: return null
            val t = (atlas?.get(key) as? JsonObject)?.get("textures") ?: return null
            val first: JsonValue = (t as? JsonArray)?.items?.firstOrNull() ?: t
            return png(first.asString ?: (first as? JsonObject)?.str("path"))
        }

        fun entities(): List<Entry> {
            // Apparences, indexées par identifiant.
            val clients = HashMap<String, Pair<String, JsonObject>>()
            for (p in jsonFiles(files, "$RP/entity")) {
                val d = readJson(files, p)?.path("minecraft:client_entity", "description") as? JsonObject ?: continue
                d.str("identifier")?.let { clients.putIfAbsent(it, p to d) }
            }
            val out = mutableListOf<Entry>()
            val seen = HashSet<String>()
            for (p in jsonFiles(files, "$BP/entities")) {
                val root = readJson(files, p) ?: continue
                val id = root.path("minecraft:entity", "description", "identifier")?.asString ?: continue
                if (!seen.add(id)) continue
                out += entity(id, p, root, clients[id])
            }
            // Apparences sans comportement (rare, mais présent dans certains packs de ressources seuls).
            for ((id, c) in clients) if (seen.add(id)) out += entity(id, null, null, c)
            return out
        }

        fun entity(id: String, bpPath: String?, bp: JsonValue?, client: Pair<String, JsonObject>?): Entry {
            val desc = client?.second
            val geoId = (desc?.obj("geometry"))?.let { g -> g.str("default") ?: g.entries.firstOrNull()?.value?.asString }
            val tex = (desc?.obj("textures"))?.let { t -> t.str("default") ?: t.entries.firstOrNull()?.value?.asString }
            val anims = (desc?.obj("animations"))?.entries?.mapNotNull { (short, v) ->
                val aid = v.asString ?: return@mapNotNull null
                if (!aid.startsWith("animation.")) return@mapNotNull null // contrôleurs ignorés
                AnimationRef(short, aid, animationFiles[aid])
            } ?: emptyList()
            val problems = buildList {
                if (client == null) add("Pas de fichier d'apparence : la créature sera invisible en jeu.")
                if (geoId != null && geometries[geoId] == null && !geoId.startsWith("geometry.humanoid")) add("Modèle « $geoId » introuvable dans le projet.")
                if (tex != null && png(tex) == null) add("Texture « $tex » introuvable (ou au format .tga, non affiché).")
                if (bpPath == null) add("Pas de comportement : apparence seule.")
            }
            return Entry(
                id = id, kind = Kind.ENTITY, name = name(Kind.ENTITY, id), behaviorPath = bpPath, clientPath = client?.first,
                geometryId = geoId, geometryPath = geoId?.let { geometries[it] }, texturePath = png(tex), animations = anims,
                stats = bp?.let { QuickEdit.readStats(Kind.ENTITY, it) } ?: emptyMap(), problems = problems,
            )
        }

        fun items(): List<Entry> {
            // Anciens objets : l'icône est dans RP/items.
            val legacyIcons = HashMap<String, String>()
            for (p in jsonFiles(files, "$RP/items")) {
                val it = readJson(files, p)?.path("minecraft:item") ?: continue
                val id = it.path("description", "identifier")?.asString ?: continue
                it.path("components", "minecraft:icon")?.asString?.let { icon -> legacyIcons[id] = icon }
            }
            return jsonFiles(files, "$BP/items").mapNotNull { p ->
                val root = readJson(files, p) ?: return@mapNotNull null
                val item = root.path("minecraft:item") ?: return@mapNotNull null
                val id = item.path("description", "identifier")?.asString ?: return@mapNotNull null
                val icon = item.path("components", "minecraft:icon")
                val key = icon?.asString ?: icon?.path("texture")?.asString ?: icon?.path("textures", "default")?.asString ?: legacyIcons[id]
                val tex = atlasTexture(itemAtlas, key)
                Entry(
                    id = id, kind = Kind.ITEM, name = name(Kind.ITEM, id), behaviorPath = p, clientPath = null,
                    geometryId = null, geometryPath = null, texturePath = tex,
                    stats = QuickEdit.readStats(Kind.ITEM, root),
                    problems = if (key != null && tex == null) listOf("Icône « $key » introuvable dans item_texture.json.") else emptyList(),
                )
            }.distinctBy { it.id }
        }

        fun blocks(): List<Entry> = jsonFiles(files, "$BP/blocks").mapNotNull { p ->
            val root = readJson(files, p) ?: return@mapNotNull null
            val block = root.path("minecraft:block") ?: return@mapNotNull null
            val id = block.path("description", "identifier")?.asString ?: return@mapNotNull null
            val comps = block.path("components") as? JsonObject
            val mats = comps?.obj("minecraft:material_instances")
            fun mat(vararg keys: String): String? {
                for (k in keys) {
                    val m = mats?.get(k) ?: continue
                    // Une instance peut renvoyer vers une autre (« "north": "side" »).
                    val resolved = m.asString?.let { mats[it] } ?: m
                    (resolved as? JsonObject)?.str("texture")?.let { return atlasTexture(terrainAtlas, it) }
                }
                return null
            }
            var faces = BlockFaces(mat("up", "*"), mat("down", "*"), mat("side", "north", "*"))
            if (faces.any == null) {
                // Ancien format : textures dans RP/blocks.json.
                val t = blocksJson?.get(id)?.path("textures")
                faces = when {
                    t?.asString != null -> atlasTexture(terrainAtlas, t.asString).let { BlockFaces(it, it, it) }
                    t is JsonObject -> BlockFaces(
                        atlasTexture(terrainAtlas, t.str("up")), atlasTexture(terrainAtlas, t.str("down")),
                        atlasTexture(terrainAtlas, t.str("side") ?: t.str("north")),
                    )
                    else -> faces
                }
            }
            val geo = comps?.get("minecraft:geometry")?.let { it.asString ?: it.path("identifier")?.asString }
                ?.takeIf { it != "minecraft:geometry.full_block" }
            Entry(
                id = id, kind = Kind.BLOCK, name = name(Kind.BLOCK, id), behaviorPath = p, clientPath = null,
                geometryId = geo, geometryPath = geo?.let { geometries[it] }, texturePath = faces.any, blockFaces = faces,
                stats = QuickEdit.readStats(Kind.BLOCK, root),
                problems = if (faces.any == null) listOf("Aucune texture trouvée pour ce bloc.") else emptyList(),
            )
        }.distinctBy { it.id }
    }
}
