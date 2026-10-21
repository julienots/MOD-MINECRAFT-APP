package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.image.PixelCanvas
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers
import java.util.Locale

enum class BlockStyle(val labelFr: String) {
    STONE("Pierre"), ORE("Minerai"), PLANKS("Planches"), BRICKS("Briques"), GLASS("Verre / cristal"), LAMP("Lampe"), PORTAL("Portail"),
}

/** Sons de blocs vanilla (blocks.json). */
enum class BlockSound(val id: String, val labelFr: String) {
    STONE("stone", "Pierre"), WOOD("wood", "Bois"), GRAVEL("gravel", "Gravier"), GLASS("glass", "Verre"),
    METAL("metal", "Métal"), SAND("sand", "Sable"), GRASS("grass", "Herbe"), CLOTH("cloth", "Laine"),
}

data class BlockSpec(
    val displayName: String,
    val shortId: String = Identifiers.slug(displayName).replace('.', '_').ifEmpty { "bloc" },
    val style: BlockStyle = BlockStyle.STONE,
    val mainColor: Int = 0xFF7E57C2.toInt(),
    val accentColor: Int = 0xFFFFD54F.toInt(),
    /** Temps pour casser à la main, en secondes (pierre ≈ 1,5 ; obsidienne ≈ 50). */
    val hardness: Double = 1.5,
    /** Résistance aux explosions (pierre = 6). */
    val explosionResistance: Double = 6.0,
    /** Lumière émise, de 0 à 15. */
    val lightEmission: Int = 0,
    val sound: BlockSound = BlockSound.STONE,
    /** Objet laissé en cassant le bloc ; null = le bloc lui-même. */
    val dropItem: String? = null,
    val dropMin: Int = 1,
    val dropMax: Int = 1,
) {
    fun validate(): List<String> = buildList {
        if (displayName.isBlank()) add("Le nom affiché est vide.")
        if (!Identifiers.isValidName(shortId)) add("L'identifiant « $shortId » ne doit contenir que des minuscules, chiffres et _.")
        if (hardness !in 0.0..100.0) add("La dureté doit être comprise entre 0 et 100 secondes.")
        if (explosionResistance !in 0.0..10_000.0) add("La résistance aux explosions doit être comprise entre 0 et 10 000.")
        if (lightEmission !in 0..15) add("La lumière doit être comprise entre 0 et 15.")
        if (dropItem != null && !Identifiers.isValidIdentifier(dropItem)) add("L'objet laissé « $dropItem » n'est pas un identifiant valide.")
        if (dropMin < 0 || dropMax < dropMin || dropMax > 64) add("Quantité laissée invalide (0 à 64).")
    }
}

object BlockGenerator {

    fun textureKey(namespace: String, shortId: String) = "${namespace}_$shortId"

    fun generate(namespace: String, spec: BlockSpec, profile: BedrockVersionProfile): GeneratedContent {
        val errors = spec.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val id = "$namespace:${spec.shortId}"
        val name = spec.shortId
        val texKey = textureKey(namespace, name)
        val c = GeneratedContent()
        c.text("$BP/blocks/$name.json", JsonWriter.write(block(id, name, texKey, spec, profile)) + "\n")
        c.bytes("$RP/textures/blocks/$name.png", texture(spec))
        c.terrainTexture(texKey, "textures/blocks/$name")
        c.blockSound(id, spec.sound.id)
        if (spec.dropItem != null) c.text("$BP/loot_tables/blocks/$name.json", JsonWriter.write(loot(spec)) + "\n")
        c.lang["tile.$id.name"] = spec.displayName
        return c
    }

