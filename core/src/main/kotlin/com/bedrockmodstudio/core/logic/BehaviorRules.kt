package com.bedrockmodstudio.core.logic

import com.bedrockmodstudio.core.bedrock.BedrockFeature
import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.Js
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers

/**
 * Éditeur de comportements « par blocs » : chaque règle est QUAND (événement) → SI (conditions) → ALORS (actions).
 * Les règles sont compilées en un script `@minecraft/server`. Un bloc impossible à traduire produit une erreur
 * de compilation explicite : rien n'est écrit dans ce cas.
 */
enum class BlockCategory(val labelFr: String) { EVENT("Quand"), CONDITION("Si"), ACTION("Alors") }

enum class ParamType { TEXT, NUMBER, ITEM, BLOCK, ENTITY, EFFECT, DIMENSION, SOUND, TAG, COMMAND_NAME }

data class ParamSpec(
    val key: String,
    val labelFr: String,
    val type: ParamType,
    val default: String = "",
    val min: Double? = null,
    val max: Double? = null,
)

/** Contexte disponible dans une règle selon son événement. */
data class RuleContext(val hasPlayer: Boolean, val playerMayBeMissing: Boolean, val hasEntity: Boolean)

/** Un bloc posé dans une règle : un type du catalogue et ses valeurs. */
data class BlockInstance(val kind: String, val params: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String =
        params[key] ?: BehaviorCatalog.ALL.filter { it.kind == kind }.flatMap { it.params }.firstOrNull { it.key == key }?.default ?: ""
}

data class BehaviorRule(
    val name: String,
    val trigger: BlockInstance,
    val conditions: List<BlockInstance> = emptyList(),
    val actions: List<BlockInstance> = emptyList(),
    val enabled: Boolean = true,
)

class BlockDef(
    val kind: String,
    val category: BlockCategory,
    val labelFr: String,
    val helpFr: String,
    val params: List<ParamSpec> = emptyList(),
    /** Pour un événement : contexte fourni. */
    val context: RuleContext? = null,
    /** Pour une condition ou une action : nécessite un joueur. */
    val needsPlayer: Boolean = false,
    val needsEntity: Boolean = false,
    val feature: BedrockFeature? = null,
    /** Résumé lisible : « donner 3 × minecraft:diamond ». */
    val summary: (BlockInstance) -> String,
)

object BehaviorCatalog {
    private fun p(key: String, label: String, type: ParamType, default: String = "", min: Double? = null, max: Double? = null) =
        ParamSpec(key, label, type, default, min, max)

