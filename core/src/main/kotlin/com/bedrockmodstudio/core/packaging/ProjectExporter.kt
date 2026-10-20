package com.bedrockmodstudio.core.packaging

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.Manifests
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.model.PackVersion
import com.bedrockmodstudio.core.storage.AtomicFiles
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.validation.ValidationReport
import com.bedrockmodstudio.core.validation.Validator
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class ExportKind(val extension: String, val labelFr: String) {
    MCADDON("mcaddon", "Addon complet (.mcaddon)"),
    MCPACK_BEHAVIOR("mcpack", "Behavior pack seul (.mcpack)"),
    MCPACK_RESOURCE("mcpack", "Resource pack seul (.mcpack)"),
    BACKUP_ZIP("zip", "Sauvegarde du projet (.zip)"),
}

sealed class ExportOutcome {
    /** Export refusé : la validation a trouvé des erreurs bloquantes. Aucun fichier produit. */
    data class Blocked(val report: ValidationReport) : ExportOutcome()
    data class Success(val file: File, val report: ValidationReport?, val version: PackVersion?, val steps: List<String>) : ExportOutcome()
    data class Failed(val message: String) : ExportOutcome()
}

/**
 * Chaîne d'export. L'extension finale n'est attribuée qu'à une archive :
 * 1) produite à partir d'un projet sans erreur bloquante, et
 * 2) relue avec succès, manifestes compris, après écriture.
 */
class ProjectExporter(private val store: ProjectStore) {

    companion object {
        const val BP = "behavior_pack"
        const val RP = "resource_pack"
        const val BACKUP_MARKER = "bms_backup.json"

        /**
         * Nom de fichier en ASCII pur (« Épée — Feu » → « Epee_Feu ») : certains gestionnaires de fichiers,
         * messageries et systèmes de fichiers gèrent mal les autres caractères.
         */
        fun safeFileName(name: String): String {
            val ascii = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val s = ascii.replace(Regex("[^A-Za-z0-9._-]+"), "_").replace(Regex("_+"), "_").trim('_', '.', '-')
            return s.take(60).ifEmpty { "addon" }
        }
    }

    fun validate(projectId: String): ValidationReport {
        val meta = store.load(projectId)
        return Validator(store.dirOf(projectId), meta.namespace, BedrockVersions.byId(meta.targetVersion)).run()
    }

    fun export(projectId: String, kind: ExportKind, outDir: File, bumpVersion: Boolean = true): ExportOutcome {
        val steps = mutableListOf<String>()
        return try {
            val dir = store.dirOf(projectId)
            var meta = store.load(projectId)
            outDir.mkdirs()

            if (kind == ExportKind.BACKUP_ZIP) {
                val out = AtomicFiles.uniqueFile(outDir, "${safeFileName(meta.name)}_sauvegarde", "zip")
                val tmp = File(outDir, ".${out.name}.part")
                ZipOutputStream(FileOutputStream(tmp)).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry(BACKUP_MARKER))
                    zos.write(JsonWriter.write(meta.toJson()).toByteArray()); zos.closeEntry()
                    // La sauvegarde inclut les originaux importés, mais pas l'historique ni la corbeille.
                    SafeZip.addDirectory(zos, dir, "project/", includeHidden = false)
                    val imported = File(dir, ".imported")
                    if (imported.isDirectory) SafeZip.addDirectory(zos, imported, "project/.imported/", includeHidden = true)
                }
                verifyZipReadable(tmp)
                if (!tmp.renameTo(out)) return ExportOutcome.Failed("Impossible de finaliser le fichier de sauvegarde.")
                return ExportOutcome.Success(out, null, null, listOf("Sauvegarde complète du projet créée."))
            }

            // 1-6. Vérification du projet (JSON, manifestes, UUID, dépendances, identifiants, références).
            val report = validate(projectId)
            steps += "Projet vérifié : ${report.checkedFiles} fichiers JSON analysés, ${report.errors.size} erreur(s), ${report.warnings.size} avertissement(s)."
            if (report.hasErrors) return ExportOutcome.Blocked(report)

            val includeBp = kind != ExportKind.MCPACK_RESOURCE && File(dir, BP).isDirectory
            val includeRp = kind != ExportKind.MCPACK_BEHAVIOR && File(dir, RP).isDirectory
            if (!includeBp && !includeRp) return ExportOutcome.Failed("Le pack demandé n'existe pas dans ce projet.")

