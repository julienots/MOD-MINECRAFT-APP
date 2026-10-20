package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.image.PixelCanvas
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers
import java.util.Locale

enum class MobTemperament(val labelFr: String, val explanationFr: String) {
    PASSIVE("Passif", "Ne se bat jamais et s'enfuit quand on le frappe."),
    NEUTRAL("Neutre", "Se défend seulement s'il est attaqué."),
    HOSTILE("Hostile", "Attaque les joueurs qu'il voit."),
}

/**
 * Paramètres d'un mob simple (créature quadrupède au modèle original).
 * L'assistant complet de la phase 3 étendra ces paramètres ; ceux-ci sont déjà tous traduits en fichiers Bedrock.
 */
data class MobSpec(
    val displayName: String,
    val shortId: String = Identifiers.slug(displayName).replace('.', '_').ifEmpty { "creature" },
    val temperament: MobTemperament = MobTemperament.NEUTRAL,
    /** Points de vie (2 points = 1 cœur). */
    val health: Int = 20,
    /** Vitesse de déplacement Bedrock (0,25 ≈ zombie, 0,3 ≈ cochon rapide). */
    val speed: Double = 0.25,
    /** Dégâts par coup (2 = 1 cœur). Ignoré pour un mob passif. */
    val attackDamage: Int = 3,
    /** Distance (en blocs) à laquelle un mob hostile repère le joueur. */
    val followRange: Int = 16,
    /** Taille relative (1 = normale). */
    val scale: Double = 1.0,
    val bodyColor: Int = 0xFF4E8A3E.toInt(),
    val accentColor: Int = 0xFF2F5A23.toInt(),
    val xpOnDeath: Int = 5,
    val lootItem: String? = "minecraft:leather",
    val lootMin: Int = 0,
    val lootMax: Int = 2,
    val spawnNaturally: Boolean = false,
    val spawnWeight: Int = 8,
) {
    fun validate(): List<String> = buildList {
        if (displayName.isBlank()) add("Le nom affiché est vide.")
        if (!Identifiers.isValidName(shortId)) add("L'identifiant « $shortId » ne doit contenir que des minuscules, chiffres et _.")
        if (health !in 1..1024) add("La santé doit être comprise entre 1 et 1024.")
        if (speed !in 0.0..2.0) add("La vitesse doit être comprise entre 0 et 2.")
        if (attackDamage !in 0..1000) add("Les dégâts doivent être compris entre 0 et 1000.")
        if (followRange !in 1..128) add("La distance de détection doit être comprise entre 1 et 128 blocs.")
        if (scale !in 0.1..10.0) add("La taille doit être comprise entre 0,1 et 10.")
        if (lootItem != null && !Identifiers.isValidIdentifier(lootItem)) add("L'objet de butin « $lootItem » n'est pas un identifiant valide.")
        if (lootMin < 0 || lootMax < lootMin) add("Quantité de butin invalide.")
        if (xpOnDeath !in 0..10000) add("L'expérience doit être comprise entre 0 et 10000.")
    }
}

object MobGenerator {

    fun generate(namespace: String, spec: MobSpec, profile: BedrockVersionProfile): GeneratedContent {
        val errors = spec.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val id = "$namespace:${spec.shortId}"
        val name = spec.shortId
        val geometryId = "geometry.$namespace.$name"
        val animPrefix = "animation.$namespace.$name"
        val c = GeneratedContent()

        c.text("$BP/entities/$name.json", JsonWriter.write(behaviorEntity(id, name, spec, profile)) + "\n")
        c.text("$RP/entity/$name.entity.json", JsonWriter.write(clientEntity(id, name, geometryId, animPrefix, spec, profile)) + "\n")
        c.text("$RP/models/entity/$name.geo.json", JsonWriter.write(geometry(geometryId, profile)) + "\n")
        c.text("$RP/animations/$name.animation.json", JsonWriter.write(animations(animPrefix, profile)) + "\n")
        c.bytes("$RP/textures/entity/$name.png", texture(spec))
        if (spec.lootItem != null) {
            c.text("$BP/loot_tables/entities/$name.json", JsonWriter.write(lootTable(spec)) + "\n")
        }
        if (spec.spawnNaturally) {
            c.text("$BP/spawn_rules/$name.json", JsonWriter.write(spawnRules(id, spec, profile)) + "\n")
        }
        c.lang["entity.$id.name"] = spec.displayName
        c.lang["item.spawn_egg.entity.$id.name"] = "Œuf d'apparition : ${spec.displayName}"
        return c
    }