    val ALL: List<BlockDef> = listOf(
        // ---- Événements ----
        BlockDef("player_join", BlockCategory.EVENT, "Un joueur rejoint le monde", "Au premier apparition du joueur dans la session.",
            context = RuleContext(true, false, true)) { "un joueur rejoint le monde" },
        BlockDef("item_use", BlockCategory.EVENT, "Un joueur utilise un objet", "Clic droit / appui long avec l'objet en main.",
            listOf(p("item", "Objet", ParamType.ITEM, "minecraft:stick")), RuleContext(true, false, true)) { "un joueur utilise ${it["item"]}" },
        BlockDef("block_break", BlockCategory.EVENT, "Un joueur casse un bloc", "Après la destruction du bloc par un joueur.",
            listOf(p("block", "Bloc", ParamType.BLOCK, "minecraft:stone")), RuleContext(true, false, false)) { "un joueur casse ${it["block"]}" },
        BlockDef("entity_die", BlockCategory.EVENT, "Une créature meurt", "Le joueur est celui qui l'a tuée, s'il y en a un.",
            listOf(p("entity", "Créature", ParamType.ENTITY, "minecraft:zombie")), RuleContext(true, true, true)) { "${it["entity"]} meurt" },
        BlockDef("timer", BlockCategory.EVENT, "Toutes les N secondes", "Les actions s'appliquent à chaque joueur connecté.",
            listOf(p("seconds", "Secondes", ParamType.NUMBER, "10", 1.0, 3600.0)), RuleContext(true, false, true)) { "toutes les ${it["seconds"]} s (pour chaque joueur)" },
        BlockDef("area_enter", BlockCategory.EVENT, "Un joueur entre dans une zone", "Zone rectangulaire entre deux coins (vérifiée deux fois par seconde).",
            listOf(
                p("x1", "X coin 1", ParamType.NUMBER, "0", -30_000_000.0, 30_000_000.0), p("y1", "Y coin 1", ParamType.NUMBER, "60", -64.0, 320.0),
                p("z1", "Z coin 1", ParamType.NUMBER, "0", -30_000_000.0, 30_000_000.0), p("x2", "X coin 2", ParamType.NUMBER, "10", -30_000_000.0, 30_000_000.0),
                p("y2", "Y coin 2", ParamType.NUMBER, "80", -64.0, 320.0), p("z2", "Z coin 2", ParamType.NUMBER, "10", -30_000_000.0, 30_000_000.0),
            ), RuleContext(true, false, true)) { "un joueur entre dans la zone (${it["x1"]}, ${it["y1"]}, ${it["z1"]}) → (${it["x2"]}, ${it["y2"]}, ${it["z2"]})" },
        BlockDef("command", BlockCategory.EVENT, "Un joueur tape une commande", "Commande personnalisée /espace:nom, sans triche nécessaire.",
            listOf(p("name", "Nom de la commande", ParamType.COMMAND_NAME, "bonus"), p("description", "Description", ParamType.TEXT, "Commande de mon addon")),
            RuleContext(true, false, true), feature = BedrockFeature.CUSTOM_COMMANDS) { "un joueur tape /${it["name"]}" },

        // ---- Conditions ----
        BlockDef("chance", BlockCategory.CONDITION, "Avec une probabilité de…", "Tirage au sort à chaque fois.",
            listOf(p("percent", "Pourcentage", ParamType.NUMBER, "50", 0.0, 100.0))) { "avec ${it["percent"]} % de chance" },
        BlockDef("has_tag", BlockCategory.CONDITION, "Le joueur a l'étiquette", "Étiquette posée avec l'action « Ajouter une étiquette ».",
            listOf(p("tag", "Étiquette", ParamType.TAG, "vip")), needsPlayer = true) { "le joueur a l'étiquette « ${it["tag"]} »" },
        BlockDef("not_tag", BlockCategory.CONDITION, "Le joueur n'a pas l'étiquette", "Utile pour ne faire quelque chose qu'une fois.",
            listOf(p("tag", "Étiquette", ParamType.TAG, "deja_fait")), needsPlayer = true) { "le joueur n'a pas l'étiquette « ${it["tag"]} »" },
        BlockDef("sneaking", BlockCategory.CONDITION, "Le joueur est accroupi", "", needsPlayer = true) { "le joueur est accroupi" },
        BlockDef("health_below", BlockCategory.CONDITION, "La vie du joueur est sous…", "En pourcentage de sa vie maximale.",
            listOf(p("percent", "Pourcentage", ParamType.NUMBER, "30", 1.0, 100.0)), needsPlayer = true) { "la vie du joueur est sous ${it["percent"]} %" },
        BlockDef("in_dimension", BlockCategory.CONDITION, "Le joueur est dans la dimension", "",
            listOf(p("dimension", "Dimension", ParamType.DIMENSION, "minecraft:overworld")), needsPlayer = true) { "le joueur est dans ${it["dimension"]}" },
        BlockDef("is_night", BlockCategory.CONDITION, "Il fait nuit", "Entre 19 h et 5 h du jeu (temps 13000 à 23000).") { "il fait nuit" },
        BlockDef("counter_at_least", BlockCategory.CONDITION, "Le compteur du joueur vaut au moins…", "Compteurs propres à chaque joueur, conservés dans la sauvegarde.",
            listOf(p("counter", "Compteur", ParamType.TAG, "points"), p("value", "Valeur", ParamType.NUMBER, "10", -1e9, 1e9)), needsPlayer = true) {
            "le compteur « ${it["counter"]} » ≥ ${it["value"]}"
        },

        // ---- Actions ----
        BlockDef("message", BlockCategory.ACTION, "Envoyer un message au joueur", "",
            listOf(p("text", "Message", ParamType.TEXT, "Bonjour !")), needsPlayer = true) { "écrire « ${it["text"]} » au joueur" },
        BlockDef("broadcast", BlockCategory.ACTION, "Envoyer un message à tout le monde", "",
            listOf(p("text", "Message", ParamType.TEXT, "Attention !"))) { "écrire « ${it["text"]} » à tous" },
        BlockDef("give", BlockCategory.ACTION, "Donner un objet au joueur", "",
            listOf(p("item", "Objet", ParamType.ITEM, "minecraft:diamond"), p("count", "Quantité", ParamType.NUMBER, "1", 1.0, 64.0)), needsPlayer = true) {
            "donner ${it["count"]} × ${it["item"]}"
        },
        BlockDef("effect", BlockCategory.ACTION, "Appliquer un effet au joueur", "Vitesse, régénération, force…",
            listOf(p("effect", "Effet", ParamType.EFFECT, "speed"), p("seconds", "Durée (s)", ParamType.NUMBER, "10", 1.0, 3600.0),
                p("level", "Niveau", ParamType.NUMBER, "1", 1.0, 10.0)), needsPlayer = true) { "effet ${it["effect"]} niveau ${it["level"]} pendant ${it["seconds"]} s" },
        BlockDef("summon", BlockCategory.ACTION, "Faire apparaître une créature", "À la position de l'événement.",
            listOf(p("entity", "Créature", ParamType.ENTITY, "minecraft:zombie"), p("count", "Nombre", ParamType.NUMBER, "1", 1.0, 20.0))) {
            "faire apparaître ${it["count"]} × ${it["entity"]}"
        },
        BlockDef("teleport", BlockCategory.ACTION, "Téléporter le joueur", "",
            listOf(p("x", "X", ParamType.NUMBER, "0", -30_000_000.0, 30_000_000.0), p("y", "Y", ParamType.NUMBER, "100", -64.0, 320.0),
                p("z", "Z", ParamType.NUMBER, "0", -30_000_000.0, 30_000_000.0), p("dimension", "Dimension", ParamType.DIMENSION, "minecraft:overworld")),
            needsPlayer = true) { "téléporter en (${it["x"]}, ${it["y"]}, ${it["z"]}) dans ${it["dimension"]}" },
        BlockDef("sound", BlockCategory.ACTION, "Jouer un son au joueur", "Identifiant de son Bedrock, ex. random.levelup.",
            listOf(p("sound", "Son", ParamType.SOUND, "random.levelup")), needsPlayer = true) { "jouer le son ${it["sound"]}" },
        BlockDef("add_tag", BlockCategory.ACTION, "Ajouter une étiquette au joueur", "",
            listOf(p("tag", "Étiquette", ParamType.TAG, "deja_fait")), needsPlayer = true) { "ajouter l'étiquette « ${it["tag"]} »" },
        BlockDef("remove_tag", BlockCategory.ACTION, "Retirer une étiquette au joueur", "",
            listOf(p("tag", "Étiquette", ParamType.TAG, "deja_fait")), needsPlayer = true) { "retirer l'étiquette « ${it["tag"]} »" },
        BlockDef("counter_add", BlockCategory.ACTION, "Ajouter au compteur du joueur", "Mettre une valeur négative pour retirer.",
            listOf(p("counter", "Compteur", ParamType.TAG, "points"), p("amount", "Valeur ajoutée", ParamType.NUMBER, "1", -1e9, 1e9)), needsPlayer = true) {
            "compteur « ${it["counter"]} » + ${it["amount"]}"
        },
        BlockDef("run_command", BlockCategory.ACTION, "Exécuter une commande", "Commande Minecraft sans le « / », exécutée par le joueur ou à l'endroit de l'événement.",
            listOf(p("command", "Commande", ParamType.TEXT, "say Bonjour"))) { "exécuter « /${it["command"]} »" },
        BlockDef("wait", BlockCategory.ACTION, "Attendre", "Les actions suivantes ont lieu après ce délai.",
            listOf(p("seconds", "Secondes", ParamType.NUMBER, "2", 0.05, 3600.0))) { "attendre ${it["seconds"]} s" },
    )

