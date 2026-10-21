package com.bedrockmodstudio.core.library

import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter

enum class ComponentTarget(val labelFr: String, val rootKey: String) {
    ENTITY("Créatures", "minecraft:entity"),
    ITEM("Objets", "minecraft:item"),
    BLOCK("Blocs", "minecraft:block"),
}

/**
 * Composant prêt à insérer. [example] est un exemple original (valeurs choisies pour l'application),
 * dont le format suit les fichiers vanilla de Mojang/bedrock-samples et la documentation Microsoft Learn.
 */
data class LibraryComponent(
    val target: ComponentTarget,
    val name: String,
    val category: String,
    val labelFr: String,
    val descriptionFr: String,
    val example: String,
) {
    val value: JsonValue get() = JsonParser.parseOrThrow(example)
}

object ComponentLibrary {
    private fun e(name: String, cat: String, label: String, desc: String, example: String) = LibraryComponent(ComponentTarget.ENTITY, name, cat, label, desc, example)
    private fun i(name: String, cat: String, label: String, desc: String, example: String) = LibraryComponent(ComponentTarget.ITEM, name, cat, label, desc, example)
    private fun b(name: String, cat: String, label: String, desc: String, example: String) = LibraryComponent(ComponentTarget.BLOCK, name, cat, label, desc, example)