    private fun block(id: String, name: String, texKey: String, s: BlockSpec, p: BedrockVersionProfile): JsonValue {
        // « alpha_test » : les pixels transparents de la texture sont découpés (verre, cristal).
        val renderMethod = if (s.style == BlockStyle.GLASS) "alpha_test" else "opaque"
        val components = linkedMapOf<String, Any>(
            "minecraft:geometry" to "minecraft:geometry.full_block",
            "minecraft:material_instances" to obj("*" to obj("texture" to texKey, "render_method" to renderMethod)),
            "minecraft:destructible_by_mining" to obj("seconds_to_destroy" to s.hardness),
            "minecraft:destructible_by_explosion" to obj("explosion_resistance" to s.explosionResistance),
            "minecraft:map_color" to String.format(Locale.ROOT, "#%06X", s.mainColor and 0xFFFFFF),
        )
        if (s.lightEmission > 0) components["minecraft:light_emission"] = s.lightEmission
        if (s.style == BlockStyle.GLASS) components["minecraft:light_dampening"] = 0
        if (s.dropItem != null) components["minecraft:loot"] = "loot_tables/blocks/$name.json"
        return obj(
            "format_version" to p.itemFormat,
            "minecraft:block" to obj(
                "description" to obj("identifier" to id, "menu_category" to obj("category" to "construction")),
                "components" to components,
            ),
        )
    }

    private fun loot(s: BlockSpec) = obj(
        "pools" to listOf(obj(
            "rolls" to 1,
            "entries" to listOf(obj(
                "type" to "item", "name" to s.dropItem, "weight" to 1,
                "functions" to listOf(obj("function" to "set_count", "count" to obj("min" to s.dropMin, "max" to s.dropMax))),
            )),
        )),
    )

    /** Texture 16×16 originale selon le style. */
    fun texture(s: BlockSpec): ByteArray {
        val c = PixelCanvas(16, 16, 0)
        val seed = s.shortId.hashCode()
        val main = s.mainColor; val accent = s.accentColor
        when (s.style) {
            BlockStyle.STONE -> c.noiseRect(0, 0, 16, 16, main, seed, 16)
            BlockStyle.ORE -> {
                c.noiseRect(0, 0, 16, 16, main, seed, 14)
                var r = seed
                repeat(6) {
                    r = r * 1103515245 + 12345
                    val x = ((r ushr 8) and 0xFF) % 13 + 1
                    val y = ((r ushr 16) and 0xFF) % 13 + 1
                    c.fillRect(x, y, 2, 2, accent); c[x + 1, y + 1] = PixelCanvas.shade(accent, -50); c[x, y] = PixelCanvas.shade(accent, 50)
                }
            }
            BlockStyle.PLANKS -> {
                c.noiseRect(0, 0, 16, 16, main, seed, 8)
                for (y in listOf(3, 7, 11, 15)) c.fillRect(0, y, 16, 1, PixelCanvas.shade(main, -55))
                for ((row, x) in listOf(0 to 5, 1 to 12, 2 to 3, 3 to 9)) c.fillRect(x, row * 4, 1, 3, PixelCanvas.shade(main, -40))
            }
            BlockStyle.BRICKS -> {
                c.fillRect(0, 0, 16, 16, accent)
                for (row in 0 until 4) {
                    val offset = if (row % 2 == 0) 0 else 4
                    for (b in -1 until 2) c.noiseRect(b * 8 + offset + 1, row * 4 + 1, 7, 3, main, seed + row * 7 + b, 12)
                }
            }
            BlockStyle.GLASS -> {
                for (i in 0 until 16) { c[i, 0] = main; c[i, 15] = main; c[0, i] = main; c[15, i] = main }
                c[3, 3] = PixelCanvas.shade(main, 60); c[4, 4] = PixelCanvas.shade(main, 60); c[4, 3] = PixelCanvas.shade(main, 40)
                c[11, 11] = PixelCanvas.shade(main, 40)
            }
            BlockStyle.PORTAL -> {
                // Spirale : la teinte varie avec la distance au centre et l'angle.
                for (y in 0 until 16) for (x in 0 until 16) {
                    val dx = x - 7.5; val dy = y - 7.5
                    val d = Math.sqrt(dx * dx + dy * dy)
                    val a = Math.atan2(dy, dx)
                    val wave = Math.sin(d * 1.3 - a * 2)
                    c[x, y] = PixelCanvas.shade(main, (wave * 45).toInt() + if (d < 2.5) 70 else 0)
                }
            }
            BlockStyle.LAMP -> {
                c.noiseRect(0, 0, 16, 16, PixelCanvas.shade(main, -40), seed, 10)
                c.noiseRect(3, 3, 10, 10, accent, seed + 1, 18)
                c.fillRect(6, 6, 4, 4, PixelCanvas.shade(accent, 70))
            }
        }
        return c.toPng()
    }
}