    fun byKind(kind: String, category: BlockCategory? = null) = ALL.firstOrNull { it.kind == kind && (category == null || it.category == category) }
    fun of(category: BlockCategory) = ALL.filter { it.category == category }

    /** Effets courants (identifiants Bedrock) proposés dans l'éditeur. */
    val EFFECTS = listOf(
        "speed" to "Vitesse", "slowness" to "Lenteur", "haste" to "Célérité", "strength" to "Force", "instant_health" to "Soin instantané",
        "jump_boost" to "Saut amélioré", "regeneration" to "Régénération", "resistance" to "Résistance", "fire_resistance" to "Résistance au feu",
        "water_breathing" to "Respiration aquatique", "invisibility" to "Invisibilité", "night_vision" to "Vision nocturne",
        "levitation" to "Lévitation", "slow_falling" to "Chute lente", "poison" to "Poison", "weakness" to "Faiblesse", "absorption" to "Absorption",
    )
}

data class CompileResult(val content: GeneratedContent?, val errors: List<String>, val code: String = "")

object BehaviorCompiler {
    const val MODULE = "bms/comportements.js"

    /** Vérifie une règle ; renvoie des messages en français (vide = règle valide). */
    fun check(rule: BehaviorRule, namespace: String): List<String> = buildList {
        val label = "Règle « ${rule.name.ifBlank { "sans nom" }} »"
        val trig = BehaviorCatalog.byKind(rule.trigger.kind, BlockCategory.EVENT)
        if (trig == null) { add("$label : événement inconnu « ${rule.trigger.kind} »."); return@buildList }
        if (rule.actions.isEmpty()) add("$label : ajoute au moins une action.")
        val all = listOf(rule.trigger to trig) +
            rule.conditions.map { it to BehaviorCatalog.byKind(it.kind, BlockCategory.CONDITION) } +
            rule.actions.map { it to BehaviorCatalog.byKind(it.kind, BlockCategory.ACTION) }
        for ((inst, def) in all) {
            if (def == null) { add("$label : bloc inconnu « ${inst.kind} » (il ne peut pas être traduit en script)."); continue }
            for (ps in def.params) {
                val v = inst[ps.key].trim()
                val where = "$label, « ${def.labelFr} » → ${ps.labelFr}"
                when (ps.type) {
                    ParamType.NUMBER -> {
                        val d = v.replace(',', '.').toDoubleOrNull()
                        if (d == null) add("$where : nombre attendu.")
                        else if ((ps.min != null && d < ps.min) || (ps.max != null && d > ps.max)) add("$where : doit être compris entre ${Js.num(ps.min ?: d)} et ${Js.num(ps.max ?: d)}.")
                    }
                    ParamType.ITEM, ParamType.BLOCK, ParamType.ENTITY, ParamType.DIMENSION ->
                        if (!Identifiers.isValidIdentifier(v) && !(v.startsWith("minecraft:") && Identifiers.isValidName(v.removePrefix("minecraft:")))) {
                            add("$where : identifiant invalide « $v » (ex. minecraft:diamond).")
                        }
                    ParamType.EFFECT -> if (!Regex("^[a-z_]+$").matches(v)) add("$where : effet invalide « $v ».")
                    ParamType.SOUND -> if (!Regex("^[a-z0-9_.]+$").matches(v)) add("$where : son invalide « $v ».")
                    ParamType.TAG -> if (!Regex("^[A-Za-z0-9_]{1,32}$").matches(v)) add("$where : lettres, chiffres et _ uniquement (32 au maximum).")
                    ParamType.COMMAND_NAME -> if (!Regex("^[a-z0-9_]{1,32}$").matches(v)) add("$where : minuscules, chiffres et _ uniquement.")
                    ParamType.TEXT -> if (v.isEmpty()) add("$where : texte vide.") else if (v.length > 500) add("$where : 500 caractères au maximum.")
                }
            }
            if ((def.needsPlayer && !trig.context!!.hasPlayer)) add("$label : « ${def.labelFr} » a besoin d'un joueur, que l'événement ne fournit pas.")
        }
    }

