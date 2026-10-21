package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.model.Identifiers

enum class DimensionTerrain(val labelFr: String, val explanationFr: String) {
    PLATFORM("Plateforme", "Une île ronde unique flottant dans le vide."),
    ISLANDS("Îles flottantes", "Une île centrale entourée de petites îles, générées par script."),
    FLAT("Plaine plate", "Un grand carré plat de plusieurs couches."),
}

/**
 * Dimension personnalisée (API de scripts stable `DimensionRegistry.registerCustomDimension`, Minecraft ≥ 1.26.30).
 * La dimension est créée vide par Minecraft (générateur « void ») : le terrain décrit ici est construit par script,
 * une seule fois, au premier voyage.
 */
data class DimensionSpec(
    val displayName: String,
    val shortId: String = Identifiers.slug(displayName).replace('.', '_').ifEmpty { "dimension" },
    val terrain: DimensionTerrain = DimensionTerrain.ISLANDS,
    /** Bloc de surface (identifiant vanilla ou du projet). */
    val surfaceBlock: String = "minecraft:moss_block",
    /** Bloc sous la surface. */
    val fillerBlock: String = "minecraft:end_stone",
    /** Rayon du terrain principal, en blocs. */
    val radius: Int = 12,
    /** Hauteur d'arrivée. */
    val spawnY: Int = 80,
    val withPortal: Boolean = true,
    val withKey: Boolean = true,
    val withCommand: Boolean = true,
    val portalColor: Int = 0xFF8E44AD.toInt(),
    val arrivalMessage: String = "Bienvenue dans $displayName !",
) {
    fun validate(): List<String> = buildList {
        if (displayName.isBlank()) add("Le nom de la dimension est vide.")
        if (!Identifiers.isValidName(shortId) || shortId.contains('.')) add("L'identifiant « $shortId » ne doit contenir que des minuscules, chiffres et _.")
        for ((label, b) in listOf("Bloc de surface" to surfaceBlock, "Bloc de remplissage" to fillerBlock)) {
            if (!Identifiers.isValidIdentifier(b) && !(b.startsWith("minecraft:") && Identifiers.isValidName(b.removePrefix("minecraft:")))) {
                add("$label « $b » n'est pas un identifiant de bloc valide.")
            }
        }
        if (radius !in 4..32) add("Le rayon doit être compris entre 4 et 32 blocs.")
        if (spawnY !in 0..250) add("La hauteur d'arrivée doit être comprise entre 0 et 250.")
        if (!withPortal && !withKey && !withCommand) add("Choisis au moins un moyen d'accès (portail, clé ou commande).")
    }
}

object DimensionGenerator {

    fun generate(namespace: String, spec: DimensionSpec, profile: BedrockVersionProfile): GeneratedContent {
        val errors = spec.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val c = GeneratedContent()
        c.requiredFeatures += BedrockFeature.CUSTOM_DIMENSIONS
        c.requiredFeatures += BedrockFeature.CUSTOM_COMMANDS.takeIf { spec.withCommand } ?: BedrockFeature.SCRIPT_API
        val portalId = "$namespace:${spec.shortId}_portal"
        val keyId = "$namespace:${spec.shortId}_key"
        if (spec.withPortal) {
            c.addAll(BlockGenerator.generate(namespace, BlockSpec(
                displayName = "Portail : ${spec.displayName}", shortId = "${spec.shortId}_portal", style = BlockStyle.PORTAL,
                mainColor = spec.portalColor, accentColor = 0xFFFFFFFF.toInt(), hardness = 3.0, explosionResistance = 1200.0,
                lightEmission = 11, sound = BlockSound.GLASS,
            ), profile))
        }
        if (spec.withKey) {
            c.addAll(ItemGenerator.generate(namespace, ItemSpec(
                displayName = "Clé : ${spec.displayName}", shortId = "${spec.shortId}_key", kind = ItemKind.SIMPLE,
                mainColor = spec.portalColor, accentColor = 0xFFFFD54F.toInt(), maxStack = 1, glint = true,
            ), profile))
        }
        c.script("bms/dimension_${spec.shortId}.js", script(namespace, spec, portalId.takeIf { spec.withPortal }, keyId.takeIf { spec.withKey }))
        return c
    }

