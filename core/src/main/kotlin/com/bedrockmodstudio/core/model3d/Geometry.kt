package com.bedrockmodstudio.core.model3d

import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonNumber
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.jsonOf
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.storage.UserFacingException

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun toList() = listOf(x, y, z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun of(v: JsonValue?, default: Vec3 = ZERO): Vec3 {
            val a = v as? JsonArray ?: return default
            if (a.size != 3) return default
            return Vec3(a[0].asDouble ?: return default, a[1].asDouble ?: return default, a[2].asDouble ?: return default)
        }
    }
}

/** Cube d'un os. UV « boîte » ([uv] = coin du patron) ou UV par face (conservé tel quel dans [faceUv]). */
data class Cube(
    val origin: Vec3,
    val size: Vec3,
    val uv: Pair<Double, Double> = 0.0 to 0.0,
    val faceUv: JsonObject? = null,
    val inflate: Double = 0.0,
    val pivot: Vec3? = null,
    val rotation: Vec3? = null,
    val mirror: Boolean = false,
    /** Champs non gérés par l'éditeur, conservés à l'écriture. */
    val extra: JsonObject = JsonObject(),
)

data class Bone(
    val name: String,
    val parent: String? = null,
    val pivot: Vec3 = Vec3.ZERO,
    val rotation: Vec3 = Vec3.ZERO,
    val cubes: List<Cube> = emptyList(),
    val mirror: Boolean = false,
    val extra: JsonObject = JsonObject(),
)

data class GeoModel(
    val identifier: String,
    val textureWidth: Int = 64,
    val textureHeight: Int = 64,
    val bones: List<Bone> = emptyList(),
    val visibleBoundsWidth: Double = 3.0,
    val visibleBoundsHeight: Double = 3.0,
    val visibleBoundsOffset: Vec3 = Vec3(0.0, 1.0, 0.0),
    val descriptionExtra: JsonObject = JsonObject(),
) {
    fun bone(name: String) = bones.firstOrNull { it.name == name }

    /** Os enfants de [name] (ou racines si null). */
    fun children(name: String?) = bones.filter { it.parent == name }

    /** Vérifications de cohérence (noms uniques, parents existants, pas de boucle). */
    fun validate(): List<String> = buildList {
        if (!Regex("^geometry\\.[A-Za-z0-9_.]+$").matches(identifier)) add("Identifiant de modèle invalide « $identifier » (ex. geometry.monmod.dragon).")
        if (textureWidth !in 1..4096 || textureHeight !in 1..4096) add("Taille de texture invalide.")
        bones.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { add("Plusieurs os s'appellent « $it ».") }
        for (b in bones) {
            if (b.name.isBlank()) add("Un os n'a pas de nom.")
            if (b.parent != null && bone(b.parent) == null) add("L'os « ${b.name} » a pour parent « ${b.parent} », qui n'existe pas.")
            var p = b.parent; var depth = 0
            while (p != null && depth < 100) { if (p == b.name) { add("L'os « ${b.name} » est son propre ancêtre (boucle)."); break }; p = bone(p)?.parent; depth++ }
            b.cubes.forEachIndexed { i, c ->
                if (c.size.x < 0 || c.size.y < 0 || c.size.z < 0) add("Cube ${i + 1} de « ${b.name} » : taille négative.")
            }
        }
    }
}

/** Format choisi pour l'écriture : « minecraft:geometry » en tableau (1.12.0 et plus) ou ancien format par clés (1.8.0). */
data class GeoFile(val formatVersion: String, val models: List<GeoModel>, val legacy: Boolean)

object GeoCodec {

    fun parse(text: String): GeoFile {
        val root = (JsonParser.parse(text) as? JsonParseResult.Success)?.value as? JsonObject
            ?: throw UserFacingException("Ce fichier de modèle n'est pas un JSON valide.")
        val fv = root.str("format_version") ?: "1.12.0"
        val arr = root.arr("minecraft:geometry")
        if (arr != null) return GeoFile(fv, arr.items.mapNotNull { parseModern(it as? JsonObject) }, legacy = false)
        val legacy = root.entries.filter { it.key.startsWith("geometry.") }.mapNotNull { (k, v) -> parseLegacy(k, v as? JsonObject) }
        if (legacy.isEmpty()) throw UserFacingException("Aucun modèle trouvé : le fichier ne contient ni « minecraft:geometry » ni « geometry.… ».")
        return GeoFile(fv, legacy, legacy = true)
    }

    private fun parseModern(o: JsonObject?): GeoModel? {
        o ?: return null
        val d = o.obj("description") ?: return null
        val extra = JsonObject(d.entries.filter { it.key !in setOf("identifier", "texture_width", "texture_height", "visible_bounds_width", "visible_bounds_height", "visible_bounds_offset") }
            .associate { it.key to it.value })
        return GeoModel(
            identifier = d.str("identifier") ?: return null,
            textureWidth = d["texture_width"]?.asInt ?: 64,
            textureHeight = d["texture_height"]?.asInt ?: 64,
            bones = (o.arr("bones")?.items ?: emptyList()).mapNotNull { parseBone(it as? JsonObject) },
            visibleBoundsWidth = d["visible_bounds_width"]?.asDouble ?: 3.0,
            visibleBoundsHeight = d["visible_bounds_height"]?.asDouble ?: 3.0,
            visibleBoundsOffset = Vec3.of(d["visible_bounds_offset"], Vec3(0.0, 1.0, 0.0)),
            descriptionExtra = extra,
        )
    }

