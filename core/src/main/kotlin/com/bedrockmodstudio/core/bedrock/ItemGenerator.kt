package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.image.PixelArt
import com.bedrockmodstudio.core.image.PixelCanvas
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers

enum class ItemKind(val labelFr: String, val explanationFr: String) {
    SIMPLE("Objet simple", "Un objet de collection ou de fabrication (gemme, lingot…), empilable."),
    FOOD("Nourriture", "Se mange et rend des points de faim."),
    SWORD("Épée", "Arme de corps à corps."),
    PICKAXE("Pioche", "Casse la pierre et les métaux plus vite."),
    AXE("Hache", "Coupe le bois plus vite ; peut servir d'arme."),
    SHOVEL("Pelle", "Creuse la terre, le sable et le gravier plus vite."),
    HELMET("Casque", "Armure de tête."),
    CHESTPLATE("Plastron", "Armure de torse."),
    LEGGINGS("Jambières", "Armure de jambes."),
    BOOTS("Bottes", "Armure de pieds.");

    val isTool get() = this == PICKAXE || this == AXE || this == SHOVEL
    val isArmor get() = this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS
    val isWeaponOrTool get() = this == SWORD || isTool
    val hasDurability get() = isWeaponOrTool || isArmor
}

/** Niveau d'outil : vitesse de minage (valeurs vanilla) et étiquette de niveau. */
enum class ToolTier(val labelFr: String, val speed: Int, val tag: String) {
    WOOD("Bois", 2, "minecraft:wooden_tier"),
    STONE("Pierre", 4, "minecraft:stone_tier"),
    IRON("Fer", 6, "minecraft:iron_tier"),
    DIAMOND("Diamant", 8, "minecraft:diamond_tier"),
    NETHERITE("Netherite", 9, "minecraft:netherite_tier"),
}

data class ItemSpec(
    val displayName: String,
    val shortId: String = Identifiers.slug(displayName).replace('.', '_').ifEmpty { "objet" },
    val kind: ItemKind = ItemKind.SIMPLE,
    val mainColor: Int = 0xFF4FC3F7.toInt(),
    val accentColor: Int = 0xFF8D6E63.toInt(),
    val maxStack: Int = 64,
    /** Dégâts (épée, outils). */
    val damage: Int = 6,
    /** Durabilité (armes, outils, armures). */
    val durability: Int = 500,
    val tier: ToolTier = ToolTier.IRON,
    /** Points de protection (armures). */
    val protection: Int = 3,
    val nutrition: Int = 4,
    val saturation: Double = 0.6,
    val canAlwaysEat: Boolean = false,
    /** Facilité d'enchantement (10 = fer, 22 = or). */
    val enchantability: Int = 10,
    /** Objet utilisé pour réparer sur l'enclume. */
    val repairItem: String? = null,
    val glint: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        if (displayName.isBlank()) add("Le nom affiché est vide.")
        if (!Identifiers.isValidName(shortId)) add("L'identifiant « $shortId » ne doit contenir que des minuscules, chiffres et _.")
        if (maxStack !in 1..64) add("La taille de pile doit être comprise entre 1 et 64.")
        if (damage !in 0..1000) add("Les dégâts doivent être compris entre 0 et 1000.")
        if (durability !in 1..100_000) add("La durabilité doit être comprise entre 1 et 100 000.")
        if (protection !in 0..100) add("La protection doit être comprise entre 0 et 100.")
        if (nutrition !in 0..20) add("La nourriture rendue doit être comprise entre 0 et 20.")
        if (saturation !in 0.0..10.0) add("La saturation doit être comprise entre 0 et 10.")
        if (enchantability !in 0..100) add("L'enchantabilité doit être comprise entre 0 et 100.")
        if (repairItem != null && !Identifiers.isValidIdentifier(repairItem)) add("L'objet de réparation « $repairItem » n'est pas un identifiant valide.")
    }
}

object ItemGenerator {

    fun textureKey(namespace: String, shortId: String) = "${namespace}_$shortId"

