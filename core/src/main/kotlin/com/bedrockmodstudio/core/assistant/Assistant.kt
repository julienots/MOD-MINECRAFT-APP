package com.bedrockmodstudio.core.assistant

import com.bedrockmodstudio.core.bedrock.BedrockVersionProfile
import com.bedrockmodstudio.core.bedrock.BlockGenerator
import com.bedrockmodstudio.core.bedrock.BlockSpec
import com.bedrockmodstudio.core.bedrock.BlockStyle
import com.bedrockmodstudio.core.bedrock.BossSpec
import com.bedrockmodstudio.core.bedrock.DimensionGenerator
import com.bedrockmodstudio.core.bedrock.DimensionSpec
import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.ItemGenerator
import com.bedrockmodstudio.core.bedrock.ItemKind
import com.bedrockmodstudio.core.bedrock.ItemSpec
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.bedrock.MobTemperament
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonBool
import com.bedrockmodstudio.core.json.JsonNumber
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.arr
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.particle.ParticleGenerator
import com.bedrockmodstudio.core.particle.ParticlePreset
import com.bedrockmodstudio.core.structure.StructureGenerator
import com.bedrockmodstudio.core.structure.StructurePreset
import com.bedrockmodstudio.core.structure.StructureSpec
import java.text.Normalizer

enum class StepType(val key: String, val labelFr: String) {
    CREATURE("creature", "Créature"), ITEM("item", "Objet"), BLOCK("block", "Bloc"), PARTICLE("particle", "Particules"),
    STRUCTURE("structure", "Structure"), DIMENSION("dimension", "Dimension"),
}

/**
 * Une étape du plan : uniquement des paramètres simples, jamais de code. Elle est convertie en fichiers par les
 * générateurs de l'application (les mêmes que les assistants), après validation.
 */
