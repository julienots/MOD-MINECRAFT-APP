package com.bedrockmodstudio.core.mods

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.bedrock.LangFile
import com.bedrockmodstudio.core.json.JsonNumber
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.mods.ModCatalog.Kind
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.storage.UserFacingException

/**
 * Modifications « en un geste » d'un contenu existant (créé ici ou importé) :
 * réglages chiffrés, nom affiché, duplication en variante et suppression.
 *
 * Seules les clés concernées sont touchées ; le reste du fichier est conservé tel quel,
 * et chaque écriture laisse l'ancienne version dans l'historique du fichier.
 */
object QuickEdit {

    /**
     * Réglage modifiable. [component] et [field] désignent l'emplacement dans `components` ;
     * un champ null signifie que le composant est directement un nombre (ou `{ "value": n }`).
     */
    enum class Stat(
        val kind: Kind, val labelFr: String, val helpFr: String,
        val component: String, val field: String?,
        val min: Double, val max: Double, val integer: Boolean, val default: Double,
    ) {
        HEALTH(Kind.ENTITY, "Points de vie", "1 cœur = 2 points. Un zombie en a 20.", "minecraft:health", "value", 1.0, 1000.0, true, 20.0),
        SPEED(Kind.ENTITY, "Vitesse", "0,25 = vitesse d'un zombie, 0,35 = rapide.", "minecraft:movement", "value", 0.0, 1.5, false, 0.25),
        ATTACK(Kind.ENTITY, "Dégâts d'attaque", "Points retirés à chaque coup.", "minecraft:attack", "damage", 0.0, 100.0, true, 3.0),
        SCALE(Kind.ENTITY, "Taille", "1 = taille normale du modèle.", "minecraft:scale", "value", 0.1, 10.0, false, 1.0),
        MAX_STACK(Kind.ITEM, "Pile maximale", "Nombre d'exemplaires par case (1 à 64).", "minecraft:max_stack_size", null, 1.0, 64.0, true, 64.0),
        DAMAGE(Kind.ITEM, "Dégâts", "Dégâts infligés en frappant avec l'objet.", "minecraft:damage", null, 0.0, 100.0, true, 1.0),
        DURABILITY(Kind.ITEM, "Durabilité", "Nombre d'utilisations avant de casser.", "minecraft:durability", "max_durability", 1.0, 10000.0, true, 250.0),
        NUTRITION(Kind.ITEM, "Nourriture", "Points de faim rendus (nourriture uniquement).", "minecraft:food", "nutrition", 0.0, 20.0, true, 4.0),
        HARDNESS(Kind.BLOCK, "Temps de minage", "Secondes pour casser à la main.", "minecraft:destructible_by_mining", "seconds_to_destroy", 0.0, 60.0, false, 1.0),
        RESISTANCE(Kind.BLOCK, "Résistance aux explosions", "Plus c'est haut, moins les explosions le cassent.", "minecraft:destructible_by_explosion", "explosion_resistance", 0.0, 3600.0, false, 3.0),
        LIGHT(Kind.BLOCK, "Lumière émise", "0 = aucune, 15 = comme une torche puissante.", "minecraft:light_emission", null, 0.0, 15.0, true, 0.0);

        /** Composant « nourriture » : seulement si l'objet en a déjà un (on n'en ajoute pas d'office). */
        val onlyIfPresent: Boolean get() = this == NUTRITION || this == DURABILITY

        companion object { fun of(kind: Kind) = entries.filter { it.kind == kind } }
    }

    private fun rootKey(kind: Kind) = when (kind) { Kind.ENTITY -> "minecraft:entity"; Kind.ITEM -> "minecraft:item"; Kind.BLOCK -> "minecraft:block" }

    private fun number(v: JsonValue?): Double? = v?.asDouble ?: v?.path("value")?.asDouble
        ?: v?.asBoolean?.let { if (it) 1.0 else 0.0 }

    fun readStats(kind: Kind, root: JsonValue): Map<Stat, Double> {
        val comps = root.path(rootKey(kind), "components") as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<Stat, Double>()
        for (s in Stat.of(kind)) {
            val c = comps[s.component] ?: continue
            val v = if (s.field == null) number(c) else number(c.path(s.field))
            if (v != null) out[s] = v
        }
        return out
    }

    /** Valeurs réglables pour cette fiche (celles présentes, plus celles qu'on peut ajouter). */
    fun editableStats(entry: ModCatalog.Entry): List<Stat> = Stat.of(entry.kind).filter { !it.onlyIfPresent || it in entry.stats }

    private fun load(files: ProjectFiles, path: String): JsonObject =
        JsonParser.parseOrThrow(files.readText(path)) as? JsonObject ?: throw UserFacingException("Fichier illisible : $path")

    private fun save(files: ProjectFiles, path: String, root: JsonObject, reason: String) =
        files.writeText(path, JsonWriter.write(root) + "\n", reason)

    private fun num(s: Stat, v: Double): JsonNumber {
        val c = v.coerceIn(s.min, s.max)
        return if (s.integer) JsonNumber(Math.round(c).toInt()) else JsonNumber(Math.round(c * 1000) / 1000.0)
    }