    fun compile(rules: List<BehaviorRule>, namespace: String): CompileResult {
        val active = rules.filter { it.enabled }
        val errors = active.flatMap { check(it, namespace) }.toMutableList()
        val commandNames = active.filter { it.trigger.kind == "command" }.map { it.trigger["name"] }
        commandNames.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { errors += "La commande /$namespace:$it est définie plusieurs fois." }
        if (errors.isNotEmpty()) return CompileResult(null, errors)

        val features = mutableSetOf<BedrockFeature>()
        val body = StringBuilder()
        val startup = StringBuilder()
        active.forEachIndexed { i, rule ->
            val fn = "rule${i + 1}"
            val trig = BehaviorCatalog.byKind(rule.trigger.kind, BlockCategory.EVENT)!!
            trig.feature?.let { features += it }
            body.append("\n// Règle ${i + 1} : ${rule.name.replace("\n", " ")}\n")
            body.append("// ").append(summary(rule).replace("\n", " ")).append('\n')
            body.append(ruleFunction(fn, rule))
            body.append(subscription(fn, rule.trigger, namespace, startup))
        }
        val imports = "import { CommandPermissionLevel, CustomCommandStatus, ItemStack, Player, system, world } from \"@minecraft/server\";"
        val code = Js.header("Comportements créés avec l'éditeur visuel") +
            "// Ne pas modifier à la main : ce fichier est régénéré à chaque compilation des règles.\n" +
            imports + "\n" + RUNTIME +
            (if (startup.isNotEmpty()) "\nsystem.beforeEvents.startup.subscribe((event) => {\n$startup});\n" else "") +
            body
        val content = GeneratedContent()
        content.script(MODULE, code)
        content.requiredFeatures += features
        return CompileResult(content, emptyList(), code)
    }

