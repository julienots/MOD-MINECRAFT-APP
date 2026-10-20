package com.bedrockmodstudio.core.packaging

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.LangFile
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonValue
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model.PackIds
import com.bedrockmodstudio.core.model.PackVersion
import com.bedrockmodstudio.core.model.ProjectMeta
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.validation.Severity
import com.bedrockmodstudio.core.validation.Validator
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class ImportedPack(
    val folder: String,
    val name: String,
    val kind: String,
    val uuid: String?,
    val version: String?,
    /** Rôle dans le projet : « behavior_pack », « resource_pack », ou conservé tel quel. */
    val usedAs: String,
)

data class ImportReport(
    val sourceName: String,
    val packs: List<ImportedPack>,
    /** Ressources reconnues, par catégorie (entités, objets, textures…). */
    val recognized: Map<String, Int>,
    val editableFiles: Int,
    val binaryFiles: Int,
    val unknownFiles: List<String>,
    val invalidJson: List<String>,
    val dependencies: List<String>,
    val skippedEntries: List<String>,
    val notes: List<String>,
    val validationErrors: Int,
    val validationWarnings: Int,
)

sealed class ImportOutcome {
    data class Success(val meta: ProjectMeta, val report: ImportReport) : ImportOutcome()
    data class Rejected(val message: String) : ImportOutcome()
}

/**
 * Reconstruit un projet éditable à partir d'un .mcaddon, .mcpack ou .zip.
 * - Le fichier d'origine est conservé intact dans `.imported/original/`.
 * - Aucun fichier inconnu n'est supprimé : tout le contenu des packs est copié.
 * - Les packs qui ne sont ni le behavior ni le resource pack principal sont conservés dans `.imported/extra_packs/`.
 */