    private fun behaviorEntity(id: String, name: String, s: MobSpec, p: BedrockVersionProfile): JsonValue {
        val families = mutableListOf(name, "mob")
        families += if (s.temperament == MobTemperament.HOSTILE) "monster" else "creature"
        val components = linkedMapOf<String, Any>(
            "minecraft:type_family" to obj("family" to families),
            "minecraft:health" to obj("value" to s.health, "max" to s.health),
            "minecraft:movement" to obj("value" to s.speed),
            "minecraft:navigation.walk" to obj("can_walk" to true, "avoid_water" to true, "avoid_damage_blocks" to true),
            "minecraft:movement.basic" to obj(),
            "minecraft:jump.static" to obj(),
            "minecraft:physics" to obj(),
            "minecraft:pushable" to obj("is_pushable" to true, "is_pushable_by_piston" to true),
            "minecraft:breathable" to obj("total_supply" to 15, "suffocate_time" to 0),
            "minecraft:nameable" to obj(),
            "minecraft:collision_box" to obj("width" to round2(0.9 * s.scale), "height" to round2(0.9 * s.scale)),
            "minecraft:follow_range" to obj("value" to s.followRange, "max" to s.followRange),
            "minecraft:experience_reward" to obj("on_death" to "query.last_hit_by_player ? ${s.xpOnDeath} : 0"),
            "minecraft:behavior.float" to obj("priority" to 0),
            "minecraft:behavior.random_stroll" to obj("priority" to 6, "speed_multiplier" to 1.0),
            "minecraft:behavior.look_at_player" to obj("priority" to 7, "look_distance" to 6),
            "minecraft:behavior.random_look_around" to obj("priority" to 8),
        )
        if (s.scale != 1.0) components["minecraft:scale"] = obj("value" to s.scale)
        if (s.lootItem != null) components["minecraft:loot"] = obj("table" to "loot_tables/entities/$name.json")
        if (s.spawnNaturally) components["minecraft:despawn"] = obj("despawn_from_distance" to obj())
        when (s.temperament) {
            MobTemperament.PASSIVE ->
                components["minecraft:behavior.panic"] = obj("priority" to 1, "speed_multiplier" to 1.25)
            MobTemperament.NEUTRAL, MobTemperament.HOSTILE -> {
                components["minecraft:attack"] = obj("damage" to s.attackDamage)
                components["minecraft:behavior.hurt_by_target"] = obj("priority" to 1)
                components["minecraft:behavior.melee_box_attack"] = obj("priority" to 3, "speed_multiplier" to 1.2, "track_target" to true)
                if (s.temperament == MobTemperament.HOSTILE) {
                    components["minecraft:behavior.nearest_attackable_target"] = obj(
                        "priority" to 2, "must_see" to true, "reselect_targets" to true,
                        "entity_types" to listOf(obj(
                            "filters" to obj("test" to "is_family", "subject" to "other", "value" to "player"),
                            "max_dist" to s.followRange,
                        )),
                    )
                }
            }
        }
        return obj(
            "format_version" to p.entityBehaviorFormat,
            "minecraft:entity" to obj(
                "description" to obj(
                    "identifier" to id,
                    "is_spawnable" to true,
                    "is_summonable" to true,
                    "spawn_category" to if (s.temperament == MobTemperament.HOSTILE) "monster" else "creature",
                ),
                "components" to components,
            ),
        )
    }

    private fun clientEntity(id: String, name: String, geo: String, anim: String, s: MobSpec, p: BedrockVersionProfile) = obj(
        "format_version" to p.entityClientFormat,
        "minecraft:client_entity" to obj(
            "description" to obj(
                "identifier" to id,
                "materials" to obj("default" to "entity_alphatest"),
                "textures" to obj("default" to "textures/entity/$name"),
                "geometry" to obj("default" to geo),
                "animations" to obj("walk" to "$anim.walk", "look_at_target" to "$anim.look_at_target"),
                "scripts" to obj("animate" to listOf("walk", "look_at_target")),
                "render_controllers" to listOf("controller.render.default"),
                "spawn_egg" to obj("base_color" to hex(s.bodyColor), "overlay_color" to hex(s.accentColor)),
            ),
        ),
    )

    /** Quadrupède original : corps 10×8×14, tête 8×8×8, quatre pattes 4×6×4. Texture 64×64, UV en boîte. */
    private fun geometry(geo: String, p: BedrockVersionProfile) = obj(
        "format_version" to p.geometryFormat,
        "minecraft:geometry" to listOf(obj(
            "description" to obj(
                "identifier" to geo, "texture_width" to 64, "texture_height" to 64,
                "visible_bounds_width" to 3, "visible_bounds_height" to 2.5, "visible_bounds_offset" to listOf(0, 0.75, 0),
            ),
            "bones" to listOf(
                obj("name" to "body", "pivot" to listOf(0, 10, 0),
                    "cubes" to listOf(obj("origin" to listOf(-5, 6, -7), "size" to listOf(10, 8, 14), "uv" to listOf(0, 16)))),
                obj("name" to "head", "parent" to "body", "pivot" to listOf(0, 12, -7),
                    "cubes" to listOf(obj("origin" to listOf(-4, 9, -15), "size" to listOf(8, 8, 8), "uv" to listOf(0, 0)))),
                leg("leg0", -5, -7), leg("leg1", 1, -7), leg("leg2", -5, 3), leg("leg3", 1, 3),
            ),
        )),
    )