    fun summary(rule: BehaviorRule): String {
        val t = BehaviorCatalog.byKind(rule.trigger.kind)?.summary?.invoke(rule.trigger) ?: rule.trigger.kind
        val c = rule.conditions.mapNotNull { BehaviorCatalog.byKind(it.kind)?.summary?.invoke(it) }
        val a = rule.actions.mapNotNull { BehaviorCatalog.byKind(it.kind)?.summary?.invoke(it) }
        return "Quand $t" + (if (c.isNotEmpty()) ", si ${c.joinToString(" et ")}" else "") + ", alors : ${a.joinToString(", puis ")}."
    }

    private fun num(inst: BlockInstance, key: String) = inst[key].trim().replace(',', '.').toDouble()
    private fun ticks(seconds: Double) = Math.max(1, Math.round(seconds * 20)).toString()

    /** Fonctions utilitaires communes, insérées une fois dans le script. */
    private val RUNTIME = """
        |
        |/** @param {import("@minecraft/server").Player} player */
        |function healthRatio(player) {
        |  const health = player.getComponent("minecraft:health");
        |  return health ? health.currentValue / health.effectiveMax : 1;
        |}
        |
        |/** @param {import("@minecraft/server").Player} player @param {string} name */
        |function counter(player, name) {
        |  const value = player.getDynamicProperty("bms_counter:" + name);
        |  return typeof value === "number" ? value : 0;
        |}
        |
        |/** @param {import("@minecraft/server").Player} player @param {string} id @param {number} count */
        |function giveItem(player, id, count) {
        |  const inventory = player.getComponent("minecraft:inventory");
        |  const leftover = inventory?.container?.addItem(new ItemStack(id, count));
        |  if (leftover) player.dimension.spawnItem(leftover, player.location);
        |}
        |
        |function isNight() {
        |  const time = world.getTimeOfDay();
        |  return time >= 13000 && time < 23000;
        |}
        |""".trimMargin()

    private fun condition(c: BlockInstance): String = when (c.kind) {
        "chance" -> "Math.random() * 100 < ${Js.num(num(c, "percent"))}"
        "has_tag" -> "player.hasTag(${Js.str(c["tag"])})"
        "not_tag" -> "!player.hasTag(${Js.str(c["tag"])})"
        "sneaking" -> "player.isSneaking"
        "health_below" -> "healthRatio(player) * 100 < ${Js.num(num(c, "percent"))}"
        "in_dimension" -> "player.dimension.id === ${Js.str(c["dimension"])}"
        "is_night" -> "isNight()"
        "counter_at_least" -> "counter(player, ${Js.str(c["counter"])}) >= ${Js.num(num(c, "value"))}"
        else -> error("condition non traduisible : ${c.kind}")
    }