    private fun parseLegacy(key: String, o: JsonObject?): GeoModel? {
        o ?: return null
        return GeoModel(
            identifier = key.substringBefore(':'),
            textureWidth = o["texturewidth"]?.asInt ?: 64,
            textureHeight = o["textureheight"]?.asInt ?: 64,
            bones = (o.arr("bones")?.items ?: emptyList()).mapNotNull { parseBone(it as? JsonObject) },
            visibleBoundsWidth = o["visible_bounds_width"]?.asDouble ?: 3.0,
            visibleBoundsHeight = o["visible_bounds_height"]?.asDouble ?: 3.0,
            visibleBoundsOffset = Vec3.of(o["visible_bounds_offset"], Vec3(0.0, 1.0, 0.0)),
        )
    }

    private val boneKeys = setOf("name", "parent", "pivot", "rotation", "cubes", "mirror")
    private val cubeKeys = setOf("origin", "size", "uv", "inflate", "pivot", "rotation", "mirror")

    private fun parseBone(o: JsonObject?): Bone? {
        o ?: return null
        return Bone(
            name = o.str("name") ?: return null,
            parent = o.str("parent"),
            pivot = Vec3.of(o["pivot"]),
            rotation = Vec3.of(o["rotation"]),
            cubes = (o.arr("cubes")?.items ?: emptyList()).mapNotNull { c -> parseCube(c as? JsonObject) },
            mirror = o["mirror"]?.asBoolean ?: false,
            extra = JsonObject(o.entries.filter { it.key !in boneKeys }.associate { it.key to it.value }),
        )
    }

    private fun parseCube(o: JsonObject?): Cube? {
        o ?: return null
        val uvValue = o["uv"]
        val boxUv = (uvValue as? JsonArray)?.let { (it[0].asDouble ?: 0.0) to (it[1].asDouble ?: 0.0) }
        return Cube(
            origin = Vec3.of(o["origin"]),
            size = Vec3.of(o["size"], Vec3(1.0, 1.0, 1.0)),
            uv = boxUv ?: (0.0 to 0.0),
            faceUv = uvValue as? JsonObject,
            inflate = o["inflate"]?.asDouble ?: 0.0,
            pivot = o["pivot"]?.let { Vec3.of(it) },
            rotation = o["rotation"]?.let { Vec3.of(it) },
            mirror = o["mirror"]?.asBoolean ?: false,
            extra = JsonObject(o.entries.filter { it.key !in cubeKeys }.associate { it.key to it.value }),
        )
    }

    private fun num(d: Double): JsonNumber = JsonNumber(d)
    private fun vec(v: Vec3) = JsonArray(v.toList().map(::num))

    /** Écrit au format moderne « minecraft:geometry » (le format ancien est converti). */
    fun write(file: GeoFile): String {
        val fv = if (file.legacy || file.formatVersion < "1.12.0") "1.12.0" else file.formatVersion
        val models = file.models.map { m ->
            val desc = JsonObject()
            desc["identifier"] = jsonOf(m.identifier)
            desc["texture_width"] = jsonOf(m.textureWidth)
            desc["texture_height"] = jsonOf(m.textureHeight)
            desc["visible_bounds_width"] = num(m.visibleBoundsWidth)
            desc["visible_bounds_height"] = num(m.visibleBoundsHeight)
            desc["visible_bounds_offset"] = vec(m.visibleBoundsOffset)
            m.descriptionExtra.entries.forEach { desc[it.key] = it.value }
            obj("description" to desc, "bones" to m.bones.map { b ->
                val o = JsonObject()
                o["name"] = jsonOf(b.name)
                b.parent?.let { o["parent"] = jsonOf(it) }
                o["pivot"] = vec(b.pivot)
                if (b.rotation != Vec3.ZERO) o["rotation"] = vec(b.rotation)
                if (b.mirror) o["mirror"] = jsonOf(true)
                b.extra.entries.forEach { o[it.key] = it.value }
                if (b.cubes.isNotEmpty()) o["cubes"] = JsonArray(b.cubes.map { c ->
                    val co = JsonObject()
                    co["origin"] = vec(c.origin)
                    co["size"] = vec(c.size)
                    co["uv"] = c.faceUv ?: JsonArray(listOf(num(c.uv.first), num(c.uv.second)))
                    if (c.inflate != 0.0) co["inflate"] = num(c.inflate)
                    c.pivot?.let { co["pivot"] = vec(it) }
                    c.rotation?.let { co["rotation"] = vec(it) }
                    if (c.mirror) co["mirror"] = jsonOf(true)
                    c.extra.entries.forEach { co[it.key] = it.value }
                    co
                })
                o
            })
        }
        return JsonWriter.write(obj("format_version" to fv, "minecraft:geometry" to models)) + "\n"
    }

    /** Nouveau modèle de départ : un os racine et un cube. */
    fun starter(identifier: String, textureWidth: Int = 64, textureHeight: Int = 64) = GeoModel(
        identifier = identifier, textureWidth = textureWidth, textureHeight = textureHeight,
        bones = listOf(Bone("root", pivot = Vec3.ZERO), Bone("body", parent = "root", pivot = Vec3(0.0, 8.0, 0.0),
            cubes = listOf(Cube(Vec3(-4.0, 0.0, -4.0), Vec3(8.0, 8.0, 8.0), 0.0 to 0.0)))),
    )
}
