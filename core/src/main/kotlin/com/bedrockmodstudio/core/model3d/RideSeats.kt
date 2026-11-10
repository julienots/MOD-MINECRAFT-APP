package com.bedrockmodstudio.core.model3d

import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.storage.UserFacingException

/** Une place sur la créature. [position] est en blocs, relative aux pieds de la créature (comme dans `minecraft:rideable`). */
data class Seat(
    val position: Vec3,
    /** Rotation du passager autour de la verticale (degrés), par ex. 180 pour s'asseoir à l'envers. */
    val rotateRiderBy: Double = 0.0,
    /** Angle maximal dont le passager peut tourner la tête/le corps ; null = libre. */
    val lockRiderRotation: Double? = null,
)

enum class RiderKind(val labelFr: String, val families: List<String>) {
    PLAYERS("Joueurs seulement", listOf("player")),
    PLAYERS_AND_MOBS("Joueurs et créatures", listOf("player", "zombie", "skeleton", "villager", "pillager")),
}

data class RideSpec(
    val seats: List<Seat> = listOf(Seat(Vec3(0.0, 1.0, 0.0))),
    val riders: RiderKind = RiderKind.PLAYERS,
    /** Le joueur sur la première place dirige la créature (touches de déplacement). */
    val controllable: Boolean = true,
    /** Les créatures proches peuvent être embarquées automatiquement (comme un bateau). */
    val pullInEntities: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        if (seats.isEmpty()) add("Ajoute au moins une place.")
        if (seats.size > 8) add("8 places au maximum.")
        seats.forEachIndexed { i, s ->
            val p = s.position
            if (listOf(p.x, p.z).any { it !in -8.0..8.0 } || p.y !in -2.0..16.0) add("Place ${i + 1} : position trop loin de la créature.")
        }
    }
}

/**
 * Places pour monter sur une créature (`minecraft:rideable`, Microsoft Learn : seat_count, family_types,
 * interact_text, pull_in_entities, controlling_seat, seats[].position / rotate_rider_by / lock_rider_rotation).
 * Diriger la créature : `minecraft:input_ground_controlled` (utilisé par le cheval sellé dans les fichiers vanilla).
 */
object RideSeats {
    data class Creature(val id: String, val path: String)

    /** Apparence de la créature pour l'aperçu. */
    data class Look(val model: GeoModel?, val texturePath: String?, val scale: Double, val collisionWidth: Double, val collisionHeight: Double)

    /** Placement du passager assis : sa hanche se trouve un peu au-dessus du point de la place (pixels). Approximation calée sur le cochon et le cheval vanilla. */
    const val RIDER_HIP_ABOVE_SEAT = 4.0

    fun creatures(files: ProjectFiles): List<Creature> {
        if (!files.exists("$BP/entities")) return emptyList()
        return files.walk("$BP/entities").filter { it.endsWith(".json") }.mapNotNull { p ->
            val v = runCatching { JsonParser.parse(files.readText(p)).valueOrNull() }.getOrNull() ?: return@mapNotNull null
            v.path("minecraft:entity", "description", "identifier")?.asString?.let { Creature(it, p) }
        }.sortedBy { it.id }
    }

    private fun components(root: JsonValue?): JsonObject? = root.path("minecraft:entity", "components") as? JsonObject

    /** Places déjà déclarées (composant principal ou premier groupe qui en contient), ou null. */
    fun read(entityText: String): RideSpec? {
        val root = JsonParser.parse(entityText).valueOrNull()
        val comps = components(root)
        val r = comps?.obj("minecraft:rideable")
            ?: (root.path("minecraft:entity", "component_groups") as? JsonObject)?.entries?.firstNotNullOfOrNull { (it.value as? JsonObject)?.obj("minecraft:rideable") }
            ?: return null
        val seatsValue = r["seats"]
        val seatObjs = when (seatsValue) { is JsonArray -> seatsValue.items.mapNotNull { it as? JsonObject }; is JsonObject -> listOf(seatsValue); else -> emptyList() }
        val seats = seatObjs.map { s ->
            Seat(Vec3.of(s["position"]), s["rotate_rider_by"]?.asDouble ?: 0.0, s["lock_rider_rotation"]?.asDouble)
        }.ifEmpty { listOf(Seat(Vec3(0.0, 1.0, 0.0))) }
        val families = (r.arr("family_types")?.items ?: emptyList()).mapNotNull { it.asString }
        return RideSpec(
            seats = seats,
            riders = if (families.size > 1) RiderKind.PLAYERS_AND_MOBS else RiderKind.PLAYERS,
            controllable = comps?.containsKey("minecraft:input_ground_controlled") == true,
            pullInEntities = r["pull_in_entities"]?.asBoolean ?: false,
        )
    }

    fun rideable(spec: RideSpec): JsonObject {
        fun r(d: Double) = Math.round(d * 100) / 100.0
        return obj(
            "seat_count" to spec.seats.size,
            "controlling_seat" to 0,
            "crouching_skip_interact" to true,
            "family_types" to spec.riders.families,
            "interact_text" to "action.interact.mount",
            "pull_in_entities" to spec.pullInEntities,
            "seats" to spec.seats.map { s ->
                obj(
                    "position" to listOf(r(s.position.x), r(s.position.y), r(s.position.z)),
                    "min_rider_count" to 0,
                    "max_rider_count" to spec.seats.size,
                    "rotate_rider_by" to if (s.rotateRiderBy != 0.0) r(s.rotateRiderBy) else null,
                    "lock_rider_rotation" to s.lockRiderRotation?.let(::r),
                )
            },
        )
    }

