package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.jsonOf
import com.bedrockmodstudio.core.json.obj
import com.bedrockmodstudio.core.model.PackIds
import com.bedrockmodstudio.core.storage.ProjectFiles
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException

/**
 * Active l'API de scripts dans le behavior pack et tient à jour le point d'entrée.
 *
 * Le point d'entrée contient un bloc d'imports géré automatiquement entre deux marqueurs ;
 * tout ce que l'utilisateur écrit en dehors de ce bloc est conservé.
 */
object ScriptSetup {
    const val BEGIN = "// <bms:imports> — lignes gérées par Bedrock Mod Studio"
    const val END = "// </bms:imports>"
    private const val MANIFEST = "behavior_pack/manifest.json"

    data class Result(val entry: String, val changedFiles: List<String>)

    fun enable(store: ProjectStore, projectId: String, profile: BedrockVersionProfile, needsServerUi: Boolean): Result {
        val files = store.files(projectId)
        var meta = store.load(projectId)
        if (!files.exists(MANIFEST)) throw UserFacingException("Le projet n'a pas de behavior pack : impossible d'ajouter des scripts.")
        val manifest = (JsonParser.parse(files.readText(MANIFEST)) as? JsonParseResult.Success)?.value as? JsonObject
            ?: throw UserFacingException("Le manifeste du behavior pack contient une erreur JSON : corrige-le avant d'ajouter des scripts.")
        val changed = mutableListOf<String>()

        val modules = manifest.arr("modules") ?: JsonArray().also { manifest["modules"] = it }
        var scriptModule = modules.items.mapNotNull { it as? JsonObject }.firstOrNull { it.str("type") == "script" }
        if (scriptModule == null) {
            if (meta.behaviorPack.scriptModuleUuid == null) {
                meta = meta.copy(behaviorPack = meta.behaviorPack.copy(scriptModuleUuid = PackIds.generate(true).scriptModuleUuid))
                store.saveMeta(meta)
            }
            scriptModule = obj(
                "description" to "${meta.name} — scripts", "type" to "script", "language" to "javascript",
                "uuid" to meta.behaviorPack.scriptModuleUuid,
                "version" to (manifest.obj("header")?.get("version") ?: jsonOf(meta.packVersion.toList())),
                "entry" to Manifests.SCRIPT_ENTRY,
            )
            modules.add(scriptModule)
        }
        val entry = scriptModule.str("entry") ?: Manifests.SCRIPT_ENTRY.also { scriptModule["entry"] = JsonString(it) }

        val deps = manifest.arr("dependencies") ?: JsonArray().also { manifest["dependencies"] = it }
        fun ensureDep(module: String, version: String) {
            val existing = deps.items.mapNotNull { it as? JsonObject }.firstOrNull { it.str("module_name") == module }
            if (existing == null) deps.add(obj("module_name" to module, "version" to version))
        }
        ensureDep("@minecraft/server", profile.scriptServerVersion)
        if (needsServerUi) ensureDep("@minecraft/server-ui", profile.scriptServerUiVersion)

        val text = JsonWriter.write(manifest) + "\n"
        if (text != files.readText(MANIFEST)) { files.writeText(MANIFEST, text, "Activation des scripts"); changed += MANIFEST }

        val entryPath = "behavior_pack/$entry"
        if (!files.exists(entryPath)) {
            files.writeText(entryPath, defaultEntry(), "Activation des scripts"); changed += entryPath
        }
        return Result(entryPath, changed)
    }

    fun defaultEntry() = """
        |// Point d'entrée des scripts de l'addon.
        |// Le bloc ci-dessous est mis à jour automatiquement par Bedrock Mod Studio.
        |// Écris ton propre code en dessous : il ne sera jamais modifié par l'application.
        |$BEGIN
        |$END
        |""".trimMargin()

    /** Ajoute `import "./module.js";` dans le bloc géré du point d'entrée [entryPath] (sans doublon). */
    fun addImports(files: ProjectFiles, entryPath: String, modules: List<String>): Boolean {
        val entryDir = entryPath.substringBeforeLast('/', "")
        val text = files.readText(entryPath)
        val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
        var begin = lines.indexOfFirst { it.trim().startsWith("// <bms:imports>") }
        var end = lines.indexOfFirst { it.trim() == END }
        if (begin < 0 || end < begin) {
            // Pas encore de bloc géré : on l'ajoute en tête, le reste du fichier est conservé tel quel.
            lines.add(0, BEGIN); lines.add(1, END)
            begin = 0; end = 1
        }
        val existing = lines.subList(begin + 1, end).map { it.trim() }.toSet()
        val toAdd = modules.map { m -> "import \"./${relative(entryDir, "behavior_pack/scripts/$m")}\";" }.filter { it !in existing }
        if (toAdd.isEmpty()) return false
        lines.addAll(end, toAdd)
        files.writeText(entryPath, lines.joinToString("\n"), "Ajout de scripts")
        return true
    }

    /** Chemin relatif simple de [dir] vers [target] (les deux relatifs à la racine du projet). */
    fun relative(dir: String, target: String): String {
        val from = if (dir.isEmpty()) emptyList() else dir.split('/')
        val to = target.split('/')
        var common = 0
        while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
        val ups = from.size - common
        return (List(ups) { ".." } + to.drop(common)).joinToString("/")
    }
}

/**
 * Installation d'un contenu généré : vérifie que la version ciblée le permet, écrit les fichiers,
 * puis active les scripts et ajoute les imports si le contenu en contient.
 */
object ContentInstaller {
    fun install(store: ProjectStore, projectId: String, content: GeneratedContent, overwrite: Boolean, reason: String): ProjectFiles.ApplyResult {
        val meta = store.load(projectId)
        val profile = BedrockVersions.byId(meta.targetVersion) ?: BedrockVersions.DEFAULT
        val missing = content.requiredFeatures.filter { !profile.supports(it) }
        if (missing.isNotEmpty()) {
            throw UserFacingException("La version ciblée (${profile.label}) ne permet pas : ${missing.joinToString { it.labelFr }}.")
        }
        val files = store.files(projectId)
        val result = files.apply(content, overwrite, reason)
        if (content.scriptModules.isEmpty()) return result
        val setup = ScriptSetup.enable(store, projectId, profile, content.needsServerUi)
        val importsChanged = ScriptSetup.addImports(files, setup.entry, content.scriptModules)
        val extra = setup.changedFiles + if (importsChanged) listOf(setup.entry) else emptyList()
        return result.copy(written = (result.written + extra).distinct())
    }
}