            // 7. Version : incrémentée pour que Minecraft accepte la mise à jour d'un pack déjà installé.
            var version: PackVersion? = null
            if (bumpVersion) {
                version = bumpVersions(dir, meta.packVersion)
                meta = meta.copy(packVersion = version)
                store.saveMeta(meta)
                steps += "Version des packs passée à $version."
            }

            // 8-9. Construction dans un fichier temporaire, puis relecture complète.
            val base = safeFileName(meta.name)
            val suffix = when (kind) { ExportKind.MCPACK_BEHAVIOR -> "_BP"; ExportKind.MCPACK_RESOURCE -> "_RP"; else -> "" }
            val versionTag = (version ?: meta.packVersion).toString()
            val out = AtomicFiles.uniqueFile(outDir, "${base}${suffix}_v$versionTag", kind.extension)
            val tmp = File(outDir, ".${out.name}.part")
            val expectedManifests = mutableListOf<String>()
            ZipOutputStream(FileOutputStream(tmp)).use { zos ->
                if (kind == ExportKind.MCADDON) {
                    if (includeBp) { SafeZip.addDirectory(zos, File(dir, BP), "${base}_BP/"); expectedManifests += "${base}_BP/manifest.json" }
                    if (includeRp) { SafeZip.addDirectory(zos, File(dir, RP), "${base}_RP/"); expectedManifests += "${base}_RP/manifest.json" }
                } else {
                    // Un .mcpack contient le pack à sa racine (manifest.json à la racine de l'archive).
                    SafeZip.addDirectory(zos, File(dir, if (includeBp) BP else RP), "")
                    expectedManifests += "manifest.json"
                }
            }
            val problem = verifyArchive(tmp, expectedManifests)
            if (problem != null) {
                tmp.delete()
                return ExportOutcome.Failed("L'archive produite est invalide, elle n'a pas été enregistrée : $problem")
            }
            if (!tmp.renameTo(out)) { tmp.delete(); return ExportOutcome.Failed("Impossible de finaliser le fichier exporté.") }
            steps += "Archive construite et relue : ${expectedManifests.size} pack(s), manifestes valides."
            ExportOutcome.Success(out, report, version, steps)
        } catch (e: Exception) {
            ExportOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Met à jour les deux manifestes (avec historique) et renvoie la nouvelle version. */
    private fun bumpVersions(dir: File, current: PackVersion): PackVersion {
        val files = store.files(dir.name)
        val manifests = listOf("$BP/manifest.json", "$RP/manifest.json").filter { File(dir, it).isFile }
            .associateWith { JsonParser.parseOrThrow(files.readText(it)) as JsonObject }
        val versions = manifests.values.mapNotNull { PackVersion.fromJson(it.obj("header")?.get("version")) } + current
        val next = versions.maxWith(compareBy({ it.major }, { it.minor }, { it.patch })).bumpPatch()
        val headerUuids = manifests.values.mapNotNull { it.obj("header")?.str("uuid") }.toSet()
        for ((path, m) in manifests) {
            files.writeText(path, JsonWriter.write(Manifests.applyVersion(m, next, headerUuids)) + "\n", reason = "Export (version $next)")
        }
        return next
    }

    private fun verifyZipReadable(file: File) {
        ZipFile(file).use { z -> z.entries().asSequence().forEach { e -> z.getInputStream(e).use { s -> val b = ByteArray(8192); while (s.read(b) >= 0) Unit } } }
    }

    /** Relit l'archive : chaque entrée doit être lisible et chaque manifeste présent et valide. */
    fun verifyArchive(file: File, expectedManifests: List<String>): String? = try {
        ZipFile(file).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            for (n in names) if (SafeZip.safeEntryName(n) == null) return "entrée au chemin invalide « $n »"
            for (m in expectedManifests) {
                val entry = z.getEntry(m) ?: return "manifeste « $m » absent"
                val text = z.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val json = JsonParser.parse(text).valueOrNull() as? JsonObject ?: return "manifeste « $m » illisible"
                if (json.obj("header")?.str("uuid") == null) return "manifeste « $m » sans UUID"
            }
            z.entries().asSequence().forEach { e -> z.getInputStream(e).use { s -> val b = ByteArray(8192); while (s.read(b) >= 0) Unit } }
            null
        }
    } catch (e: Exception) {
        "relecture impossible (${e.message})"
    }
}