    val ALL: List<LibraryComponent> = listOf(
        // ---- Créatures : statistiques ----
        e("minecraft:health", "Statistiques", "Points de vie", "Vie de départ et maximale (2 points = 1 cœur).", """{ "value": 20, "max": 20 }"""),
        e("minecraft:movement", "Statistiques", "Vitesse", "Vitesse de déplacement (0,25 ≈ un cochon, 0,3 ≈ un zombie).", """{ "value": 0.25 }"""),
        e("minecraft:attack", "Statistiques", "Dégâts d'attaque", "Dégâts infligés au corps à corps. À combiner avec un objectif d'attaque.", """{ "damage": 4 }"""),
        e("minecraft:scale", "Statistiques", "Taille", "Agrandit ou rétrécit le modèle (1 = taille normale). Pense à adapter la boîte de collision.", """{ "value": 1.5 }"""),
        e("minecraft:collision_box", "Statistiques", "Boîte de collision", "Largeur et hauteur en blocs.", """{ "width": 0.9, "height": 1.3 }"""),
        e("minecraft:knockback_resistance", "Statistiques", "Résistance au recul", "0 = recul normal, 1 = aucun recul.", """{ "value": 0.5 }"""),
        e("minecraft:follow_range", "Statistiques", "Distance de poursuite", "Distance (en blocs) à laquelle la créature suit sa cible.", """{ "value": 16, "max": 32 }"""),
        e("minecraft:experience_reward", "Statistiques", "Expérience gagnée", "Expérience donnée à la mort si un joueur l'a tuée (expression Molang).", """{ "on_death": "query.last_hit_by_player ? 5 : 0" }"""),
        e("minecraft:variant", "Statistiques", "Variante", "Numéro de variante lisible par le pack de ressources (textures différentes).", """{ "value": 1 }"""),
        e("minecraft:type_family", "Statistiques", "Familles", "Familles utilisées par les filtres (ex. « monster » est ciblé par les golems).", """{ "family": ["mob", "monster"] }"""),
        e("minecraft:loot", "Statistiques", "Butin", "Table de butin lâchée à la mort (chemin dans le pack de comportement).", """{ "table": "loot_tables/entities/ma_creature.json" }"""),
        // ---- Créatures : corps et déplacement ----
        e("minecraft:physics", "Corps", "Physique", "Gravité et collisions. Presque toutes les créatures en ont besoin.", "{}"),
        e("minecraft:pushable", "Corps", "Peut être poussée", "Par les autres créatures et par les pistons.", """{ "is_pushable": true, "is_pushable_by_piston": true }"""),
        e("minecraft:breathable", "Corps", "Respiration", "Réserve d'air sous l'eau (en secondes).", """{ "total_supply": 15, "suffocate_time": 0 }"""),
        e("minecraft:fire_immune", "Corps", "Insensible au feu", "Ne prend pas feu et ne subit pas de dégâts de feu ou de lave.", "{}"),
        e("minecraft:burns_in_daylight", "Corps", "Brûle au soleil", "Prend feu à la lumière du jour, comme un zombie.", "{}"),
        e("minecraft:can_climb", "Corps", "Grimpe aux échelles", "Peut monter aux échelles et aux lianes.", "{}"),
        e("minecraft:movement.basic", "Corps", "Déplacement au sol", "Mode de déplacement standard.", "{}"),
        e("minecraft:jump.static", "Corps", "Saut", "Permet de sauter d'un bloc.", "{}"),
        e("minecraft:navigation.walk", "Corps", "Recherche de chemin", "Trouve un chemin en marchant, en évitant l'eau et les blocs dangereux.",
            """{ "can_walk": true, "avoid_water": true, "avoid_damage_blocks": true }"""),
        e("minecraft:nameable", "Corps", "Peut être nommée", "Accepte les étiquettes de nom.", "{}"),
        e("minecraft:despawn", "Corps", "Disparaît au loin", "La créature disparaît quand aucun joueur n'est proche.", """{ "despawn_from_distance": {} }"""),
        e("minecraft:rideable", "Corps", "Peut être chevauchée", "Un joueur peut monter dessus (position du siège en blocs).",
            """{ "seat_count": 1, "family_types": ["player"], "interact_text": "action.interact.mount", "seats": { "position": [0.0, 1.1, 0.0] } }"""),
        // ---- Créatures : intelligence ----
        e("minecraft:behavior.float", "Intelligence", "Nager", "Remonte à la surface de l'eau.", """{ "priority": 0 }"""),
        e("minecraft:behavior.panic", "Intelligence", "Fuir quand blessée", "Court plus vite quand elle est attaquée.", """{ "priority": 1, "speed_multiplier": 1.25 }"""),
        e("minecraft:behavior.random_stroll", "Intelligence", "Se promener", "Marche au hasard.", """{ "priority": 6, "speed_multiplier": 1.0 }"""),
        e("minecraft:behavior.look_at_player", "Intelligence", "Regarder les joueurs", "Tourne la tête vers les joueurs proches.", """{ "priority": 7, "look_distance": 6 }"""),
        e("minecraft:behavior.random_look_around", "Intelligence", "Regarder autour", "Tourne la tête au hasard.", """{ "priority": 8 }"""),
        e("minecraft:behavior.tempt", "Intelligence", "Attirée par un objet", "Suit un joueur qui tient l'objet indiqué.", """{ "priority": 4, "speed_multiplier": 1.2, "items": ["minecraft:wheat"] }"""),
        e("minecraft:behavior.hurt_by_target", "Intelligence", "Riposter", "Attaque ce qui l'a blessée (avec un objectif d'attaque).", """{ "priority": 1 }"""),
        e("minecraft:behavior.melee_box_attack", "Intelligence", "Attaque au corps à corps", "Fonce sur sa cible et frappe (nécessite « Dégâts d'attaque »).",
            """{ "priority": 3, "speed_multiplier": 1.2, "track_target": true }"""),
        e("minecraft:behavior.nearest_attackable_target", "Intelligence", "Chasser les joueurs", "Choisit comme cible le joueur le plus proche qu'elle voit.",
            """{ "priority": 2, "must_see": true, "reselect_targets": true, "entity_types": [ { "filters": { "test": "is_family", "subject": "other", "value": "player" }, "max_dist": 16 } ] }"""),

        // ---- Objets ----
        i("minecraft:max_stack_size", "Base", "Taille de pile", "Nombre maximal dans une case (1 à 64).", "16"),
        i("minecraft:hand_equipped", "Base", "Tenu comme un outil", "S'affiche en main comme une épée ou une pioche.", """{ "value": true }"""),
        i("minecraft:allow_off_hand", "Base", "Main secondaire", "Peut être placé dans la main secondaire.", """{ "value": true }"""),
        i("minecraft:glint", "Base", "Reflet enchanté", "Brille comme un objet enchanté.", "true"),
        i("minecraft:should_despawn", "Base", "Disparaît au sol", "« false » : l'objet jeté ne disparaît jamais.", """{ "value": false }"""),
        i("minecraft:liquid_clipped", "Base", "Vise les liquides", "Le clic peut viser l'eau et la lave (utile pour les seaux).", """{ "value": true }"""),
        i("minecraft:can_destroy_in_creative", "Base", "Casse en créatif", "« false » : ne casse pas les blocs en mode créatif (comme une épée).", """{ "value": false }"""),
        i("minecraft:durability", "Combat et outils", "Durabilité", "Nombre d'utilisations avant de casser.", """{ "max_durability": 250 }"""),
        i("minecraft:damage", "Combat et outils", "Dégâts", "Dégâts ajoutés quand on frappe avec l'objet.", """{ "value": 6 }"""),
        i("minecraft:enchantable", "Combat et outils", "Enchantable", "Emplacement d'enchantement et facilité à l'enchanter.", """{ "slot": "sword", "value": 14 }"""),
        i("minecraft:cooldown", "Combat et outils", "Temps de recharge", "Délai (secondes) entre deux utilisations ; la catégorie est partagée entre objets.", """{ "category": "ma_categorie", "duration": 2.0 }"""),
        i("minecraft:food", "Nourriture", "Nourriture", "Points de faim rendus et saturation.", """{ "nutrition": 6, "saturation_modifier": 0.6, "can_always_eat": false }"""),
        i("minecraft:use_modifiers", "Nourriture", "Durée d'utilisation", "Temps pour manger ou utiliser l'objet (secondes) et ralentissement pendant l'utilisation.",
            """{ "use_duration": 1.6, "movement_modifier": 0.35 }"""),
        i("minecraft:use_animation", "Nourriture", "Animation d'utilisation", "« eat » (manger), « drink » (boire)…", "\"eat\""),
        i("minecraft:fuel", "Divers", "Combustible", "Durée de combustion dans un four (secondes).", """{ "duration": 10.0 }"""),

        // ---- Blocs ----
        b("minecraft:destructible_by_mining", "Solidité", "Temps de minage", "Secondes pour casser le bloc à la main.", """{ "seconds_to_destroy": 1.5 }"""),
        b("minecraft:destructible_by_explosion", "Solidité", "Résistance aux explosions", "Plus la valeur est grande, plus le bloc résiste.", """{ "explosion_resistance": 6 }"""),
        b("minecraft:flammable", "Solidité", "Inflammable", "Chance de prendre feu et d'être détruit par le feu.", """{ "catch_chance_modifier": 5, "destroy_chance_modifier": 20 }"""),
        b("minecraft:friction", "Physique", "Glissance", "Entre 0 et 0,9. Valeur par défaut 0,4 ; plus c'est haut, plus ça glisse.", "0.4"),
        b("minecraft:collision_box", "Physique", "Boîte de collision", "En pixels (16 = un bloc). « false » : on passe à travers.", """{ "origin": [-8, 0, -8], "size": [16, 8, 16] }"""),
        b("minecraft:selection_box", "Physique", "Boîte de sélection", "Zone visée par le curseur, en pixels.", """{ "origin": [-8, 0, -8], "size": [16, 8, 16] }"""),
        b("minecraft:light_emission", "Lumière", "Émet de la lumière", "De 0 (aucune) à 15 (comme une torche ou plus).", "10"),
        b("minecraft:light_dampening", "Lumière", "Bloque la lumière", "0 = transparent (comme le verre), 15 = opaque.", "0"),
        b("minecraft:map_color", "Apparence", "Couleur sur les cartes", "Couleur du bloc sur une carte.", "\"#7A9A3B\""),
        b("minecraft:loot", "Divers", "Butin", "Table de butin lâchée quand le bloc est cassé.", "\"loot_tables/blocks/mon_bloc.json\""),
    )