    /** Écrit un réglage dans le fichier de comportement. */
    fun setStat(files: ProjectFiles, entry: ModCatalog.Entry, stat: Stat, value: Double) {
        require(stat.kind == entry.kind)
        val path = entry.behaviorPath ?: throw UserFacingException("Ce contenu n'a pas de fichier de comportement à modifier.")
        val root = load(files, path)
        val main = root.obj(rootKey(entry.kind)) ?: throw UserFacingException("Ce fichier n'est pas un(e) ${entry.kind.labelFr.lowercase()}.")
        val comps = main.obj("components") ?: JsonObject().also { main["components"] = it }
        val n = num(stat, value)
        when {
            stat.field == null -> {
                val existing = comps[stat.component]
                if (existing is JsonObject) existing["value"] = n else comps[stat.component] = n
            }
            else -> {
                val c = comps.obj(stat.component) ?: JsonObject().also { comps[stat.component] = it }
                c[stat.field] = n
                // La vie maximale suit la vie de départ, sinon la créature apparaît blessée ou plafonnée.
                if (stat == Stat.HEALTH) c["max"] = n
            }
        }
        save(files, path, root, "Réglage : ${stat.labelFr}")
    }

    /** Change le nom affiché en jeu (traductions français et anglais). */
    fun rename(files: ProjectFiles, entry: ModCatalog.Entry, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) throw UserFacingException("Le nom ne peut pas être vide.")
        if (name.length > 64) throw UserFacingException("Nom trop long (64 caractères maximum).")
        val keys = ModCatalog.langKeys(entry.kind, entry.id)
        for (lang in listOf("fr_FR", "en_US")) {
            val p = "$RP/texts/$lang.lang"
            val l = LangFile(if (files.exists(p)) files.readText(p) else "")
            val key = keys.firstOrNull { l[it] != null } ?: keys.first()
            l[key] = name
            if (entry.kind == Kind.ENTITY) l["item.spawn_egg.entity.${entry.id}.name"] = "Œuf de $name".takeIf { lang == "fr_FR" } ?: "$name Spawn Egg"
            files.writeText(p, l.toString(), "Nouveau nom : $name")
        }
        ensureLanguagesListed(files)
    }

    private fun ensureLanguagesListed(files: ProjectFiles) {
        val p = "$RP/texts/languages.json"
        val list = if (files.exists(p)) runCatching { JsonParser.parse(files.readText(p)).valueOrNull()?.asArray }.getOrNull() else null
        val names = list?.items?.mapNotNull { it.asString }?.toMutableList() ?: mutableListOf()
        val before = names.size
        for (l in listOf("en_US", "fr_FR")) if (l !in names) names += l
        if (names.size != before || !files.exists(p)) files.writeText(p, JsonWriter.write(com.bedrockmodstudio.core.json.JsonArray(names.map { JsonString(it) })) + "\n", "Langues")
    }

    /** Identifiant libre dérivé de [id] (« ns:loup » → « ns:loup_2 »). */
    fun freeId(catalog: ModCatalog.Catalog, id: String): String {
        val base = id.replace(Regex("_\\d+$"), "")
        var i = 2
        while (catalog.entries.any { it.id == "${base}_$i" }) i++
        return "${base}_$i"
    }

    private fun siblingPath(path: String, oldShort: String, newShort: String): String {
        val dir = path.substringBeforeLast('/', "")
        val file = path.substringAfterLast('/')
        val renamed = if (file.contains(oldShort)) file.replace(oldShort, newShort) else "${newShort}_${file}"
        return if (dir.isEmpty()) renamed else "$dir/$renamed"
    }

    /**
     * Crée une variante indépendante : nouveaux fichiers de comportement et d'apparence avec un nouvel
     * identifiant, même modèle et même texture (modifiables ensuite séparément). Renvoie le nouvel identifiant.
     */
    fun duplicate(files: ProjectFiles, catalog: ModCatalog.Catalog, entry: ModCatalog.Entry, newName: String): String {
        val newId = freeId(catalog, entry.id)
        val oldShort = entry.shortId; val newShort = newId.substringAfter(':')
        fun copy(path: String, rootKey: String): String {
            val root = load(files, path)
            val desc = root.obj(rootKey)?.obj("description") ?: throw UserFacingException("Fichier inattendu : $path")
            desc["identifier"] = JsonString(newId)
            var target = siblingPath(path, oldShort, newShort)
            var n = 2
            while (files.exists(target)) { target = siblingPath(path, oldShort, "${newShort}_$n"); n++ }
            files.writeText(target, JsonWriter.write(root) + "\n", "Variante de ${entry.id}")
            return target
        }
        entry.behaviorPath?.let { copy(it, rootKey(entry.kind)) }
        entry.clientPath?.let { copy(it, "minecraft:client_entity") }
        val created = entry.copy(id = newId)
        rename(files, created, newName.ifBlank { entry.name + " 2" })
        return newId
    }

    /** Envoie les fichiers propres à ce contenu dans la corbeille du projet (récupérables). */
    fun delete(files: ProjectFiles, entry: ModCatalog.Entry): List<String> {
        val removed = listOfNotNull(entry.behaviorPath, entry.clientPath).filter { files.exists(it) }
        removed.forEach { files.moveToTrash(it) }
        return removed
    }

    /** Valeur à afficher pour un réglage absent : celle utilisée par Minecraft par défaut. */
    fun valueOrDefault(entry: ModCatalog.Entry, stat: Stat): Double = entry.stats[stat] ?: stat.default

    /** Nouvelle texture vide (copie de l'existante si possible) pour personnaliser une variante sans toucher l'original. */
    fun textureCopyPath(entry: ModCatalog.Entry): String {
        val folder = when (entry.kind) { Kind.ENTITY -> "entity"; Kind.ITEM -> "items"; Kind.BLOCK -> "blocks" }
        return "$RP/textures/$folder/${entry.shortId}.png"
    }

}
