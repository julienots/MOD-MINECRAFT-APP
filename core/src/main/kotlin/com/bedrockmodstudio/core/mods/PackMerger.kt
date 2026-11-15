package com.bedrockmodstudio.core.mods

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.bedrock.LangFile
import com.bedrockmodstudio.core.bedrock.ScriptSetup
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.packaging.PackImporter
import com.bedrockmodstudio.core.packaging.SafeZip
import com.bedrockmodstudio.core.packaging.ZipLimits
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Ajoute le contenu d'un addon existant (.mcaddon, .mcpack, .zip) DANS un projet déjà ouvert,
 * pour combiner plusieurs mods en un seul et les modifier ensemble.
 *
 * - Les fichiers identiques sont ignorés, les fichiers différents suivent la [Policy] choisie.
 * - Les fichiers partagés (traductions, atlas de textures, blocks.json, sons) sont FUSIONNÉS clé par clé.
 * - Les scripts du mod sont rangés dans `scripts/mods/<nom>/` et importés depuis le point d'entrée du projet.
 * - Les identifiants déjà définis dans le projet sont signalés (deux définitions = une seule gagne en jeu).
 */
class PackMerger(private val store: ProjectStore, private val workDir: File, private val limits: ZipLimits = ZipLimits()) {

    enum class Policy(val labelFr: String) {
        KEEP_MINE("Garder mes fichiers en cas de conflit"),
        TAKE_THEIRS("Prendre les fichiers du mod (mes versions restent dans l'historique)"),
    }

    data class Report(
        val sourceName: String,
        val packNames: List<String>,
        val added: Int,
        val replaced: Int,
        val keptMine: List<String>,
        val identical: Int,
        val mergedShared: List<String>,
        val duplicateIds: List<String>,
        val scriptEntry: String?,
        val notes: List<String>,
        /** Contenus apportés par le mod (créatures, objets, blocs). */
        val newContent: List<String>,
    )

    private val sharedJson = mapOf(
        "$RP/textures/item_texture.json" to listOf("texture_data"),
        "$RP/textures/terrain_texture.json" to listOf("texture_data"),
        "$RP/textures/flipbook_textures.json" to emptyList(),
        "$RP/blocks.json" to emptyList(),
        "$RP/sounds/sound_definitions.json" to listOf("sound_definitions"),
        "$RP/sounds.json" to emptyList(),
    )
    private val skipNames = setOf("manifest.json", "pack_icon.png", "contents.json", "signatures.json", "languages.json")

