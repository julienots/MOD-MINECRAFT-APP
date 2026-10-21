package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.Identifiers

/** Recettes au format des fichiers vanilla (`format_version` 1.20.10, champ `unlock`). */
sealed class RecipeSpec {
    abstract val shortId: String

    /** Grille 3×3 lue ligne par ligne ; null = case vide. */
    data class Shaped(override val shortId: String, val grid: List<String?>, val result: String, val count: Int = 1) : RecipeSpec()
    data class Shapeless(override val shortId: String, val ingredients: List<String>, val result: String, val count: Int = 1) : RecipeSpec()
    /** Cuisson au four (le même fichier sert au fumoir ou au haut fourneau via les étiquettes). */
    data class Furnace(override val shortId: String, val input: String, val output: String, val smoker: Boolean = false, val blastFurnace: Boolean = false) : RecipeSpec()

    fun validate(): List<String> = buildList {
        if (!Identifiers.isValidName(shortId)) add("L'identifiant de recette « $shortId » ne doit contenir que des minuscules, chiffres et _.")
        fun item(label: String, id: String?) {
            if (id != null && !Identifiers.isValidIdentifier(id) && !(id.startsWith("minecraft:") && Identifiers.isValidName(id.removePrefix("minecraft:")))) {
                add("$label « $id » n'est pas un identifiant d'objet valide (ex. minecraft:diamond).")
            }
        }
        when (val r = this@RecipeSpec) {
            is Shaped -> {
                if (r.grid.size != 9) add("La grille doit contenir 9 cases.")
                if (r.grid.all { it.isNullOrBlank() }) add("La grille est vide.")
                r.grid.forEach { item("Ingrédient", it?.takeIf { s -> s.isNotBlank() }) }
                item("Résultat", r.result)
                if (r.count !in 1..64) add("La quantité produite doit être comprise entre 1 et 64.")
            }
            is Shapeless -> {
                if (r.ingredients.isEmpty()) add("Ajoute au moins un ingrédient.")
                if (r.ingredients.size > 9) add("9 ingrédients au maximum.")
                r.ingredients.forEach { item("Ingrédient", it) }
                item("Résultat", r.result)
                if (r.count !in 1..64) add("La quantité produite doit être comprise entre 1 et 64.")
            }
            is Furnace -> { item("Entrée", r.input); item("Sortie", r.output) }
        }
    }
}

object RecipeGenerator {

    fun generate(namespace: String, spec: RecipeSpec, profile: BedrockVersionProfile): GeneratedContent {
        val errors = spec.validate()
        require(errors.isEmpty()) { errors.joinToString(" ") }
        val c = GeneratedContent()
        c.text("$BP/recipes/${spec.shortId}.json", JsonWriter.write(recipe("$namespace:${spec.shortId}", spec, profile)) + "\n")
        return c
    }

    fun recipe(id: String, spec: RecipeSpec, p: BedrockVersionProfile): JsonValue = when (spec) {
        is RecipeSpec.Shaped -> {
            val (pattern, key) = compact(spec.grid)
            obj("format_version" to p.recipeFormat, "minecraft:recipe_shaped" to obj(
                "description" to obj("identifier" to id),
                "tags" to listOf("crafting_table"),
                "pattern" to pattern,
                "key" to key.mapValues { obj("item" to it.value) },
                "unlock" to listOf(obj("item" to key.values.first())),
                "result" to obj("item" to spec.result, "count" to spec.count),
            ))
        }
        is RecipeSpec.Shapeless -> obj("format_version" to p.recipeFormat, "minecraft:recipe_shapeless" to obj(
            "description" to obj("identifier" to id),
            "tags" to listOf("crafting_table"),
            "ingredients" to spec.ingredients.map { obj("item" to it) },
            "unlock" to listOf(obj("item" to spec.ingredients.first())),
            "result" to obj("item" to spec.result, "count" to spec.count),
        ))
        is RecipeSpec.Furnace -> obj("format_version" to p.recipeFormat, "minecraft:recipe_furnace" to obj(
            "description" to obj("identifier" to id),
            "tags" to listOfNotNull("furnace", "smoker".takeIf { spec.smoker }, "blast_furnace".takeIf { spec.blastFurnace }),
            "input" to spec.input,
            "output" to spec.output,
            "unlock" to listOf(obj("item" to spec.input)),
        ))
    }

    /**
     * Réduit la grille 3×3 au plus petit rectangle utile et attribue une lettre par ingrédient,
     * comme les recettes vanilla (une épée tient dans une colonne de 1 × 3).
     */
    fun compact(grid: List<String?>): Pair<List<String>, Map<String, String>> {
        val cells = grid.map { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val rows = (0 until 3).filter { r -> (0 until 3).any { cells[r * 3 + it] != null } }
        val cols = (0 until 3).filter { col -> (0 until 3).any { cells[it * 3 + col] != null } }
        val letters = LinkedHashMap<String, String>() // objet → lettre
        val pattern = rows.map { r ->
            cols.joinToString("") { col ->
                val item = cells[r * 3 + col]
                if (item == null) " " else letters.getOrPut(item) { ('A' + letters.size).toString() }
            }
        }
        return pattern to letters.entries.associate { (item, letter) -> letter to item }
    }

    /** Recette vanilla habituelle pour un équipement fabriqué à partir de [material]. */
    fun standardFor(kind: ItemKind, material: String, result: String, shortId: String): RecipeSpec.Shaped? {
        val m = material; val s = "minecraft:stick"
        val grid: List<String?> = when (kind) {
            ItemKind.SWORD -> listOf(null, m, null, null, m, null, null, s, null)
            ItemKind.PICKAXE -> listOf(m, m, m, null, s, null, null, s, null)
            ItemKind.AXE -> listOf(m, m, null, m, s, null, null, s, null)
            ItemKind.SHOVEL -> listOf(null, m, null, null, s, null, null, s, null)
            ItemKind.HELMET -> listOf(m, m, m, m, null, m, null, null, null)
            ItemKind.CHESTPLATE -> listOf(m, null, m, m, m, m, m, m, m)
            ItemKind.LEGGINGS -> listOf(m, m, m, m, null, m, m, null, m)
            ItemKind.BOOTS -> listOf(m, null, m, m, null, m, null, null, null)
            else -> return null
        }
        return RecipeSpec.Shaped(shortId, grid, result)
    }
}