data class PlanStep(
    val type: StepType,
    val name: String,
    val itemKind: ItemKind? = null,
    val temperament: MobTemperament? = null,
    val boss: Boolean = false,
    val health: Int? = null,
    val damage: Int? = null,
    val speed: Double? = null,
    val color: Int? = null,
    val light: Int? = null,
    val blockStyle: BlockStyle? = null,
    val particlePreset: ParticlePreset? = null,
    val structurePreset: StructurePreset? = null,
) {
    val shortId get() = Identifiers.slug(name).replace('.', '_').take(40).ifEmpty { type.key }

    fun summaryFr(): String = buildString {
        append(type.labelFr).append(" « ").append(name).append(" »")
        val details = listOfNotNull(
            itemKind?.labelFr?.lowercase(), temperament?.labelFr?.lowercase(), "boss".takeIf { boss },
            health?.let { "$it points de vie" }, damage?.let { "$it de dégâts" }, speed?.let { "vitesse $it" },
            light?.let { "lumière $it" }, blockStyle?.labelFr?.lowercase(), particlePreset?.labelFr?.lowercase(), structurePreset?.labelFr?.lowercase(),
            color?.let { String.format("couleur #%06X", it and 0xFFFFFF) },
        )
        if (details.isNotEmpty()) append(" : ").append(details.joinToString(", "))
    }

    fun generate(ns: String, profile: BedrockVersionProfile): GeneratedContent = when (type) {
        StepType.CREATURE -> MobGenerator.generate(ns, MobSpec(
            displayName = name, shortId = shortId, temperament = temperament ?: MobTemperament.NEUTRAL,
            health = health ?: if (boss) 200 else 20, attackDamage = damage ?: if (boss) 8 else 3, speed = speed ?: 0.25,
            bodyColor = color ?: 0xFF4E8A3E.toInt(), accentColor = darker(color ?: 0xFF4E8A3E.toInt()),
            scale = if (boss) 2.0 else 1.0, boss = if (boss) BossSpec() else null,
        ), profile)
        StepType.ITEM -> ItemGenerator.generate(ns, ItemSpec(
            displayName = name, shortId = shortId, kind = itemKind ?: ItemKind.SIMPLE, mainColor = color ?: 0xFF4FC3F7.toInt(),
            damage = damage ?: 6, glint = boss,
        ), profile)
        StepType.BLOCK -> BlockGenerator.generate(ns, BlockSpec(
            displayName = name, shortId = shortId, style = blockStyle ?: if ((light ?: 0) > 0) BlockStyle.LAMP else BlockStyle.STONE,
            mainColor = color ?: 0xFF7E57C2.toInt(), lightEmission = light ?: 0,
        ), profile)
        StepType.PARTICLE -> ParticleGenerator.generate(ns, shortId, particlePreset ?: ParticlePreset.MAGIC)
        StepType.STRUCTURE -> StructureGenerator.generate(ns, StructureSpec(name, shortId), (structurePreset ?: StructurePreset.HOUSE).build())
        StepType.DIMENSION -> DimensionGenerator.generate(ns, DimensionSpec(name, shortId.replace('-', '_')), profile)
    }

    private fun darker(c: Int): Int {
        fun ch(sh: Int) = (((c shr sh) and 0xFF) * 6 / 10) shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}

/** Plan proposé : étapes + remarques (ce qui a été compris ou ignoré). Rien n'est écrit tant que l'utilisateur ne l'a pas accepté. */
data class Plan(val steps: List<PlanStep>, val notes: List<String>) {
    fun generate(ns: String, profile: BedrockVersionProfile): GeneratedContent {
        val c = GeneratedContent()
        steps.forEach { c.addAll(it.generate(ns, profile)) }
        return c
    }
}

/** Échange du plan au format JSON (réponse de l'IA facultative) : lecture stricte, chaque valeur est bornée. */
object PlanJson {
    const val MAX_STEPS = 8

    private fun nullable(type: String) = arr(type, "null")
    private fun enumOrNull(values: List<String>) = obj("type" to nullable("string"), "enum" to values + listOf(null))

    /** Schéma JSON (sorties structurées) : toutes les propriétés sont requises, les facultatives acceptent null. */
    fun schema(): JsonObject = obj(
        "type" to "object", "additionalProperties" to false, "required" to arr("steps", "notes"),
        "properties" to obj(
            "notes" to obj("type" to "array", "items" to obj("type" to "string")),
            "steps" to obj("type" to "array", "items" to obj(
                "type" to "object", "additionalProperties" to false,
                "required" to arr("type", "name", "item_kind", "temperament", "boss", "health", "damage", "speed", "color", "light", "block_style", "particle_preset", "structure_preset"),
                "properties" to obj(
                    "type" to obj("type" to "string", "enum" to StepType.entries.map { it.key }),
                    "name" to obj("type" to "string"),
                    "item_kind" to enumOrNull(ItemKind.entries.map { it.name.lowercase() }),
                    "temperament" to enumOrNull(MobTemperament.entries.map { it.name.lowercase() }),
                    "boss" to obj("type" to "boolean"),
                    "health" to obj("type" to nullable("integer")),
                    "damage" to obj("type" to nullable("integer")),
                    "speed" to obj("type" to nullable("number")),
                    "color" to obj("type" to nullable("string"), "description" to "#RRGGBB"),
                    "light" to obj("type" to nullable("integer")),
                    "block_style" to enumOrNull(BlockStyle.entries.filter { it != BlockStyle.PORTAL }.map { it.name.lowercase() }),
                    "particle_preset" to enumOrNull(ParticlePreset.entries.map { it.name.lowercase() }),
                    "structure_preset" to enumOrNull(StructurePreset.entries.map { it.name.lowercase() }),
                ),
            )),
        ),
    )

    fun toJson(plan: Plan): String = JsonWriter.write(obj(
        "steps" to plan.steps.map { s ->
            obj(
                "type" to s.type.key, "name" to s.name, "item_kind" to s.itemKind?.name?.lowercase(), "temperament" to s.temperament?.name?.lowercase(),
                "boss" to s.boss, "health" to s.health, "damage" to s.damage, "speed" to s.speed,
                "color" to s.color?.let { String.format("#%06X", it and 0xFFFFFF) }, "light" to s.light,
                "block_style" to s.blockStyle?.name?.lowercase(), "particle_preset" to s.particlePreset?.name?.lowercase(),
                "structure_preset" to s.structurePreset?.name?.lowercase(),
            )
        },
        "notes" to plan.notes,
    ))

    private inline fun <reified E : Enum<E>> enumOf(v: JsonValue?): E? =
        (v as? JsonString)?.value?.uppercase()?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    /** Lit un plan ; les étapes invalides sont écartées avec une remarque, jamais exécutées. */
    fun parse(text: String): Plan {
        val root = runCatching { JsonParser.parseOrThrow(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")) }.getOrNull() as? JsonObject
            ?: return Plan(emptyList(), listOf("La réponse n'est pas un plan lisible."))
        val notes = ((root["notes"] as? JsonArray)?.items ?: emptyList()).mapNotNull { (it as? JsonString)?.value?.take(300) }.take(10).toMutableList()
        val steps = mutableListOf<PlanStep>()
        for (v in ((root["steps"] as? JsonArray)?.items ?: emptyList()).take(MAX_STEPS)) {
            val o = v as? JsonObject ?: continue
            val type = StepType.entries.firstOrNull { it.key == o.str("type") }
            val name = o.str("name")?.trim()?.take(48)
            if (type == null || name.isNullOrBlank()) { notes += "Étape ignorée : type ou nom manquant."; continue }
            fun int(k: String, range: IntRange) = (o[k] as? JsonNumber)?.toDouble()?.toInt()?.coerceIn(range)
            val color = o.str("color")?.let { Regex("^#?([0-9a-fA-F]{6})$").find(it)?.groupValues?.get(1) }?.toLong(16)?.toInt()?.or(0xFF shl 24)
            steps += PlanStep(
                type, name, enumOf<ItemKind>(o["item_kind"]), enumOf<MobTemperament>(o["temperament"]), (o["boss"] as? JsonBool)?.value == true,
                int("health", 1..1024), int("damage", 0..1000), (o["speed"] as? JsonNumber)?.toDouble()?.coerceIn(0.05, 1.0),
                color, int("light", 0..15), enumOf<BlockStyle>(o["block_style"])?.takeIf { it != BlockStyle.PORTAL },
                enumOf<ParticlePreset>(o["particle_preset"]), enumOf<StructurePreset>(o["structure_preset"]),
            )
        }
        if ((root["steps"] as? JsonArray)?.items?.size ?: 0 > MAX_STEPS) notes += "Seules les $MAX_STEPS premières étapes sont gardées."
        return Plan(steps, notes)
    }

    /** Consignes données à l'IA facultative (le texte de l'utilisateur est transmis à part, comme donnée). */
    const val INSTRUCTIONS = """Tu aides un enfant ou un débutant à créer un add-on Minecraft Bedrock Edition (pas Java) dans une application mobile.
Transforme sa demande en un plan d'au plus 8 étapes, en utilisant uniquement les types et valeurs du schéma.
- creature : créature (temperament passive/neutral/hostile ; boss=true pour un boss ; health 1-1024 ; damage ; speed 0.05-1, 0.25 = normal).
- item : objet (item_kind : simple, food, sword, pickaxe, axe, shovel, helmet, chestplate, leggings, boots).
- block : bloc (block_style ; light 0-15 pour un bloc lumineux).
- particle : effet de particules (particle_preset). structure : construction (structure_preset). dimension : nouvelle dimension.
Choisis des noms courts en français. color au format #RRGGBB si une couleur est demandée, sinon null. boss=false hors créatures (pour un objet, true = reflet enchanté).
Si une partie de la demande est impossible avec ces types (code, mods Java, modification de Minecraft lui-même…), ne l'invente pas : explique-le dans notes, en français simple."""
}

/** Analyse hors ligne par mots-clés : couvre les demandes courantes, sans réseau. */
object OfflinePlanner {
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private val COLORS = listOf(
        "rouge" to 0xFFD32F2F, "bleu" to 0xFF1E88E5, "vert" to 0xFF43A047, "jaune" to 0xFFFDD835, "orange" to 0xFFFB8C00,
        "violet" to 0xFF8E24AA, "rose" to 0xFFF06292, "noir" to 0xFF212121, "blanc" to 0xFFF5F5F5, "gris" to 0xFF9E9E9E,
        "marron" to 0xFF6D4C41, "dore" to 0xFFFFC107, "or " to 0xFFFFC107, "cyan" to 0xFF00BCD4, "turquoise" to 0xFF26A69A,
    ).map { it.first to it.second.toInt() }

    private val ITEM_WORDS = listOf(
        "epee" to ItemKind.SWORD, "pioche" to ItemKind.PICKAXE, "hache" to ItemKind.AXE, "pelle" to ItemKind.SHOVEL,
        "casque" to ItemKind.HELMET, "plastron" to ItemKind.CHESTPLATE, "jambiere" to ItemKind.LEGGINGS, "botte" to ItemKind.BOOTS,
        "nourriture" to ItemKind.FOOD, "gateau" to ItemKind.FOOD, "pomme" to ItemKind.FOOD, "tarte" to ItemKind.FOOD, "soupe" to ItemKind.FOOD,
        "gemme" to ItemKind.SIMPLE, "lingot" to ItemKind.SIMPLE, "cristal" to ItemKind.SIMPLE, "objet" to ItemKind.SIMPLE, "baguette" to ItemKind.SIMPLE,
    )

    /** Découpe la demande en morceaux (« et », « puis », virgules, « avec aussi ») et traite chacun. */
    fun plan(text: String): Plan {
        val notes = mutableListOf<String>()
        val n = norm(text)
        if (Regex("\\b(java|forge|fabric|\\.jar|optifine)\\b").containsMatchIn(n)) {
            notes += "Les mods Java (Forge, Fabric, .jar) ne fonctionnent pas sur Bedrock : l'application crée uniquement des add-ons Bedrock."
        }
        val parts = text.split(Regex("(?i)\\s*(?:,|;|\\bet\\b|\\bpuis\\b|\\bainsi qu['e]|\\bplus\\b)\\s+(?=(?:un|une|des|le|la|les|l')\\b|\\w)"))
            .map { it.trim() }.filter { it.length > 2 }
        val steps = mutableListOf<PlanStep>()
        val seen = mutableSetOf<String>()
        for (part in parts) {
            val step = stepFor(part) ?: continue
            if (seen.add(step.type.key + step.shortId)) steps += step
            if (steps.size >= PlanJson.MAX_STEPS) break
        }
        if (steps.isEmpty()) {
            notes += "Je n'ai pas reconnu de créature, d'objet, de bloc, de particules, de structure ou de dimension. Exemple : « un zombie de feu rouge hostile avec 40 points de vie et une épée de glace bleue »."
        }
        return Plan(steps, notes)
    }

    private fun stepFor(part: String): PlanStep? {
        val n = norm(part)
        fun has(vararg w: String) = w.any { Regex("\\b$it").containsMatchIn(n) }
        val color = COLORS.firstOrNull { (w, _) -> n.contains(w) }?.second
        val number = { unit: String -> Regex("(\\d{1,4})\\s*(?:points? de |pts? de )?$unit").find(n)?.groupValues?.get(1)?.toIntOrNull() }
        val hearts = number("coeurs?")?.let { it * 2 }
        val health = number("(?:vie|pv|sante)") ?: hearts
        val damage = number("(?:de )?degats?")
        val light = if (has("lumineu", "lumiere", "lampe", "brille", "eclaire")) 15 else null
        val type = when {
            has("dimension", "monde parallele", "autre monde") -> StepType.DIMENSION
            has("particule", "etincelle", "fumee", "paillette") -> StepType.PARTICLE
            has("maison", "cabane", "tour", "donjon", "structure", "batiment", "arche", "salle") -> StepType.STRUCTURE
            ITEM_WORDS.any { (w, _) -> Regex("\\b$w").containsMatchIn(n) } -> StepType.ITEM
            has("bloc", "minerai", "brique", "planche", "lampe", "verre") -> StepType.BLOCK
            has("creature", "mob", "monstre", "animal", "boss", "zombie", "squelette", "dragon", "loup", "golem", "chat", "chien", "cochon",
                "vache", "araignee", "fantome", "gardien", "bete", "sanglier", "renard", "ours", "slime", "robot", "chevalier") -> StepType.CREATURE
            else -> return null
        }
        val name = extractName(part, type)
        return when (type) {
            StepType.CREATURE -> PlanStep(type, name,
                temperament = when {
                    has("hostile", "mechant", "agressi", "attaque", "dangereu", "monstre", "boss") -> MobTemperament.HOSTILE
                    has("passi", "gentil", "pacifique", "inoffensi", "mignon") -> MobTemperament.PASSIVE
                    else -> MobTemperament.NEUTRAL
                },
                boss = has("boss"), health = health?.coerceIn(1, 1024), damage = damage?.coerceIn(0, 1000),
                speed = when { has("rapide", "vite") -> 0.35; has("lent") -> 0.15; else -> null }, color = color)
            StepType.ITEM -> PlanStep(type, name, itemKind = ITEM_WORDS.first { (w, _) -> Regex("\\b$w").containsMatchIn(n) }.second,
                damage = damage?.coerceIn(0, 1000), color = color, boss = has("enchant", "magique", "legendaire"))
            StepType.BLOCK -> PlanStep(type, name, color = color, light = light, blockStyle = when {
                has("minerai") -> BlockStyle.ORE; has("brique") -> BlockStyle.BRICKS; has("planche", "bois") -> BlockStyle.PLANKS
                has("verre", "cristal") -> BlockStyle.GLASS; light != null -> BlockStyle.LAMP; else -> BlockStyle.STONE
            })
            StepType.PARTICLE -> PlanStep(type, name, particlePreset = when {
                has("fumee") -> ParticlePreset.SMOKE; has("coeur", "amour") -> ParticlePreset.HEARTS; has("pluie", "goutte") -> ParticlePreset.RAIN
                has("etincelle", "feu", "flamme") -> ParticlePreset.SPARKS; else -> ParticlePreset.MAGIC
            })
            StepType.STRUCTURE -> PlanStep(type, name, structurePreset = when {
                has("tour") -> StructurePreset.TOWER; has("donjon", "salle", "tresor", "secret") -> StructurePreset.DUNGEON
                has("arche", "portail") -> StructurePreset.ARCH; else -> StructurePreset.HOUSE
            })
            StepType.DIMENSION -> PlanStep(type, name)
        }
    }

    private val STOP = setOf("un", "une", "des", "le", "la", "les", "l", "de", "du", "d", "qui", "avec", "et", "je", "veux", "voudrais", "cree", "creer",
        "fais", "faire", "ajoute", "ajouter", "moi", "mon", "ma", "mes", "nouveau", "nouvelle", "nouvel", "s'il", "te", "plait", "stp", "aussi")

    /** Nom entre guillemets s'il y en a, sinon les premiers mots significatifs (sans nombres). */
    private fun extractName(part: String, type: StepType): String {
        Regex("[«\"“]\\s*([^»\"”]{2,40})\\s*[»\"”]").find(part)?.let { return it.groupValues[1].trim() }
        val words = part.split(Regex("[\\s']+")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotBlank() }
        val start = words.indexOfFirst { norm(it) !in STOP }
        if (start < 0) return type.labelFr
        val kept = words.drop(start).takeWhile { w -> !Regex("^\\d").containsMatchIn(w) && norm(w) !in setOf("avec", "qui", "et", "hostile", "passif", "neutre", "lumineux", "lumineuse") }.take(4)
        return kept.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { type.labelFr }
    }
}