    fun merge(projectId: String, input: InputStream, fileName: String, policy: Policy): Report {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "jar") throw UserFacingException("Les .jar sont des mods Java Edition : incompatibles avec Bedrock.")
        if (ext !in PackImporter.ARCHIVE_EXTENSIONS) throw UserFacingException("Format « .$ext » non pris en charge. Choisis un .mcaddon, .mcpack ou .zip.")
        val files = store.files(projectId)
        val meta = store.load(projectId)
        val before = ModCatalog.scan(files)
        val session = File(workDir, "merge_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val archive = File(session, "a.zip")
            copyLimited(input, archive)
            val x = File(session, "x")
            archive.inputStream().use { SafeZip.extract(it, x, limits) }
            x.walkTopDown().filter { it.isFile && it.extension.lowercase() in PackImporter.ARCHIVE_EXTENSIONS }.toList().forEach { nested ->
                runCatching { nested.inputStream().use { SafeZip.extract(it, File(nested.parentFile, nested.nameWithoutExtension + "_contenu"), limits) } }
                nested.delete()
            }
            val roots = x.walkTopDown().maxDepth(6).filter { it.isFile && it.name.equals("manifest.json", true) }.map { it.parentFile }
                .sortedBy { it.path.length }.toList().let { all -> all.filter { r -> all.none { o -> o != r && r.path.startsWith(o.path + File.separator) } } }
            if (roots.isEmpty()) throw UserFacingException("Aucun pack Bedrock dans ce fichier (pas de manifest.json).")

            var added = 0; var replaced = 0; var identical = 0
            val kept = mutableListOf<String>(); val shared = mutableListOf<String>(); val notes = mutableListOf<String>()
            val names = mutableListOf<String>()
            var scriptEntry: String? = null

            for (root in roots) {
                val manifest = runCatching { JsonParser.parse(File(root, "manifest.json").readText()).valueOrNull() as? JsonObject }.getOrNull()
                val types = manifest?.arr("modules")?.items?.mapNotNull { (it as? JsonObject)?.str("type") }?.toSet() ?: emptySet()
                val name = manifest?.obj("header")?.str("name")?.replace(Regex("§."), "")?.trim()?.ifEmpty { null } ?: root.name
                names += name
                val target = when {
                    "resources" in types -> RP
                    "data" in types || "script" in types -> BP
                    File(root, "textures").isDirectory -> RP
                    else -> BP
                }
                if (File(root, "contents.json").isFile) notes += "« $name » contient contents.json : il vient peut-être du Marketplace (contenu chiffré ou protégé par sa licence)."
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "mod" }.take(32)

                // Scripts : rangés à part pour ne jamais écraser ceux du projet.
                val scriptModule = manifest?.arr("modules")?.items?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("type") == "script" }
                val modEntry = scriptModule?.str("entry")
                val scriptsDir = File(root, "scripts")

                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(root).invariantSeparatorsPath
                    if (rel in skipNames || (rel.startsWith("texts/") && rel.endsWith(".lang"))) return@forEach
                    val dest = if (target == BP && rel.startsWith("scripts/") && scriptsDir.isDirectory) "$BP/scripts/mods/$slug/${rel.removePrefix("scripts/")}"
                        else "$target/$rel"
                    if (dest in sharedJson) {
                        if (mergeShared(files, dest, f, sharedJson.getValue(dest))) shared += dest
                        return@forEach
                    }
                    val bytes = f.readBytes()
                    if (!files.exists(dest)) { files.write(dest, bytes, "Ajouté depuis $name"); added++ }
                    else if (files.readBytes(dest).contentEquals(bytes)) identical++
                    else if (policy == Policy.TAKE_THEIRS) { files.write(dest, bytes, "Remplacé par $name"); replaced++ }
                    else kept += dest
                }
                // Traductions : on ajoute les clés manquantes sans écraser les tiennes.
                File(root, "texts").listFiles { f -> f.extension == "lang" }?.forEach { langFile ->
                    val dest = "$RP/texts/${langFile.name}"
                    val mine = LangFile(if (files.exists(dest)) files.readText(dest) else "")
                    var n = 0
                    LangFile(langFile.readText()).entries.forEach { (k, v) -> if (k != "pack.name" && k != "pack.description" && mine.putIfAbsent(k, v)) n++ }
                    if (n > 0) { files.writeText(dest, mine.toString(), "Traductions de $name"); shared += "$dest (+$n)" }
                }
                if (modEntry != null && scriptsDir.isDirectory) {
                    val module = "mods/$slug/${modEntry.removePrefix("scripts/")}"
                    val profile = BedrockVersions.byId(meta.targetVersion) ?: BedrockVersions.DEFAULT
                    val r = ScriptSetup.enable(store, projectId, profile, needsServerUi = manifest.arr("dependencies")?.items
                        ?.any { (it as? JsonObject)?.str("module_name") == "@minecraft/server-ui" } == true)
                    ScriptSetup.addImports(files, r.entry, listOf(module))
                    scriptEntry = "$BP/scripts/$module"
                    manifest.arr("dependencies")?.items?.mapNotNull { it as? JsonObject }?.forEach { d ->
                        val mod = d.str("module_name") ?: return@forEach
                        val v = d["version"]?.asString ?: return@forEach
                        if (v.contains("beta")) notes += "Le script de « $name » utilise $mod $v (version bêta) : active les « Fonctionnalités expérimentales » du monde, et vérifie que la version correspond à ton jeu."
                    }
                }
            }

            val after = ModCatalog.scan(files)
            val beforeIds = before.entries.map { it.kind to it.id }.toSet()
            val newContent = after.entries.filter { (it.kind to it.id) !in beforeIds }.map { "${it.kind.labelFr} : ${it.name}" }
            val dupes = duplicateIds(files)
            if (kept.isNotEmpty()) notes += "${kept.size} fichier(s) du mod n'ont pas été copiés car tu as déjà un fichier différent au même endroit."
            store.touch(projectId)
            return Report(fileName, names, added, replaced, kept, identical, shared, dupes, scriptEntry, notes, newContent)
        } finally {
            session.deleteRecursively()
        }
    }

    /** Identifiants définis par plusieurs fichiers du projet (seul l'un d'eux sera utilisé en jeu). */
    fun duplicateIds(files: com.bedrockmodstudio.core.storage.ProjectFiles): List<String> {
        val seen = HashMap<String, MutableList<String>>()
        for ((dir, key) in listOf("$BP/entities" to "minecraft:entity", "$BP/items" to "minecraft:item", "$BP/blocks" to "minecraft:block", "$RP/entity" to "minecraft:client_entity")) {
            for (p in ModCatalog.jsonFiles(files, dir)) {
                val id = ModCatalog.readJson(files, p)?.path(key, "description", "identifier")?.asString ?: continue
                seen.getOrPut("$key|$id") { mutableListOf() } += p
            }
        }
        return seen.filter { it.value.size > 1 }.map { (k, v) -> "${k.substringAfter('|')} défini dans ${v.joinToString(", ")}" }
    }

    /** Fusion clé par clé d'un JSON partagé. Renvoie vrai si le fichier du projet a changé. */
    private fun mergeShared(files: com.bedrockmodstudio.core.storage.ProjectFiles, dest: String, incoming: File, objectPath: List<String>): Boolean {
        val parsed = runCatching { JsonParser.parse(incoming.readText()).valueOrNull() }.getOrNull() ?: return false
        if (!files.exists(dest)) { files.writeText(dest, JsonWriter.write(parsed) + "\n", "Ajouté (fichier partagé)"); return true }
        if (parsed is JsonArray) {
            // flipbook_textures.json : liste d'animations de textures, on ajoute celles qui manquent.
            val mineArr = JsonParser.parse(files.readText(dest)).valueOrNull() as? JsonArray
                ?: throw UserFacingException("$dest contient une erreur JSON : corrige-le avant d'ajouter un mod.")
            val missing = parsed.items.filter { it !in mineArr.items }
            if (missing.isEmpty()) return false
            missing.forEach { mineArr.add(it) }
            files.writeText(dest, JsonWriter.write(mineArr) + "\n", "Fusion avec un mod")
            return true
        }
        val theirs = parsed as? JsonObject ?: return false
        val mine = JsonParser.parse(files.readText(dest)).valueOrNull() as? JsonObject
            ?: throw UserFacingException("$dest contient une erreur JSON : corrige-le avant d'ajouter un mod.")
        fun at(o: JsonObject): JsonObject? { var c: JsonObject? = o; for (s in objectPath) c = c?.obj(s); return c }
        val src = at(theirs) ?: return false
        var dst = mine
        for (s in objectPath) dst = dst.obj(s) ?: JsonObject().also { dst[s] = it }
        var changed = false
        for ((k, v) in src.entries) {
            if (objectPath.isEmpty() && k == "format_version") continue
            if (!dst.containsKey(k)) { dst[k] = v; changed = true }
        }
        if (changed) files.writeText(dest, JsonWriter.write(mine) + "\n", "Fusion avec un mod")
        return changed
    }

    private fun copyLimited(input: InputStream, out: File) {
        var total = 0L
        FileOutputStream(out).use { fos ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > limits.maxTotalBytes) throw UserFacingException("Fichier trop volumineux.")
                fos.write(buf, 0, n)
            }
        }
    }
}