class PackImporter(
    private val store: ProjectStore,
    private val workDir: File,
    private val limits: ZipLimits = ZipLimits(),
) {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("mcaddon", "mcpack", "zip")
        private val TEXT_EXT = setOf("json", "lang", "mcfunction", "js", "ts", "txt", "md", "material")
        private val BINARY_EXT = setOf("png", "tga", "jpg", "jpeg", "ogg", "wav", "fsb", "mcstructure", "nbt", "ttf")
        private val CATEGORIES = linkedMapOf(
            "entities/" to "Entités (comportement)", "items/" to "Objets", "blocks/" to "Blocs", "recipes/" to "Recettes",
            "loot_tables/" to "Tables de butin", "spawn_rules/" to "Règles d'apparition", "functions/" to "Fonctions",
            "structures/" to "Structures", "scripts/" to "Scripts", "trading/" to "Échanges", "biomes/" to "Biomes",
            "feature_rules/" to "Règles de génération", "features/" to "Éléments de génération",
            "entity/" to "Entités (apparence)", "models/" to "Modèles 3D", "animations/" to "Animations",
            "animation_controllers/" to "Contrôleurs d'animation", "render_controllers/" to "Contrôleurs de rendu",
            "textures/" to "Textures", "sounds/" to "Sons", "particles/" to "Particules", "attachables/" to "Équipements portés",
            "texts/" to "Traductions", "ui/" to "Interface (JSON UI)",
        )
    }

    fun import(input: InputStream, fileName: String): ImportOutcome {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "jar") return ImportOutcome.Rejected(
            "Les fichiers .jar sont des mods Minecraft Java Edition (Forge, Fabric…). Ils ne sont pas compatibles avec Bedrock Edition et ne peuvent pas être convertis automatiquement.")
        if (ext !in ARCHIVE_EXTENSIONS) return ImportOutcome.Rejected(
            "Format « .$ext » non pris en charge pour créer un projet. Formats acceptés : .mcaddon, .mcpack, .zip. (Les fichiers .json et .png s'importent depuis l'explorateur de fichiers d'un projet.)")

        val session = File(workDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Copie de l'original (plafonnée) pour le conserver tel quel.
            val original = File(session, "original.bin")
            copyLimited(input, original, limits.maxTotalBytes)
            val extracted = File(session, "x")
            val res = original.inputStream().use { SafeZip.extract(it, extracted, limits) }
            val skipped = res.skipped.toMutableList()

            // Archives imbriquées (.mcaddon contenant des .mcpack).
            extracted.walkTopDown().filter { it.isFile && it.extension.lowercase() in ARCHIVE_EXTENSIONS }.toList().forEach { nested ->
                val target = File(nested.parentFile, nested.nameWithoutExtension + "_contenu")
                try {
                    val r = nested.inputStream().use { SafeZip.extract(it, target, limits) }
                    skipped += r.skipped
                    nested.delete() // l'original complet reste dans .imported/original
                } catch (e: UserFacingException) {
                    skipped += "${nested.name} : ${e.message}"
                }
            }

            // Sauvegarde produite par l'application : on restaure le projet entier, sous un nouvel identifiant.
            if (File(extracted, ProjectExporter.BACKUP_MARKER).isFile && File(extracted, "project/${ProjectMeta.FILE_NAME}").isFile) {
                return restoreBackup(File(extracted, "project"), fileName, skipped)
            }

            val packRoots = findPackRoots(extracted)
            if (packRoots.isEmpty()) return ImportOutcome.Rejected(explainNoPack(extracted))

            val packs = packRoots.map { analyzePack(it, extracted) }
            val bpPack = packs.firstOrNull { "data" in it.moduleTypes || "script" in it.moduleTypes }
            val rpPack = packs.firstOrNull { "resources" in it.moduleTypes }
            val extras = packs.filter { it !== bpPack && it !== rpPack }

            val meta = buildMeta(fileName, bpPack, rpPack)
            val dir = store.dirOf(meta.id)
            dir.mkdirs()
            bpPack?.root?.copyRecursively(File(dir, "behavior_pack"))
            rpPack?.root?.copyRecursively(File(dir, "resource_pack"))
            val importedDir = File(dir, ProjectFiles.IMPORTED_DIR)
            original.copyTo(File(importedDir, "original/${File(fileName).name}"))
            extras.forEach { it.root.copyRecursively(File(importedDir, "extra_packs/${it.root.name}")) }
            store.saveMeta(meta)

            val report = buildReport(fileName, packs, bpPack, rpPack, dir, skipped, meta)
            return ImportOutcome.Success(store.load(meta.id), report)
        } catch (e: UserFacingException) {
            return ImportOutcome.Rejected(e.message ?: "Import impossible.")
        } finally {
            session.deleteRecursively()
        }
    }

    private fun restoreBackup(projectDir: File, fileName: String, skipped: List<String>): ImportOutcome {
        val old = JsonParser.parse(File(projectDir, ProjectMeta.FILE_NAME).readText()).valueOrNull()?.let { ProjectMeta.fromJson(it) }
            ?: return ImportOutcome.Rejected("Sauvegarde endommagée : project.json illisible.")
        val meta = old.copy(id = store.newId(), name = old.name + " (restauré)")
        val dir = store.dirOf(meta.id)
        projectDir.copyRecursively(dir)
        store.saveMeta(meta)
        val validation = Validator(dir, meta.namespace, BedrockVersions.byId(meta.targetVersion)).run()
        val report = ImportReport(
            sourceName = fileName, packs = emptyList(), recognized = emptyMap(),
            editableFiles = 0, binaryFiles = 0, unknownFiles = emptyList(), invalidJson = emptyList(),
            dependencies = emptyList(), skippedEntries = skipped,
            notes = listOf("Sauvegarde Bedrock Mod Studio restaurée comme nouveau projet « ${meta.name} ». Le projet d'origine, s'il existe encore, n'a pas été modifié."),
            validationErrors = validation.errors.size, validationWarnings = validation.warnings.size,
        )
        return ImportOutcome.Success(store.load(meta.id), report)
    }

    private fun copyLimited(input: InputStream, out: File, max: Long) {
        var total = 0L
        FileOutputStream(out).use { fos ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw UserFacingException("Fichier trop volumineux (plus de ${max / 1024 / 1024} Mo).")
                fos.write(buf, 0, n)
            }
        }
    }

    private class PackInfo(
        val root: File,
        val manifest: JsonObject?,
        val moduleTypes: Set<String>,
        val name: String,
        val manifestError: String?,
    )

    /** Dossiers contenant un manifest.json, en ignorant les packs imbriqués dans un autre pack. */
    private fun findPackRoots(base: File): List<File> {
        val roots = base.walkTopDown().maxDepth(6).filter { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
            .map { it.parentFile }.sortedBy { it.path.length }.toList()
        val result = mutableListOf<File>()
        for (r in roots) if (result.none { r.path.startsWith(it.path + File.separator) }) result += r
        return result
    }

    private fun analyzePack(root: File, base: File): PackInfo {
        val mf = File(root, "manifest.json")
        val r = JsonParser.parse(mf.readText())
        val manifest = (r as? JsonParseResult.Success)?.value as? JsonObject
        val types = manifest?.arr("modules")?.items?.mapNotNull { (it as? JsonObject)?.str("type") }?.toSet()
            ?: guessTypes(root)
        val rawName = manifest?.obj("header")?.str("name") ?: root.name
        val error = (r as? JsonParseResult.Failure)?.let { "manifest.json illisible (ligne ${it.error.line}) : ${it.error.message}" }
        return PackInfo(root, manifest, types, resolveName(rawName, root), error)
    }

    private fun guessTypes(root: File): Set<String> = buildSet {
        if (File(root, "entities").isDirectory || File(root, "items").isDirectory) add("data")
        if (File(root, "textures").isDirectory || File(root, "entity").isDirectory) add("resources")
    }

    /** Remplace les clés de traduction (`pack.name`) et retire les codes de couleur (§a). */
    private fun resolveName(raw: String, root: File): String {
        var n = raw
        if (n == "pack.name" || !n.contains(' ') && n.startsWith("pack.")) {
            val lang = File(root, "texts/en_US.lang")
            if (lang.isFile) LangFile(lang.readText())[n]?.let { n = it }
        }
        return n.replace(Regex("§."), "").trim().ifEmpty { root.name }
    }

    private fun explainNoPack(dir: File): String {
        val all = dir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        return when {
            all.any { it == "fabric.mod.json" || it == "mods.toml" || it.endsWith(".class") } ->
                "Cette archive contient un mod Minecraft Java Edition (Forge/Fabric). Il n'est pas compatible avec Bedrock Edition."
            all.any { it == "pack.mcmeta" } ->
                "Cette archive est un pack de ressources ou de données Java Edition (pack.mcmeta). Il n'est pas compatible avec Bedrock Edition."
            all.any { it == "level.dat" } ->
                "Cette archive est un monde Minecraft (level.dat), pas un addon. L'import de mondes n'est pas pris en charge."
            else -> "Aucun pack Bedrock trouvé : l'archive ne contient aucun fichier manifest.json."
        }
    }

    private fun buildMeta(fileName: String, bp: PackInfo?, rp: PackInfo?): ProjectMeta {
        fun ids(p: PackInfo?, dataType: String): PackIds? {
            val m = p?.manifest ?: return null
            val header = m.obj("header")?.str("uuid") ?: return null
            val mods = m.arr("modules")?.items?.mapNotNull { it as? JsonObject } ?: emptyList()
            val data = mods.firstOrNull { it.str("type") == dataType }?.str("uuid") ?: mods.firstOrNull()?.str("uuid") ?: return null
            val script = mods.firstOrNull { it.str("type") == "script" }?.str("uuid")
            return PackIds(header, data, script)
        }
        val main = bp ?: rp!!
        val header = main.manifest?.obj("header")
        val minEngine = header?.arr("min_engine_version")?.items?.mapNotNull { it.asInt }
        val name = main.name.replace(Regex("\\s*\\((Behavior|Resource) Pack\\)$", RegexOption.IGNORE_CASE), "")
        val now = System.currentTimeMillis()
        return ProjectMeta(
            id = store.newId(),
            name = name.ifBlank { File(fileName).nameWithoutExtension },
            description = header?.str("description") ?: "",
            author = "",
            namespace = guessNamespace(bp, rp) ?: Identifiers.toNamespace(name),
            targetVersion = BedrockVersions.closestFor(minEngine).id,
            packVersion = PackVersion.fromJson(header?.get("version")) ?: PackVersion.INITIAL,
            behaviorPack = ids(bp, "data") ?: PackIds.generate(withScript = true),
            resourcePack = ids(rp, "resources") ?: PackIds.generate(),
            createdAt = now, updatedAt = now, imported = true,
        )
    }

    /** Espace de noms le plus fréquent parmi les identifiants personnalisés. */
    private fun guessNamespace(bp: PackInfo?, rp: PackInfo?): String? {
        val counts = HashMap<String, Int>()
        fun scan(dir: File?, sub: String, vararg keys: String) {
            dir ?: return
            File(dir, sub).walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { f ->
                val v: JsonValue = JsonParser.parse(f.readText()).valueOrNull() ?: return@forEach
                val id = v.path(*keys)?.asString ?: return@forEach
                val ns = id.substringBefore(':', "")
                if (ns.isNotEmpty() && ns != "minecraft" && Identifiers.isValidNamespace(ns)) counts[ns] = (counts[ns] ?: 0) + 1
            }
        }
        scan(bp?.root, "entities", "minecraft:entity", "description", "identifier")
        scan(bp?.root, "items", "minecraft:item", "description", "identifier")
        scan(bp?.root, "blocks", "minecraft:block", "description", "identifier")
        scan(rp?.root, "entity", "minecraft:client_entity", "description", "identifier")
        return counts.maxByOrNull { it.value }?.key
    }

    private fun buildReport(
        fileName: String, packs: List<PackInfo>, bp: PackInfo?, rp: PackInfo?, projectDir: File,
        skipped: List<String>, meta: ProjectMeta,
    ): ImportReport {
        val recognized = linkedMapOf<String, Int>()
        var editable = 0; var binary = 0
        val unknown = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (p in listOfNotNull(bp, rp)) {
            p.root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(p.root).invariantSeparatorsPath
                val ext = f.extension.lowercase()
                CATEGORIES.entries.firstOrNull { rel.startsWith(it.key) }?.let { recognized[it.value] = (recognized[it.value] ?: 0) + 1 }
                when {
                    ext in TEXT_EXT -> {
                        editable++
                        if (ext == "json" && JsonParser.parse(f.readText()) is JsonParseResult.Failure) invalid += "${p.root.name}/$rel"
                    }
                    ext in BINARY_EXT -> binary++
                    else -> unknown += "${p.root.name}/$rel"
                }
            }
            if (File(p.root, "ui").isDirectory) notes += "Le dossier ui/ (JSON UI) est conservé mais n'est pas une API officielle : modifications à tes risques."
            if (File(p.root, "contents.json").isFile || File(p.root, "signatures.json").isFile) {
                notes += "Ce pack contient des fichiers de signature ou de chiffrement (contents.json / signatures.json) : il peut provenir du Marketplace. Certaines ressources peuvent être chiffrées et donc illisibles ; modifier un contenu protégé peut aussi être interdit par sa licence."
            }
            p.manifestError?.let { notes += "${p.root.name} : $it" }
        }
        if (binary > 0) notes += "$binary fichier(s) binaires (images, sons, structures…) sont conservés tels quels ; les .png peuvent être affichés, les autres ne sont pas encore modifiables."

        val deps = mutableListOf<String>()
        val localUuids = packs.mapNotNull { it.manifest?.obj("header")?.str("uuid")?.lowercase() }.toSet()
        for (p in packs) {
            p.manifest?.arr("dependencies")?.items?.mapNotNull { it as? JsonObject }?.forEach { d ->
                val mod = d.str("module_name")
                val uuid = d.str("uuid")
                deps += when {
                    mod != null -> "${p.name} → module de script $mod ${d["version"]?.let { (it.asString ?: it.toString()) } ?: ""}".trim()
                    uuid != null && uuid.lowercase() in localUuids -> "${p.name} → pack inclus dans l'archive ($uuid)"
                    uuid != null -> "${p.name} → pack externe NON inclus ($uuid) : il devra être installé séparément"
                    else -> "${p.name} → dépendance illisible"
                }
            }
        }

        val packList = packs.map { p ->
            ImportedPack(
                folder = p.root.name, name = p.name, kind = p.moduleTypes.joinToString().ifEmpty { "inconnu" },
                uuid = p.manifest?.obj("header")?.str("uuid"),
                version = PackVersion.fromJson(p.manifest?.obj("header")?.get("version"))?.toString(),
                usedAs = when (p) { bp -> "behavior_pack"; rp -> "resource_pack"; else -> "conservé (non éditable) dans .imported/extra_packs" },
            )
        }
        val validation = Validator(projectDir, meta.namespace, BedrockVersions.byId(meta.targetVersion)).run()
        if (bp == null) notes += "Aucun behavior pack trouvé : seul l'apparence (resource pack) a été importée."
        if (rp == null) notes += "Aucun resource pack trouvé : seuls les comportements (behavior pack) ont été importés."
        return ImportReport(
            sourceName = fileName, packs = packList, recognized = recognized,
            editableFiles = editable, binaryFiles = binary, unknownFiles = unknown, invalidJson = invalid,
            dependencies = deps, skippedEntries = skipped, notes = notes,
            validationErrors = validation.issues.count { it.severity == Severity.ERROR },
            validationWarnings = validation.issues.count { it.severity == Severity.WARNING },
        )
    }
}