    /** Actions dans l'ordre ; « attendre » enveloppe la suite dans un délai. */
    private fun actions(list: List<BlockInstance>, indent: String): String {
        val sb = StringBuilder()
        for ((i, a) in list.withIndex()) {
            val line = when (a.kind) {
                "message" -> "player.sendMessage(${Js.str(a["text"])});"
                "broadcast" -> "world.sendMessage(${Js.str(a["text"])});"
                "give" -> "giveItem(player, ${Js.str(a["item"])}, ${num(a, "count").toInt()});"
                "effect" -> "player.addEffect(${Js.str(a["effect"])}, ${ticks(num(a, "seconds"))}, { amplifier: ${num(a, "level").toInt() - 1} });"
                "summon" -> "for (let i = 0; i < ${num(a, "count").toInt()}; i++) dimension.spawnEntity(${Js.str(a["entity"])}, location);"
                "teleport" -> "player.teleport({ x: ${Js.num(num(a, "x"))}, y: ${Js.num(num(a, "y"))}, z: ${Js.num(num(a, "z"))} }, { dimension: world.getDimension(${Js.str(a["dimension"])}) });"
                "sound" -> "player.playSound(${Js.str(a["sound"])});"
                "add_tag" -> "player.addTag(${Js.str(a["tag"])});"
                "remove_tag" -> "player.removeTag(${Js.str(a["tag"])});"
                "counter_add" -> "player.setDynamicProperty(\"bms_counter:\" + ${Js.str(a["counter"])}, counter(player, ${Js.str(a["counter"])}) + ${Js.num(num(a, "amount"))});"
                "run_command" -> "(player ?? dimension).runCommand(${Js.str(a["command"].removePrefix("/"))});"
                "wait" -> {
                    sb.append(indent).append("system.runTimeout(() => {\n")
                    sb.append(actions(list.drop(i + 1), "$indent  "))
                    sb.append(indent).append("}, ${ticks(num(a, "seconds"))});\n")
                    return sb.toString()
                }
                else -> error("action non traduisible : ${a.kind}")
            }
            val def = BehaviorCatalog.byKind(a.kind, BlockCategory.ACTION)!!
            sb.append(indent).append(if (def.needsPlayer) "if (player) $line" else line).append('\n')
        }
        return sb.toString()
    }

    private fun ruleFunction(fn: String, rule: BehaviorRule): String {
        val conds = rule.conditions.map { c ->
            val def = BehaviorCatalog.byKind(c.kind, BlockCategory.CONDITION)!!
            if (def.needsPlayer) "player !== undefined && ${condition(c)}" else condition(c)
        }
        return """
            |/**
            | * @param {import("@minecraft/server").Player | undefined} player
            | * @param {import("@minecraft/server").Dimension} dimension
            | * @param {import("@minecraft/server").Vector3} location
            | */
            |function $fn(player, dimension, location) {
            |${if (conds.isNotEmpty()) "  if (!(${conds.joinToString(" && ") { "($it)" }})) return;\n" else ""}${actions(rule.actions, "  ")}}
            |""".trimMargin()
    }