    fun search(query: String, target: ComponentTarget?): List<LibraryComponent> {
        val q = query.trim().lowercase()
        return ALL.filter { c ->
            (target == null || c.target == target) &&
                (q.isEmpty() || c.name.contains(q) || c.labelFr.lowercase().contains(q) || c.descriptionFr.lowercase().contains(q) || c.category.lowercase().contains(q))
        }
    }

    sealed class InsertResult {
        data class Ok(val text: String, val replaced: Boolean) : InsertResult()
        data class Problem(val messageFr: String) : InsertResult()
    }

    /**
     * Ajoute [c] dans `components` du fichier [text] (JSON d'une créature, d'un objet ou d'un bloc).
     * Si le composant existe déjà, il n'est remplacé que si [replace] est vrai.
     */
    fun insert(text: String, c: LibraryComponent, replace: Boolean): InsertResult {
        val root = runCatching { JsonParser.parseOrThrow(text) }.getOrNull() as? JsonObject
            ?: return InsertResult.Problem("Le fichier contient une erreur JSON : corrige-la d'abord dans l'éditeur.")
        val main = root.obj(c.target.rootKey)
            ?: return InsertResult.Problem("Ce fichier n'est pas un fichier « ${c.target.rootKey} ».")
        val components = main.obj("components") ?: JsonObject().also { main["components"] = it }
        val exists = components.containsKey(c.name)
        if (exists && !replace) return InsertResult.Problem("EXISTS")
        components[c.name] = c.value
        return InsertResult.Ok(JsonWriter.write(root), exists)
    }
}