    fun generate(namespace: String, spec: ItemSpec, profile: BedrockVersionProfile): GeneratedContent {
        val errors = spec.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val id = "$namespace:${spec.shortId}"
        val name = spec.shortId
        val texKey = textureKey(namespace, name)
        val c = GeneratedContent()
        c.text("$BP/items/$name.json", JsonWriter.write(item(id, texKey, spec, profile)) + "\n")
        c.bytes("$RP/textures/items/$name.png", icon(spec))
        c.itemTexture(texKey, "textures/items/$name")
        if (spec.kind.isArmor) {
            val armorTexture = "textures/models/armor/${namespace}_$name"
            c.bytes("$RP/$armorTexture.png", armorLayer(spec))
            c.text("$RP/attachables/$name.json", JsonWriter.write(attachable(id, armorTexture, spec.kind, player = false)) + "\n")
            c.text("$RP/attachables/$name.player.json", JsonWriter.write(attachable(id, armorTexture, spec.kind, player = true)) + "\n")
        }
        c.lang["item.$id.name"] = spec.displayName
        return c
    }

    private fun item(id: String, texKey: String, s: ItemSpec, p: BedrockVersionProfile): JsonValue {
        val k = s.kind
        val components = linkedMapOf<String, Any>(
            "minecraft:icon" to obj("textures" to obj("default" to texKey)),
            "minecraft:display_name" to obj("value" to "item.$id.name"),
            "minecraft:max_stack_size" to if (k.hasDurability) 1 else s.maxStack,
        )
        val tags = mutableListOf<String>()
        if (k.hasDurability) {
            components["minecraft:durability"] = obj("max_durability" to s.durability)
            if (s.repairItem != null) components["minecraft:repairable"] = obj(
                "repair_items" to listOf(obj("items" to listOf(s.repairItem), "repair_amount" to "query.max_durability * 0.25")),
            )
        }
        if (k.isWeaponOrTool) {
            components["minecraft:hand_equipped"] = obj("value" to true)
            components["minecraft:damage"] = obj("value" to s.damage)
        }
        when (k) {
            ItemKind.SIMPLE -> Unit
            ItemKind.FOOD -> {
                components["minecraft:food"] = obj("nutrition" to s.nutrition, "saturation_modifier" to s.saturation, "can_always_eat" to s.canAlwaysEat)
                components["minecraft:use_animation"] = obj("value" to "eat")
                components["minecraft:use_modifiers"] = obj("start_using" to "always", "use_duration" to 1.6, "movement_modifier" to 0.35)
                tags += "minecraft:is_food"
            }
            ItemKind.SWORD -> {
                components["minecraft:can_destroy_in_creative"] = obj("value" to false)
                components["minecraft:enchantable"] = obj("slot" to "sword", "value" to s.enchantability)
                tags += "minecraft:is_sword"; tags += s.tier.tag
            }
            ItemKind.PICKAXE, ItemKind.AXE, ItemKind.SHOVEL -> {
                val blockTags = when (k) {
                    ItemKind.PICKAXE -> "'stone', 'metal'"
                    ItemKind.AXE -> "'wood'"
                    else -> "'dirt', 'sand', 'gravel', 'grass', 'snow'"
                }
                components["minecraft:digger"] = obj(
                    "use_efficiency" to true,
                    "destroy_speeds" to listOf(obj("block" to obj("tags" to "query.any_tag($blockTags)"), "speed" to s.tier.speed)),
                )
                val slot = when (k) { ItemKind.PICKAXE -> "pickaxe"; ItemKind.AXE -> "axe"; else -> "shovel" }
                components["minecraft:enchantable"] = obj("slot" to slot, "value" to s.enchantability)
                tags += "minecraft:is_$slot"; tags += "minecraft:is_tool"; tags += s.tier.tag
            }
            ItemKind.HELMET, ItemKind.CHESTPLATE, ItemKind.LEGGINGS, ItemKind.BOOTS -> {
                val (slot, enchantSlot) = when (k) {
                    ItemKind.HELMET -> "slot.armor.head" to "armor_head"
                    ItemKind.CHESTPLATE -> "slot.armor.chest" to "armor_torso"
                    ItemKind.LEGGINGS -> "slot.armor.legs" to "armor_legs"
                    else -> "slot.armor.feet" to "armor_feet"
                }
                components["minecraft:wearable"] = obj("slot" to slot, "protection" to s.protection)
                components["minecraft:enchantable"] = obj("slot" to enchantSlot, "value" to s.enchantability)
                tags += "minecraft:is_armor"
            }
        }
        if (s.glint) components["minecraft:glint"] = true
        if (tags.isNotEmpty()) components["minecraft:tags"] = obj("tags" to tags)

        val (category, group) = when (k) {
            ItemKind.SIMPLE, ItemKind.FOOD -> "items" to null
            ItemKind.SWORD -> "equipment" to "minecraft:itemGroup.name.sword"
            ItemKind.PICKAXE -> "equipment" to "minecraft:itemGroup.name.pickaxe"
            ItemKind.AXE -> "equipment" to "minecraft:itemGroup.name.axe"
            ItemKind.SHOVEL -> "equipment" to "minecraft:itemGroup.name.shovel"
            ItemKind.HELMET -> "equipment" to "minecraft:itemGroup.name.helmet"
            ItemKind.CHESTPLATE -> "equipment" to "minecraft:itemGroup.name.chestplate"
            ItemKind.LEGGINGS -> "equipment" to "minecraft:itemGroup.name.leggings"
            ItemKind.BOOTS -> "equipment" to "minecraft:itemGroup.name.boots"
        }
        return obj(
            "format_version" to p.itemFormat,
            "minecraft:item" to obj(
                "description" to obj("identifier" to id, "menu_category" to obj("category" to category, "group" to group)),
                "components" to components,
            ),
        )
    }