    /** Écrit les places dans le fichier de comportement de la créature, sans toucher au reste. */
    fun apply(files: ProjectFiles, creature: Creature, spec: RideSpec): GeneratedContent {
        spec.validate().firstOrNull()?.let { throw UserFacingException(it) }
        val root = JsonParser.parseOrThrow(files.readText(creature.path)) as? JsonObject ?: throw UserFacingException("Fichier de créature illisible.")
        val comps = components(root) ?: throw UserFacingException("Ce fichier n'a pas de section « components ».")
        comps["minecraft:rideable"] = rideable(spec)
        if (spec.controllable) {
            comps["minecraft:input_ground_controlled"] = JsonObject()
            if (!comps.containsKey("minecraft:movement.basic")) comps["minecraft:movement.basic"] = JsonObject()
        } else comps.remove("minecraft:input_ground_controlled")
        return GeneratedContent().apply { text(creature.path, JsonWriter.write(root) + "\n") }
    }

    /** Modèle, texture et taille de la créature, pour la montrer telle qu'elle apparaîtra. */
    fun look(files: ProjectFiles, creature: Creature): Look {
        val bp = runCatching { JsonParser.parse(files.readText(creature.path)).valueOrNull() }.getOrNull()
        val comps = components(bp)
        val scale = comps?.obj("minecraft:scale")?.get("value")?.asDouble ?: 1.0
        val box = comps?.obj("minecraft:collision_box")
        var geometryId: String? = null; var texture: String? = null
        if (files.exists("$RP/entity")) for (p in files.walk("$RP/entity").filter { it.endsWith(".json") }) {
            val v = runCatching { JsonParser.parse(files.readText(p)).valueOrNull() }.getOrNull() ?: continue
            val desc = v.path("minecraft:client_entity", "description") ?: continue
            if (desc.path("identifier")?.asString != creature.id) continue
            geometryId = (desc.path("geometry") as? JsonObject)?.entries?.firstOrNull()?.value?.asString
            texture = (desc.path("textures") as? JsonObject)?.entries?.firstOrNull()?.value?.asString
            break
        }
        val model = geometryId?.let { findGeometry(files, it) }
        val png = texture?.let { "$RP/$it.png" }?.takeIf { files.exists(it) }
        return Look(model, png, scale, box?.get("width")?.asDouble ?: 0.9, box?.get("height")?.asDouble ?: 0.9)
    }

    fun findGeometry(files: ProjectFiles, id: String): GeoModel? {
        if (!files.exists("$RP/models")) return null
        for (p in files.walk("$RP/models").filter { it.endsWith(".json") }) {
            val g = runCatching { GeoCodec.parse(files.readText(p)) }.getOrNull() ?: continue
            g.models.firstOrNull { it.identifier == id }?.let { return it }
        }
        return null
    }

    /** Boîte de remplacement quand la créature n'a pas de modèle lisible (taille de collision). */
    fun placeholder(look: Look): GeoModel {
        val w = look.collisionWidth * 16; val h = look.collisionHeight * 16
        return GeoModel("geometry.bms.placeholder", 16, 16, listOf(Bone("body", cubes = listOf(Cube(Vec3(-w / 2, 0.0, -w / 2), Vec3(w, h, w))))))
    }

    /** Première place proposée : au milieu, un peu sous le haut de la créature (arrondi à 0,05 bloc). */
    fun defaultSeat(model: GeoModel, scale: Double): Seat {
        val (min, max) = ModelBinding.bounds(model)
        val y = max.y * scale / 16 * 0.75
        val z = (min.z + max.z) / 2 * scale / 16
        fun r(d: Double) = Math.round(d * 20) / 20.0
        return Seat(Vec3(0.0, r(y), r(z)))
    }

    /** Pose assise : jambes vers l'avant, bras légèrement tendus. */
    val SITTING = mapOf(
        "rightLeg" to BonePose(rotation = Vec3(-80.0, 10.0, 0.0)),
        "leftLeg" to BonePose(rotation = Vec3(-80.0, -10.0, 0.0)),
        "rightArm" to BonePose(rotation = Vec3(-35.0, 0.0, 0.0)),
        "leftArm" to BonePose(rotation = Vec3(-35.0, 0.0, 0.0)),
    )

    /** Faces de la créature (à sa taille) et d'un passager assis sur chaque place. */
    fun creatureFaces(model: GeoModel, scale: Double): List<MeshFace> {
        val s = Mat4.scale(Vec3(scale, scale, scale))
        return MeshBuilder.build(model).map { MeshBuilder.transform(it, s) }
    }

    fun riderFaces(seat: Seat, index: Int): List<MeshFace> {
        val hipY = HeldItem.mannequin.bone("rightLeg")!!.pivot.y
        val at = Vec3(seat.position.x * 16, seat.position.y * 16 + RIDER_HIP_ABOVE_SEAT - hipY, seat.position.z * 16)
        val m = Mat4.translate(at) * Mat4.rotateAround(Vec3.ZERO, Vec3(0.0, seat.rotateRiderBy, 0.0))
        return MeshBuilder.build(HeldItem.mannequin, SITTING).map { MeshBuilder.transform(it, m).copy(boneIndex = -1 - index) }
    }
}
