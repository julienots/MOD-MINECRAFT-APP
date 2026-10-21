package com.bedrockmodstudio.core.validation

import com.bedrockmodstudio.core.bedrock.BedrockVersionProfile
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.KnownComponents
import com.bedrockmodstudio.core.bedrock.LangFile
import com.bedrockmodstudio.core.image.PngInfo
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.nbt.Nbt
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.structure.StructureCodec
import com.bedrockmodstudio.core.structure.StructureGenerator
import java.io.File

enum class Severity(val labelFr: String) {
    ERROR("Erreur"), WARNING("Avertissement"), INFO("Information")
}

/**
 * Problème détecté. [code] est stable (utilisable pour traduire ou filtrer), [message] est en français.
 * [file] est relatif à la racine du projet.
 */
data class Issue(
    val severity: Severity,
    val code: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class ValidationReport(val issues: List<Issue>, val checkedFiles: Int) {
    val errors get() = issues.filter { it.severity == Severity.ERROR }
    val warnings get() = issues.filter { it.severity == Severity.WARNING }
    val hasErrors get() = errors.isNotEmpty()
}

/**
 * Valide un projet sous forme de dossiers : `behavior_pack/` et `resource_pack/` sous [projectRoot].
 * Ne modifie aucun fichier.
 *
 * Règle d'honnêteté : une référence introuvable dans le projet est une **erreur** si elle porte l'espace
 * de noms du projet (elle aurait forcément dû être créée), et seulement un **avertissement** sinon
 * (elle peut désigner une ressource vanilla de Minecraft, que l'application ne peut pas vérifier).
 */
class Validator(
    private val projectRoot: File,
    private val namespace: String?,
    private val profile: BedrockVersionProfile? = null,
    private val bpDirName: String = "behavior_pack",
    private val rpDirName: String = "resource_pack",
) {
    private val issues = mutableListOf<Issue>()
    private val parsed = HashMap<String, JsonValue>()
    private var checked = 0

    private val bp = File(projectRoot, bpDirName)
    private val rp = File(projectRoot, rpDirName)

    private fun add(sev: Severity, code: String, msg: String, file: String? = null, line: Int? = null, col: Int? = null) {
        issues += Issue(sev, code, msg, file, line, col)
    }

    private fun rel(f: File) = f.relativeTo(projectRoot).invariantSeparatorsPath

    fun run(): ValidationReport {
        if (!bp.isDirectory && !rp.isDirectory) {
            add(Severity.ERROR, "NO_PACKS", "Le projet ne contient ni behavior_pack ni resource_pack.")
            return ValidationReport(issues, 0)
        }
        parseAllJson()
        val bpManifest = checkManifest(bp, expectedModule = "data")
        val rpManifest = checkManifest(rp, expectedModule = "resources")
        checkUuidsAndDependencies(bpManifest, rpManifest)
        checkScripts(bpManifest)
        val entities = checkBehaviorIdentifiers()
        checkClientEntities(entities)
        checkTextureAtlases()
        checkComponentNames()
        checkItemAndBlockTextures()
        checkRecipeReferences()
        checkAttachables()
        checkStructures()
        checkParticles()
        checkLang()
        checkIcons()
        return ValidationReport(issues.sortedBy { it.severity.ordinal }, checked)
    }

    // ---- 1. Syntaxe JSON ----

    private fun parseAllJson() {
        for (packDir in listOf(bp, rp)) {
            if (!packDir.isDirectory) continue
            packDir.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() == "json" }.forEach { f ->
                checked++
                val path = rel(f)
                if (f.length() > 20_000_000) {
                    add(Severity.WARNING, "JSON_TOO_BIG", "Fichier trop volumineux pour être analysé (> 20 Mo).", path); return@forEach
                }
                when (val r = JsonParser.parse(f.readText())) {
                    is JsonParseResult.Success -> {
                        parsed[path] = r.value
                        r.warnings.forEach { w -> add(Severity.WARNING, "JSON_DUPLICATE_KEY", w.message, path, w.line, w.column) }
                    }
                    is JsonParseResult.Failure ->
                        add(Severity.ERROR, "JSON_SYNTAX", r.error.message, path, r.error.line, r.error.column)
                }
            }
        }
    }

    private fun json(dir: File, sub: String): Map<String, JsonValue> {
        val prefix = rel(File(dir, sub)) + "/"
        return parsed.filterKeys { it.startsWith(prefix) }
    }

    // ---- 2. Manifestes ----

    private fun checkManifest(packDir: File, expectedModule: String): JsonObject? {
        if (!packDir.isDirectory) {
            add(Severity.WARNING, "PACK_MISSING", "Dossier « ${packDir.name} » absent : un seul pack sera exporté.")
            return null
        }
        val path = rel(File(packDir, "manifest.json"))
        if (!File(packDir, "manifest.json").isFile) {
            add(Severity.ERROR, "MANIFEST_MISSING", "manifest.json manquant : Minecraft ne reconnaîtra pas ce pack.", rel(packDir)); return null
        }
        val m = parsed[path] as? JsonObject ?: run {
            if (path in parsed) add(Severity.ERROR, "MANIFEST_NOT_OBJECT", "Le manifeste doit être un objet JSON.", path)
            return null
        }
        val fv = m["format_version"]?.asInt
        if (fv == null) add(Severity.ERROR, "MANIFEST_FORMAT", "« format_version » absent ou invalide (2 attendu).", path)
        else if (fv !in 1..3) add(Severity.WARNING, "MANIFEST_FORMAT", "format_version $fv inhabituel (2 attendu).", path)

        val header = m.obj("header")
        if (header == null) {
            add(Severity.ERROR, "MANIFEST_HEADER", "Section « header » absente.", path); return m
        }
        if (header.str("name").isNullOrBlank()) add(Severity.ERROR, "MANIFEST_NAME", "Le pack n'a pas de nom (header.name).", path)
        if (!Identifiers.isValidUuid(header.str("uuid"))) add(Severity.ERROR, "MANIFEST_UUID", "UUID d'en-tête absent ou invalide.", path, header.line, header.column)
        if (!isVersion(header["version"])) add(Severity.ERROR, "MANIFEST_VERSION", "header.version doit être une liste de 3 nombres, ex. [1, 0, 0].", path)
        val minEngine = header["min_engine_version"]
        if (!isVersion(minEngine)) {
            add(Severity.ERROR, "MANIFEST_MIN_ENGINE", "header.min_engine_version doit être une liste de 3 nombres, ex. [1, 26, 50].", path)
        } else if (profile != null) {
            val v = (minEngine as JsonArray).items.map { it.asInt ?: 0 }
            if (BedrockVersions.compare(v, profile.minEngineVersion) != 0) {
                add(Severity.WARNING, "MIN_ENGINE_MISMATCH",
                    "min_engine_version ${v.joinToString(".")} différent de la version cible du projet (${profile.id}).", path)
            }
        }
        val modules = m.arr("modules")
        if (modules == null || modules.size == 0) {
            add(Severity.ERROR, "MANIFEST_MODULES", "Aucun module déclaré (« modules »).", path)
        } else {
            val types = modules.items.mapNotNull { (it as? JsonObject)?.str("type") }
            modules.items.forEach { mod ->
                val o = mod as? JsonObject ?: return@forEach
                if (!Identifiers.isValidUuid(o.str("uuid"))) add(Severity.ERROR, "MODULE_UUID", "Module sans UUID valide.", path, o.line, o.column)
                if (!isVersion(o["version"])) add(Severity.ERROR, "MODULE_VERSION", "Version de module invalide.", path, o.line, o.column)
                val t = o.str("type")
                if (t !in setOf("data", "resources", "script", "client_data", "world_template", "skin_pack", "interface", "javascript")) {
                    add(Severity.WARNING, "MODULE_TYPE", "Type de module inconnu « $t ».", path, o.line, o.column)
                }
            }
            if (expectedModule !in types) {
                add(Severity.ERROR, "MODULE_EXPECTED", "Le pack « ${packDir.name} » devrait contenir un module de type « $expectedModule ».", path)
            }
        }
        return m
    }

    private fun isVersion(v: JsonValue?): Boolean {
        val a = v as? JsonArray ?: return false
        return a.size == 3 && a.items.all { it.asInt != null && it.asInt!! >= 0 }
    }

    private fun checkUuidsAndDependencies(bpM: JsonObject?, rpM: JsonObject?) {
        val seen = HashMap<String, String>()
        for ((m, file) in listOf(bpM to "$bpDirName/manifest.json", rpM to "$rpDirName/manifest.json")) {
            m ?: continue
            val uuids = listOfNotNull(m.obj("header")?.str("uuid")) +
                (m.arr("modules")?.items ?: emptyList()).mapNotNull { (it as? JsonObject)?.str("uuid") }
            for (u in uuids) {
                val key = u.lowercase()
                if (key in seen) add(Severity.ERROR, "UUID_DUPLICATE", "UUID utilisé deux fois ($u) : chaque en-tête et chaque module doivent avoir un UUID unique.", file)
                else seen[key] = file
            }
        }
        if (bpM != null && rpM != null) {
            val rpUuid = rpM.obj("header")?.str("uuid")
            val rpVersion = rpM.obj("header")?.get("version")
            val deps = bpM.arr("dependencies")?.items?.mapNotNull { it as? JsonObject } ?: emptyList()
            val dep = deps.firstOrNull { it.str("uuid").equals(rpUuid, ignoreCase = true) }
            if (dep == null) {
                add(Severity.WARNING, "BP_RP_DEPENDENCY",
                    "Le behavior pack ne dépend pas du resource pack : il faudra activer les deux packs séparément dans Minecraft.",
                    "$bpDirName/manifest.json")
            } else if (rpVersion != null && dep["version"] != rpVersion) {
                add(Severity.ERROR, "BP_RP_DEPENDENCY_VERSION",
                    "La dépendance vers le resource pack demande la version ${dep["version"]} mais le resource pack est en version $rpVersion.",
                    "$bpDirName/manifest.json")
            }
        }
    }

    // ---- 3. Scripts ----

    private fun checkScripts(bpM: JsonObject?) {
        bpM ?: return
        val file = "$bpDirName/manifest.json"
        val scriptModules = bpM.arr("modules")?.items?.mapNotNull { it as? JsonObject }?.filter { it.str("type") == "script" } ?: emptyList()
        val deps = bpM.arr("dependencies")?.items?.mapNotNull { it as? JsonObject } ?: emptyList()
        val hasJsFiles = File(bp, "scripts").walkTopDown().any { it.isFile && it.extension == "js" }
        for (m in scriptModules) {
            val entry = m.str("entry")
            if (entry == null) add(Severity.ERROR, "SCRIPT_ENTRY", "Module de script sans « entry » (fichier de départ).", file)
            else if (!File(bp, entry).isFile) add(Severity.ERROR, "SCRIPT_ENTRY_MISSING", "Le fichier de script « $entry » est introuvable.", file)
            if (m.str("language") != "javascript") add(Severity.WARNING, "SCRIPT_LANGUAGE", "Le module de script devrait déclarer \"language\": \"javascript\".", file)
        }
        val serverDep = deps.firstOrNull { it.str("module_name") == "@minecraft/server" }
        if (scriptModules.isNotEmpty() && serverDep == null) {
            add(Severity.WARNING, "SCRIPT_NO_SERVER_DEP", "Module de script sans dépendance « @minecraft/server » : la plupart des scripts ne fonctionneront pas.", file)
        }
        if (serverDep != null && profile != null) {
            val v = serverDep.str("version")
            if (v != null && v != profile.scriptServerVersion && !v.contains("beta")) {
                add(Severity.WARNING, "SCRIPT_API_VERSION",
                    "@minecraft/server $v déclaré ; la version stable de ${profile.id} est ${profile.scriptServerVersion}.", file)
            }
            if (v != null && v.contains("beta")) {
                add(Severity.WARNING, "SCRIPT_API_BETA",
                    "@minecraft/server $v est une version bêta : il faudra activer les « API bêta » dans les expérimentations du monde.", file)
            }
        }
        checkScriptImports(deps)
        if (hasJsFiles && scriptModules.isEmpty()) {
            add(Severity.WARNING, "SCRIPT_NOT_DECLARED", "Des fichiers .js existent mais aucun module de script n'est déclaré : ils ne seront pas exécutés.", file)
        }
    }

    private val importRegex = Regex("""(?m)^\s*(?:import|export)\s+(?:[^'"`;]*?\s+from\s+)?["']([^"']+)["']""")

    /** Chaque import doit désigner un fichier du pack ou un module @minecraft déclaré dans le manifeste. */
    private fun checkScriptImports(deps: List<JsonObject>) {
        val scripts = File(bp, "scripts")
        if (!scripts.isDirectory) return
        val declared = deps.mapNotNull { it.str("module_name") }.toSet()
        scripts.walkTopDown().filter { it.isFile && it.extension == "js" }.forEach { f ->
            val path = rel(f)
            val text = f.readText()
            text.lineSequence().forEachIndexed { i, line ->
                val m = importRegex.find(line) ?: return@forEachIndexed
                val target = m.groupValues[1]
                when {
                    target.startsWith(".") -> {
                        val resolved = File(f.parentFile, target).canonicalFile
                        val ok = resolved.isFile || File(resolved.path + ".js").isFile
                        if (!ok) add(Severity.ERROR, "SCRIPT_IMPORT_MISSING", "Le script importe « $target », fichier introuvable.", path, i + 1)
                    }
                    target.startsWith("@minecraft/") -> if (target !in declared) add(Severity.ERROR, "SCRIPT_DEPENDENCY_MISSING",
                        "Le script utilise « $target » mais le manifeste ne le déclare pas dans ses dépendances.", "$bpDirName/manifest.json", i + 1)
                    else -> add(Severity.ERROR, "SCRIPT_IMPORT_UNSUPPORTED",
                        "Import « $target » : Minecraft ne charge que les modules @minecraft/… et les fichiers du pack (pas de paquets npm).", path, i + 1)
                }
            }
            val balance = bracketProblem(text)
            if (balance != null) add(Severity.ERROR, "SCRIPT_SYNTAX", balance.second, path, balance.first)
        }
    }

    /** Vérification légère : parenthèses, crochets, accolades et chaînes équilibrés (hors commentaires). */
    private fun bracketProblem(code: String): Pair<Int, String>? {
        val stack = ArrayDeque<Pair<Char, Int>>()
        var line = 1; var i = 0
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\n' -> line++
                c == '/' && i + 1 < code.length && code[i + 1] == '/' -> { while (i < code.length && code[i] != '\n') i++; continue }
                c == '/' && i + 1 < code.length && code[i + 1] == '*' -> {
                    val end = code.indexOf("*/", i + 2)
                    if (end < 0) return line to "Commentaire /* jamais refermé."
                    line += code.substring(i, end).count { it == '\n' }; i = end + 2; continue
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val start = line; i++
                    while (i < code.length && code[i] != c) {
                        if (code[i] == '\\') i++
                        else if (code[i] == '\n') { if (c != '`') return start to "Texte non terminé (guillemet manquant)."; line++ }
                        i++
                    }
                    if (i >= code.length) return start to "Texte non terminé (guillemet manquant)."
                }
                c in "([{" -> stack.addLast(c to line)
                c in ")]}" -> {
                    val open = stack.removeLastOrNull() ?: return line to "« $c » fermant sans ouverture correspondante."
                    if (open.first != pairs[c]) return line to "« $c » ne correspond pas à « ${open.first} » ouvert ligne ${open.second}."
                }
            }
            i++
        }
        return stack.lastOrNull()?.let { it.second to "« ${it.first} » ouvert ligne ${it.second} jamais refermé." }
    }

    // ---- 4. Identifiants (behavior pack) ----

    private data class Defined(val id: String, val kind: String, val file: String)

    private fun identifierOf(v: JsonValue, rootKey: String): String? = v.path(rootKey, "description", "identifier")?.asString

    private fun checkBehaviorIdentifiers(): Set<String> {
        val defs = mutableListOf<Defined>()
        for ((sub, key, kind) in listOf(
            Triple("entities", "minecraft:entity", "entité"),
            Triple("items", "minecraft:item", "objet"),
            Triple("blocks", "minecraft:block", "bloc"),
        )) {
            for ((path, v) in json(bp, sub)) {
                val id = identifierOf(v, key)
                if (id == null) {
                    add(Severity.ERROR, "IDENTIFIER_MISSING", "Aucun identifiant trouvé ($key → description → identifier).", path); continue
                }
                if (!id.contains(':')) add(Severity.ERROR, "IDENTIFIER_NO_NAMESPACE", "L'identifiant « $id » doit avoir un espace de noms (ex. monmod:$id).", path)
                else if (id.startsWith("minecraft:") && kind != "entité") add(Severity.WARNING, "IDENTIFIER_VANILLA",
                    "« $id » utilise l'espace de noms minecraft : réservé au contenu du jeu.", path)
                else if (!Identifiers.isValidIdentifier(id) && !id.startsWith("minecraft:")) add(Severity.WARNING, "IDENTIFIER_FORMAT",
                    "« $id » : utilise uniquement des minuscules, chiffres, _ et . dans les identifiants.", path)
                defs += Defined(id, kind, path)
                definedIds += id
                if (kind == "entité") checkLootReference(v, path)
            }
        }
        defs.groupBy { it.id to it.kind }.filter { it.value.size > 1 }.forEach { (k, list) ->
            add(Severity.ERROR, "IDENTIFIER_DUPLICATE",
                "L'identifiant « ${k.first} » (${k.second}) est défini ${list.size} fois : ${list.joinToString { it.file }}.", list.first().file)
        }
        // Recettes : identifiants uniques entre elles.
        val recipes = json(bp, "recipes").mapNotNull { (path, v) ->
            (v as? JsonObject)?.entries?.firstOrNull { it.key.startsWith("minecraft:recipe_") }?.value
                ?.path("description", "identifier")?.asString?.let { it to path }
        }
        recipes.groupBy { it.first }.filter { it.value.size > 1 }.forEach { (id, list) ->
            add(Severity.ERROR, "RECIPE_DUPLICATE", "La recette « $id » est définie plusieurs fois.", list.first().second)
        }
        return defs.filter { it.kind == "entité" }.map { it.id }.toSet()
    }

    private fun checkLootReference(entity: JsonValue, path: String) {
        val comps = entity.path("minecraft:entity", "components") as? JsonObject ?: return
        val table = comps.obj("minecraft:loot")?.str("table") ?: return
        if (!File(bp, table).isFile) refMissing("LOOT_TABLE_MISSING", "Table de butin « $table » introuvable dans le behavior pack.", table, path)
    }

    /**
     * Ressources vanilla dont l'existence a été vérifiée dans Mojang/bedrock-samples (v1.26.50.4) :
     * pas d'avertissement quand le projet y fait référence.
     */
    private val verifiedVanilla = setOf("textures/misc/enchanted_actor_glint", "textures/misc/enchanted_item_glint", "textures/particle/particles")

    /** Erreur si la référence porte l'espace de noms du projet, avertissement sinon (peut-être vanilla). */
    private fun refMissing(code: String, message: String, reference: String, file: String) {
        if (reference in verifiedVanilla) return
        val ours = namespace != null && reference.contains(namespace)
        add(if (ours) Severity.ERROR else Severity.WARNING, code,
            if (ours) message else "$message (peut-être une ressource de Minecraft : vérification impossible)", file)
    }

    // ---- 5. Entités client (resource pack) ----

    private fun checkClientEntities(behaviorEntities: Set<String>) {
        val geometries = collectGeometries()
        val animations = collectKeys("animations", "animations") + collectKeys("animation_controllers", "animation_controllers")
        val renderControllers = collectKeys("render_controllers", "render_controllers")
        val clientIds = mutableSetOf<String>()

        for ((path, v) in json(rp, "entity")) {
            val desc = v.path("minecraft:client_entity", "description") as? JsonObject
            if (desc == null) { add(Severity.ERROR, "CLIENT_ENTITY_FORMAT", "Section minecraft:client_entity → description absente.", path); continue }
            val id = desc.str("identifier")
            if (id == null) { add(Severity.ERROR, "IDENTIFIER_MISSING", "Entité client sans identifiant.", path); continue }
            clientIds += id
            if (!id.startsWith("minecraft:") && id !in behaviorEntities && bp.isDirectory) {
                add(Severity.WARNING, "CLIENT_WITHOUT_BEHAVIOR", "L'entité « $id » a une apparence mais aucun comportement dans le behavior pack.", path)
            }
            (desc.obj("textures")?.entries ?: emptySet()).forEach { (_, t) ->
                val tex = t.asString ?: return@forEach
                if (!textureExists(tex)) refMissing("TEXTURE_MISSING", "Texture « $tex » introuvable (.png ou .tga).", tex, path)
            }
            (desc.obj("geometry")?.entries ?: emptySet()).forEach { (_, g) ->
                val geo = g.asString ?: return@forEach
                if (geo !in geometries) refMissing("GEOMETRY_MISSING", "Modèle « $geo » introuvable dans models/.", geo, path)
            }
            (desc.obj("animations")?.entries ?: emptySet()).forEach { (_, a) ->
                val anim = a.asString ?: return@forEach
                if (anim !in animations) refMissing("ANIMATION_MISSING", "Animation ou contrôleur « $anim » introuvable.", anim, path)
            }
            (desc.arr("render_controllers")?.items ?: emptyList()).forEach { rc ->
                val name = rc.asString ?: (rc as? JsonObject)?.keys?.firstOrNull() ?: return@forEach
                if (name !in renderControllers && name != "controller.render.default") {
                    refMissing("RENDER_CONTROLLER_MISSING", "Contrôleur de rendu « $name » introuvable.", name, path)
                }
            }
        }
        for (id in behaviorEntities) {
            if (!id.startsWith("minecraft:") && id !in clientIds && rp.isDirectory) {
                add(Severity.WARNING, "BEHAVIOR_WITHOUT_CLIENT", "L'entité « $id » n'a pas d'apparence (fichier dans resource_pack/entity) : elle sera invisible.")
            }
        }
    }

    private fun collectGeometries(): Set<String> {
        val ids = mutableSetOf<String>()
        for ((_, v) in json(rp, "models")) {
            val o = v as? JsonObject ?: continue
            o.arr("minecraft:geometry")?.items?.forEach { g -> g.path("description", "identifier")?.asString?.let { ids += it } }
            // Ancien format : clés « geometry.nom » ou « geometry.nom:geometry.parent ».
            o.keys.filter { it.startsWith("geometry.") }.forEach { ids += it.substringBefore(':') }
        }
        return ids
    }

    private fun collectKeys(sub: String, rootKey: String): Set<String> =
        json(rp, sub).values.flatMap { (it as? JsonObject)?.obj(rootKey)?.keys ?: emptySet() }.toSet()

    private fun textureExists(ref: String) =
        File(rp, "$ref.png").isFile || File(rp, "$ref.tga").isFile || File(rp, ref).isFile

    private fun checkTextureAtlases() {
        for (atlas in listOf("textures/item_texture.json", "textures/terrain_texture.json")) {
            val path = "$rpDirName/$atlas"
            val data = (parsed[path] as? JsonObject)?.obj("texture_data") ?: continue
            for ((key, entry) in data.entries) {
                val tex = entry.path("textures")
                val refs = when (tex) {
                    is JsonArray -> tex.items.mapNotNull { it.asString ?: (it as? JsonObject)?.str("path") }
                    is JsonObject -> listOfNotNull(tex.str("path"))
                    else -> listOfNotNull(tex?.asString)
                }
                refs.forEach { r -> if (!textureExists(r)) refMissing("ATLAS_TEXTURE_MISSING", "Texture « $r » (clé « $key ») introuvable.", r, path) }
            }
        }
        // Les textures doivent avoir des dimensions raisonnables.
        if (rp.isDirectory) File(rp, "textures").walkTopDown().filter { it.isFile && it.extension.lowercase() == "png" }.forEach { f ->
            val head = ByteArray(32).also { b -> f.inputStream().use { it.read(b) } }
            val size = PngInfo.read(head)
            if (size == null) add(Severity.ERROR, "PNG_INVALID", "Ce fichier .png n'est pas une image PNG valide.", rel(f))
            else if (size.width > 4096 || size.height > 4096) add(Severity.WARNING, "PNG_TOO_BIG", "Texture très grande (${size.width}×${size.height}) : risque de lenteur.", rel(f))
        }
    }

    // ---- 6. Traductions et icônes ----

    // ---- 7. Composants, textures d'objets et de blocs, recettes, armures portées ----

    private val definedIds = mutableSetOf<String>()

    /** Repère les fautes de frappe dans les noms de composants (ex. « minecraft:helth »). */
    private fun checkComponentNames() {
        for ((sub, rootKey, known) in listOf(
            Triple("entities", "minecraft:entity", KnownComponents.ENTITY),
            Triple("items", "minecraft:item", KnownComponents.ITEM),
            Triple("blocks", "minecraft:block", KnownComponents.BLOCK + KnownComponents.BLOCK_EXTRA),
        )) {
            for ((path, v) in json(bp, sub)) {
                val root = v.path(rootKey) as? JsonObject ?: continue
                val names = mutableListOf<JsonObject>()
                root.obj("components")?.let { names += it }
                root.obj("component_groups")?.entries?.forEach { (_, g) -> (g as? JsonObject)?.let { names += it } }
                root.arr("permutations")?.items?.forEach { pm -> (pm as? JsonObject)?.obj("components")?.let { names += it } }
                for (o in names) for (key in o.keys) {
                    if (!key.startsWith("minecraft:") || key in known) continue
                    val guess = known.minByOrNull { editDistance(it, key) }
                    val value = o[key]
                    if (guess != null && editDistance(guess, key) <= 2) {
                        add(Severity.WARNING, "COMPONENT_TYPO", "Composant inconnu « $key » : vouliez-vous dire « $guess » ?", path, value?.line, value?.column)
                    } else {
                        add(Severity.INFO, "COMPONENT_UNLISTED",
                            "Composant « $key » absent des références officielles connues de l'application (récent, expérimental ou mal orthographié ?).",
                            path, value?.line, value?.column)
                    }
                }
            }
        }
    }

    private fun atlasKeys(file: String): Set<String> =
        ((parsed["$rpDirName/textures/$file"] as? JsonObject)?.obj("texture_data")?.keys) ?: emptySet()

    private fun checkItemAndBlockTextures() {
        val items = atlasKeys("item_texture.json")
        for ((path, v) in json(bp, "items")) {
            val icon = v.path("minecraft:item", "components", "minecraft:icon")
            val key = icon?.asString ?: icon.path("textures", "default")?.asString ?: icon.path("texture")?.asString ?: continue
            if (key !in items) refMissing("ITEM_ICON_MISSING", "Icône « $key » absente de textures/item_texture.json.", key, path)
        }
        val terrain = atlasKeys("terrain_texture.json")
        for ((path, v) in json(bp, "blocks")) {
            val mats = mutableListOf<JsonObject>()
            (v.path("minecraft:block", "components", "minecraft:material_instances") as? JsonObject)?.let { mats += it }
            (v.path("minecraft:block", "permutations") as? JsonArray)?.items?.forEach { pm ->
                (pm.path("components", "minecraft:material_instances") as? JsonObject)?.let { mats += it }
            }
            for (m in mats) for ((_, inst) in m.entries) {
                val tex = (inst as? JsonObject)?.str("texture") ?: continue
                if (tex !in terrain) refMissing("BLOCK_TEXTURE_MISSING", "Texture « $tex » absente de textures/terrain_texture.json.", tex, path)
            }
        }
    }

    /** Une recette qui utilise ou produit un objet de l'espace de noms du projet doit le trouver dans le projet. */
    private fun checkRecipeReferences() {
        val ns = namespace ?: return
        // Les œufs d'apparition des entités sont aussi des objets valides.
        val known = definedIds + definedIds.map { "${it}_spawn_egg" }
        for ((path, v) in json(bp, "recipes")) {
            val refs = mutableListOf<String>()
            fun collect(x: JsonValue?) {
                when (x) {
                    is JsonObject -> { x.str("item")?.let { refs += it }; x.entries.forEach { if (it.key != "description") collect(it.value) } }
                    is JsonArray -> x.items.forEach { collect(it) }
                    is com.bedrockmodstudio.core.json.JsonString -> if (x.value.startsWith("$ns:")) refs += x.value
                    else -> Unit
                }
            }
            collect(v)
            refs.filter { it.startsWith("$ns:") && it !in known }.distinct().forEach {
                add(Severity.ERROR, "RECIPE_UNKNOWN_ITEM", "La recette utilise « $it », qui n'existe pas dans le projet.", path)
            }
        }
    }

    private fun checkAttachables() {
        for ((path, v) in json(rp, "attachables")) {
            val desc = v.path("minecraft:attachable", "description") as? JsonObject ?: continue
            (desc.obj("textures")?.entries ?: emptySet()).forEach { (_, t) ->
                val tex = t.asString ?: return@forEach
                if (!textureExists(tex)) refMissing("TEXTURE_MISSING", "Texture « $tex » introuvable (.png ou .tga).", tex, path)
            }
        }
    }

    // ---- Structures et génération naturelle ----

    private fun checkStructures() {
        val dir = File(bp, "structures")
        val ids = mutableSetOf<String>()
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension.lowercase() == "mcstructure" }.forEach { f ->
            checked++
            val path = rel(f)
            StructureGenerator.idForPath(path.removePrefix("$bpDirName/").let { "behavior_pack/$it" })?.let { ids += it }
            val s = try { StructureCodec.read(f.readBytes()) } catch (e: UserFacingException) {
                if (e.message?.contains("trop grandes") != true) add(Severity.ERROR, "STRUCTURE_INVALID", e.message ?: "Structure illisible.", path)
                return@forEach
            }
            for (entry in s.positionData.map.values) {
                val table = ((entry as? Nbt.Compound)?.get("block_entity_data") as? Nbt.Compound)?.get("LootTable") as? Nbt.Str ?: continue
                if (!File(bp, table.v).isFile) refMissing("LOOT_TABLE_MISSING", "Table de butin « ${table.v} » du coffre introuvable.", table.v, path)
            }
            s.palette.filter { it.name.isBlank() }.forEach { _ -> add(Severity.ERROR, "STRUCTURE_INVALID", "Bloc sans nom dans la palette.", path) }
        }
        val features = mutableSetOf<String>()
        for ((path, v) in json(bp, "features")) {
            val root = (v as? JsonObject)?.entries?.firstOrNull { it.key.startsWith("minecraft:") }?.value as? JsonObject ?: continue
            val id = root.path("description", "identifier")?.asString ?: continue
            features += id
            if (id.substringAfter(':') != path.substringAfterLast('/').removeSuffix(".json")) {
                add(Severity.WARNING, "FEATURE_FILE_NAME", "Le nom du fichier doit être identique à l'identifiant (« ${id.substringAfter(':')}.json »).", path)
            }
            val structure = (v as JsonObject).obj("minecraft:structure_template_feature")?.str("structure_name") ?: continue
            if (structure !in ids) refMissing("STRUCTURE_MISSING", "Structure « $structure » introuvable dans behavior_pack/structures.", structure, path)
        }
        for ((path, v) in json(bp, "feature_rules")) {
            val desc = v.path("minecraft:feature_rules", "description") as? JsonObject ?: continue
            val id = desc.str("identifier") ?: continue
            if (id.substringAfter(':') != path.substringAfterLast('/').removeSuffix(".json")) {
                add(Severity.WARNING, "FEATURE_FILE_NAME", "Le nom du fichier doit être identique à l'identifiant (« ${id.substringAfter(':')}.json »).", path)
            }
            val placed = desc.str("places_feature") ?: continue
            if (placed !in features) refMissing("FEATURE_MISSING", "Élément de génération « $placed » introuvable dans behavior_pack/features.", placed, path)
        }
    }

    private fun checkParticles() {
        for ((path, v) in json(rp, "particles")) {
            val desc = v.path("particle_effect", "description") as? JsonObject
            if (desc == null) { add(Severity.WARNING, "PARTICLE_FORMAT", "Fichier de particules sans « particle_effect.description ».", path); continue }
            if (desc.str("identifier") == null) add(Severity.ERROR, "PARTICLE_FORMAT", "Effet de particules sans identifiant.", path)
            val tex = desc.obj("basic_render_parameters")?.str("texture") ?: continue
            if (!textureExists(tex)) refMissing("TEXTURE_MISSING", "Texture de particules « $tex » introuvable.", tex, path)
        }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun checkLang() {
        if (!rp.isDirectory) return
        val en = File(rp, "texts/en_US.lang")
        if (!en.isFile) {
            add(Severity.WARNING, "LANG_EN_MISSING", "texts/en_US.lang absent : c'est la langue de secours de Minecraft, les noms pourraient s'afficher sous forme de code.")
            return
        }
        val keys = LangFile(en.readText()).entries.keys
        val entities = json(bp, "entities").values.mapNotNull { identifierOf(it, "minecraft:entity") }.filter { !it.startsWith("minecraft:") }
        for (id in entities) if ("entity.$id.name" !in keys) {
            add(Severity.INFO, "LANG_ENTITY_NAME", "Pas de nom traduit pour « $id » (clé entity.$id.name).", "$rpDirName/texts/en_US.lang")
        }
    }

    private fun checkIcons() {
        for (dir in listOf(bp, rp)) if (dir.isDirectory && !File(dir, "pack_icon.png").isFile) {
            add(Severity.INFO, "ICON_MISSING", "Pas d'icône (pack_icon.png) : Minecraft affichera une icône par défaut.", rel(dir))
        }
    }
}