    /** Fichier « attachable » : rend l'armure visible sur le corps, comme les armures vanilla. */
    private fun attachable(id: String, texture: String, kind: ItemKind, player: Boolean): JsonValue {
        val (part, layerVar) = when (kind) {
            ItemKind.HELMET -> "helmet" to "helmet_layer_visible"
            ItemKind.CHESTPLATE -> "chestplate" to "chest_layer_visible"
            ItemKind.LEGGINGS -> "leggings" to "leg_layer_visible"
            else -> "boots" to "boot_layer_visible"
        }
        val scripts = linkedMapOf<String, Any>("parent_setup" to "variable.$layerVar = 0.0;")
        if (player) scripts["animate"] = listOf("offset")
        return obj(
            "format_version" to if (player) "1.10.0" else "1.8.0",
            "minecraft:attachable" to obj(
                "description" to obj(
                    "identifier" to if (player) "$id.player" else id,
                    "item" to if (player) obj(id to "query.owner_identifier == 'minecraft:player'") else null,
                    "materials" to obj("default" to "armor", "enchanted" to "armor_enchanted"),
                    "textures" to obj("default" to texture, "enchanted" to "textures/misc/enchanted_actor_glint"),
                    "geometry" to obj("default" to if (player) "geometry.player.armor.$part" else "geometry.humanoid.armor.$part"),
                    "scripts" to scripts,
                    "animations" to if (player) obj("offset" to "animation.armor.$part.offset") else null,
                    "render_controllers" to listOf("controller.render.armor"),
                ),
            ),
        )
    }

    fun icon(s: ItemSpec): ByteArray {
        val template = when (s.kind) {
            ItemKind.SIMPLE -> PixelArt.GEM
            ItemKind.FOOD -> PixelArt.FOOD
            ItemKind.SWORD -> PixelArt.SWORD
            ItemKind.PICKAXE -> PixelArt.PICKAXE
            ItemKind.AXE -> PixelArt.AXE
            ItemKind.SHOVEL -> PixelArt.SHOVEL
            ItemKind.HELMET -> PixelArt.HELMET
            ItemKind.CHESTPLATE -> PixelArt.CHESTPLATE
            ItemKind.LEGGINGS -> PixelArt.LEGGINGS
            ItemKind.BOOTS -> PixelArt.BOOTS
        }
        return PixelArt.render(template, s.mainColor, s.accentColor).toPng()
    }

    /** Texture d'armure 64×32 au format des couches d'armure vanilla : remplie entièrement, avec liserés. */
    fun armorLayer(s: ItemSpec): ByteArray {
        val c = PixelCanvas(64, 32, 0)
        c.noiseRect(0, 0, 64, 32, s.mainColor, s.shortId.hashCode(), 14)
        for (y in listOf(8, 20, 31)) c.fillRect(0, y, 64, 1, PixelCanvas.shade(s.accentColor, -20))
        return c.toPng()
    }
}