    private fun leg(name: String, x: Int, z: Int) = obj(
        "name" to name, "parent" to "body", "pivot" to listOf(x + 2, 6, z + 2),
        "cubes" to listOf(obj("origin" to listOf(x, 0, z), "size" to listOf(4, 6, 4), "uv" to listOf(0, 40))),
    )

    private fun animations(prefix: String, p: BedrockVersionProfile): JsonValue {
        val swing = "math.cos(query.modified_distance_moved * 38.17) * query.modified_move_speed * 80.0"
        return obj(
            "format_version" to p.animationFormat,
            "animations" to obj(
                "$prefix.walk" to obj("loop" to true, "bones" to obj(
                    "leg0" to obj("rotation" to listOf(swing, 0, 0)),
                    "leg3" to obj("rotation" to listOf(swing, 0, 0)),
                    "leg1" to obj("rotation" to listOf("-$swing", 0, 0)),
                    "leg2" to obj("rotation" to listOf("-$swing", 0, 0)),
                )),
                "$prefix.look_at_target" to obj("loop" to true, "bones" to obj(
                    "head" to obj("rotation" to listOf("query.target_x_rotation", "query.target_y_rotation", 0)),
                )),
            ),
        )
    }

    /** Texture procédurale originale correspondant exactement aux UV de [geometry]. */
    fun texture(s: MobSpec): ByteArray {
        val c = PixelCanvas(64, 64, 0)
        val seed = s.shortId.hashCode()
        // Tête 8×8×8 en (0,0) : zone 32×16.
        c.noiseRect(0, 0, 32, 16, s.bodyColor, seed)
        // Face avant (nord) de la tête : x 8..15, y 8..15 → yeux et museau.
        c.fillRect(9, 10, 2, 2, 0xFFFFFFFF.toInt()); c.fillRect(13, 10, 2, 2, 0xFFFFFFFF.toInt())
        c[10, 11] = 0xFF101010.toInt(); c[13, 11] = 0xFF101010.toInt()
        c.noiseRect(10, 13, 4, 2, s.accentColor, seed + 1)
        // Corps 10×8×14 en (0,16) : zone 48×22, avec une bande d'accent sur le dos.
        c.noiseRect(0, 16, 48, 22, s.bodyColor, seed + 2)
        c.noiseRect(14, 16, 10, 14, s.accentColor, seed + 3)
        // Pattes 4×6×4 en (0,40) : zone 16×10, extrémités plus sombres.
        c.noiseRect(0, 40, 16, 10, PixelCanvas.shade(s.bodyColor, -20), seed + 4)
        c.fillRect(0, 48, 16, 2, PixelCanvas.shade(s.accentColor, -30))
        return c.toPng()
    }

    private fun lootTable(s: MobSpec) = obj(
        "pools" to listOf(obj(
            "rolls" to 1,
            "entries" to listOf(obj(
                "type" to "item", "name" to s.lootItem, "weight" to 1,
                "functions" to listOf(obj("function" to "set_count", "count" to obj("min" to s.lootMin, "max" to s.lootMax))),
            )),
        )),
    )

    private fun spawnRules(id: String, s: MobSpec, p: BedrockVersionProfile): JsonValue {
        val hostile = s.temperament == MobTemperament.HOSTILE
        val brightness = if (hostile) obj("min" to 0, "max" to 7, "adjust_for_weather" to true)
        else obj("min" to 7, "max" to 15, "adjust_for_weather" to false)
        return obj(
            "format_version" to p.spawnRulesFormat,
            "minecraft:spawn_rules" to obj(
                "description" to obj("identifier" to id, "population_control" to if (hostile) "monster" else "animal"),
                "conditions" to listOf(obj(
                    "minecraft:spawns_on_surface" to obj(),
                    "minecraft:brightness_filter" to brightness,
                    "minecraft:weight" to obj("default" to s.spawnWeight),
                    "minecraft:herd" to obj("min_size" to 1, "max_size" to 2),
                    "minecraft:biome_filter" to obj("test" to "has_biome_tag", "value" to "overworld"),
                )),
            ),
        )
    }

    private fun hex(argb: Int) = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}