    private fun subscription(fn: String, t: BlockInstance, ns: String, startup: StringBuilder): String = when (t.kind) {
        "player_join" -> """
            |world.afterEvents.playerSpawn.subscribe((event) => {
            |  if (!event.initialSpawn) return;
            |  $fn(event.player, event.player.dimension, event.player.location);
            |});
            |""".trimMargin()
        "item_use" -> """
            |world.afterEvents.itemUse.subscribe((event) => {
            |  if (event.itemStack.typeId !== ${Js.str(t["item"])}) return;
            |  $fn(event.source, event.source.dimension, event.source.location);
            |});
            |""".trimMargin()
        "block_break" -> """
            |world.afterEvents.playerBreakBlock.subscribe((event) => {
            |  if (event.brokenBlockPermutation.type.id !== ${Js.str(t["block"])}) return;
            |  $fn(event.player, event.dimension, event.block.location);
            |});
            |""".trimMargin()
        "entity_die" -> """
            |world.afterEvents.entityDie.subscribe((event) => {
            |  const dead = event.deadEntity;
            |  if (dead.typeId !== ${Js.str(t["entity"])}) return;
            |  const killer = event.damageSource.damagingEntity;
            |  $fn(killer instanceof Player ? killer : undefined, dead.dimension, dead.location);
            |});
            |""".trimMargin()
        "timer" -> """
            |system.runInterval(() => {
            |  for (const player of world.getAllPlayers()) $fn(player, player.dimension, player.location);
            |}, ${ticks(num(t, "seconds"))});
            |""".trimMargin()
        "area_enter" -> {
            val xs = listOf(num(t, "x1"), num(t, "x2")).sorted(); val ys = listOf(num(t, "y1"), num(t, "y2")).sorted(); val zs = listOf(num(t, "z1"), num(t, "z2")).sorted()
            """
            |const ${fn}Inside = new Set();
            |system.runInterval(() => {
            |  for (const player of world.getAllPlayers()) {
            |    const l = player.location;
            |    const inside = l.x >= ${Js.num(xs[0])} && l.x <= ${Js.num(xs[1] + 1)} && l.y >= ${Js.num(ys[0])} && l.y <= ${Js.num(ys[1] + 1)} && l.z >= ${Js.num(zs[0])} && l.z <= ${Js.num(zs[1] + 1)};
            |    if (inside && !${fn}Inside.has(player.id)) $fn(player, player.dimension, l);
            |    if (inside) ${fn}Inside.add(player.id);
            |    else ${fn}Inside.delete(player.id);
            |  }
            |}, 10);
            |""".trimMargin()
        }
        "command" -> {
            startup.append("""
                |  event.customCommandRegistry.registerCommand(
                |    { name: ${Js.str("$ns:${t["name"]}")}, description: ${Js.str(t["description"])}, permissionLevel: CommandPermissionLevel.Any, cheatsRequired: false },
                |    (origin) => {
                |      const player = origin.sourceEntity;
                |      if (!(player instanceof Player)) return { status: CustomCommandStatus.Failure, message: "Seul un joueur peut utiliser cette commande." };
                |      system.run(() => $fn(player, player.dimension, player.location));
                |      return { status: CustomCommandStatus.Success };
                |    }
                |  );
                |""".trimMargin()).append('\n')
            ""
        }
        else -> error("événement non traduisible : ${t.kind}")
    }
}

/** Enregistrement des règles dans le projet (hors des packs : non exporté, mais inclus dans les sauvegardes). */
object BehaviorRulesFile {
    const val PATH = "editeur/comportements.json"

    fun toJson(rules: List<BehaviorRule>): String = JsonWriter.write(obj(
        "format" to 1,
        "rules" to rules.map { r ->
            obj("name" to r.name, "enabled" to r.enabled, "trigger" to inst(r.trigger),
                "conditions" to r.conditions.map(::inst), "actions" to r.actions.map(::inst))
        },
    )) + "\n"

    private fun inst(b: BlockInstance) = obj("kind" to b.kind, "params" to b.params)

    fun parse(text: String): List<BehaviorRule> {
        val root = JsonParser.parseOrThrow(text) as? JsonObject ?: return emptyList()
        fun blk(v: JsonValue?): BlockInstance? {
            val o = v as? JsonObject ?: return null
            val params = o.obj("params")?.entries?.associate { it.key to (it.value.asString ?: it.value.toString()) } ?: emptyMap()
            return BlockInstance(o.str("kind") ?: return null, params)
        }
        return (root.arr("rules") ?: JsonArray()).items.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            BehaviorRule(
                name = o.str("name") ?: "",
                trigger = blk(o["trigger"]) ?: return@mapNotNull null,
                conditions = o.arr("conditions")?.items?.mapNotNull(::blk) ?: emptyList(),
                actions = o.arr("actions")?.items?.mapNotNull(::blk) ?: emptyList(),
                enabled = o["enabled"]?.asBoolean ?: true,
            )
        }
    }
}