    fun script(ns: String, s: DimensionSpec, portalId: String?, keyId: String?): String {
        val dimId = "$ns:${s.shortId}"
        val cmd = if (s.withCommand) """
            |  event.customCommandRegistry.registerCommand(
            |    {
            |      name: ${Js.str(dimId)},
            |      description: ${Js.str("Voyager vers ${s.displayName} (ou revenir)")},
            |      permissionLevel: CommandPermissionLevel.Any,
            |      cheatsRequired: false,
            |    },
            |    (origin) => {
            |      const player = origin.sourceEntity;
            |      if (!(player instanceof Player)) {
            |        return { status: CustomCommandStatus.Failure, message: "Seul un joueur peut utiliser cette commande." };
            |      }
            |      system.run(() => { travel(player); });
            |      return { status: CustomCommandStatus.Success };
            |    }
            |  );
            |""".trimMargin() else ""
        val keyCode = if (keyId != null) """
            |
            |// Clé : clic droit pour partir ou revenir.
            |world.afterEvents.itemUse.subscribe((event) => {
            |  if (event.itemStack.typeId === KEY_ITEM) travel(event.source);
            |});
            |""".trimMargin() else ""
        val portalCode = if (portalId != null) """
            |
            |// Portail : se tenir debout sur le bloc fait voyager (avec un délai pour ne pas repartir aussitôt).
            |const lastTravel = new Map();
            |system.runInterval(() => {
            |  for (const player of world.getAllPlayers()) {
            |    const loc = player.location;
            |    let below;
            |    try {
            |      below = player.dimension.getBlock({ x: Math.floor(loc.x), y: Math.floor(loc.y) - 1, z: Math.floor(loc.z) });
            |    } catch {
            |      continue; // bloc dans une zone non chargée
            |    }
            |    if (!below || below.typeId !== PORTAL_BLOCK) continue;
            |    const last = lastTravel.get(player.id) ?? -1000;
            |    if (system.currentTick - last < 100) continue;
            |    lastTravel.set(player.id, system.currentTick);
            |    travel(player);
            |  }
            |}, 10);
            |""".trimMargin() else ""
        val returnPortal = if (portalId != null) "\n  dim.getBlock({ x: SPAWN.x + 3, y: SPAWN.y - 1, z: SPAWN.z })?.setPermutation(BlockPermutation.resolve(PORTAL_BLOCK));" else ""

        return Js.header("Dimension personnalisée : ${s.displayName} ($dimId)") + """
            |// Fonctionnement :
            |// - la dimension est déclarée au démarrage du monde (API stable, Minecraft 1.26.30 ou plus récent) ;
            |// - elle naît vide : son terrain est construit une seule fois, au premier voyage ;
            |// - la position de départ de chaque joueur est mémorisée pour le retour.
            |import { BlockPermutation, CommandPermissionLevel, CustomCommandStatus, Player, system, world } from "@minecraft/server";
            |
            |const DIMENSION_ID = ${Js.str(dimId)};
            |const SPAWN = { x: 0, y: ${s.spawnY}, z: 0 };
            |const RADIUS = ${s.radius};
            |/** @type {string} */
            |const TERRAIN = ${Js.str(s.terrain.name)};
            |const SURFACE_BLOCK = ${Js.str(s.surfaceBlock)};
            |const FILLER_BLOCK = ${Js.str(s.fillerBlock)};
            |const PORTAL_BLOCK = ${Js.str(portalId ?: "")};
            |const KEY_ITEM = ${Js.str(keyId ?: "")};
            |const ARRIVAL_MESSAGE = ${Js.str(s.arrivalMessage)};
            |const BUILT_PROPERTY = ${Js.str("$dimId.built")};
            |const RETURN_PROPERTY = ${Js.str("$dimId.return")};
            |
            |system.beforeEvents.startup.subscribe((event) => {
            |  event.dimensionRegistry.registerCustomDimension(DIMENSION_ID);
            |$cmd});
            |
            |/** Liste des blocs du terrain : { x, y, z, id }. Tirage pseudo-aléatoire fixe : même terrain à chaque fois. */
            |function terrainBlocks() {
            |  /** @type {{ x: number, y: number, z: number, id: string }[]} */
            |  const blocks = [];
            |  let seed = 12345;
            |  const random = () => {
            |    seed = (seed * 1103515245 + 12345) % 2147483648;
            |    return seed / 2147483648;
            |  };
            |  const island = (cx, cy, cz, r) => {
            |    for (let dy = 0; dy <= r; dy++) {
            |      const layer = Math.max(1, Math.round(r * (1 - dy / (r + 1)) - random() * 1.5));
            |      for (let dx = -layer; dx <= layer; dx++) {
            |        for (let dz = -layer; dz <= layer; dz++) {
            |          if (dx * dx + dz * dz <= layer * layer) {
            |            blocks.push({ x: cx + dx, y: cy - dy, z: cz + dz, id: dy === 0 ? SURFACE_BLOCK : FILLER_BLOCK });
            |          }
            |        }
            |      }
            |    }
            |  };
            |  if (TERRAIN === "FLAT") {
            |    for (let dx = -RADIUS; dx <= RADIUS; dx++) {
            |      for (let dz = -RADIUS; dz <= RADIUS; dz++) {
            |        for (let dy = 0; dy < 4; dy++) {
            |          blocks.push({ x: SPAWN.x + dx, y: SPAWN.y - 1 - dy, z: SPAWN.z + dz, id: dy === 0 ? SURFACE_BLOCK : FILLER_BLOCK });
            |        }
            |      }
            |    }
            |  } else {
            |    island(SPAWN.x, SPAWN.y - 1, SPAWN.z, TERRAIN === "PLATFORM" ? RADIUS : Math.ceil(RADIUS / 2));
            |    if (TERRAIN === "ISLANDS") {
            |      for (let i = 0; i < 6; i++) {
            |        const angle = (i / 6) * Math.PI * 2 + random();
            |        const distance = RADIUS * (1.1 + random() * 0.7);
            |        const r = 2 + Math.floor(random() * 3);
            |        island(
            |          SPAWN.x + Math.round(Math.cos(angle) * distance),
            |          SPAWN.y - 1 + Math.round((random() - 0.5) * 10),
            |          SPAWN.z + Math.round(Math.sin(angle) * distance),
            |          r
            |        );
            |      }
            |    }
            |  }
            |  return blocks;
            |}
            |
            |/** Construit le terrain une fois (zone maintenue chargée pendant la construction, travail étalé dans le temps). */
            |async function ensureBuilt(dim) {
            |  if (world.getDynamicProperty(BUILT_PROPERTY) === true) return;
            |  const area = DIMENSION_ID + "_build";
            |  const extent = RADIUS * 2 + 8;
            |  await world.tickingAreaManager.createTickingArea(area, {
            |    dimension: dim,
            |    from: { x: SPAWN.x - extent, y: SPAWN.y - extent, z: SPAWN.z - extent },
            |    to: { x: SPAWN.x + extent, y: SPAWN.y + 8, z: SPAWN.z + extent },
            |  });
            |  const blocks = terrainBlocks();
            |  const permutations = new Map();
            |  await new Promise((resolve) => {
            |    system.runJob((function* () {
            |      for (const { x, y, z, id } of blocks) {
            |        let permutation = permutations.get(id);
            |        if (!permutation) {
            |          permutation = BlockPermutation.resolve(id);
            |          permutations.set(id, permutation);
            |        }
            |        dim.getBlock({ x, y, z })?.setPermutation(permutation);
            |        yield;
            |      }
            |      resolve(undefined);
            |    })());
            |  });$returnPortal
            |  world.tickingAreaManager.removeTickingArea(area);
            |  world.setDynamicProperty(BUILT_PROPERTY, true);
            |}
            |
            |/** Aller dans la dimension, ou revenir à l'endroit d'où l'on est parti. */
            |async function travel(player) {
            |  if (player.dimension.id === DIMENSION_ID) {
            |    const saved = player.getDynamicProperty(RETURN_PROPERTY);
            |    let target = { dimension: "minecraft:overworld", x: 0, y: 100, z: 0 };
            |    if (typeof saved === "string") {
            |      try {
            |        target = JSON.parse(saved);
            |      } catch {
            |        // position illisible : retour au point par défaut
            |      }
            |    }
            |    player.teleport({ x: target.x, y: target.y, z: target.z }, { dimension: world.getDimension(target.dimension) });
            |    return;
            |  }
            |  const from = player.location;
            |  player.setDynamicProperty(RETURN_PROPERTY, JSON.stringify({ dimension: player.dimension.id, x: from.x, y: from.y + 1, z: from.z + 2 }));
            |  const dim = world.getDimension(DIMENSION_ID);
            |  await ensureBuilt(dim);
            |  player.teleport({ x: SPAWN.x + 0.5, y: SPAWN.y, z: SPAWN.z + 0.5 }, { dimension: dim });
            |  player.sendMessage(ARRIVAL_MESSAGE);
            |}
            |$keyCode$portalCode""".trimMargin()
    }
}
