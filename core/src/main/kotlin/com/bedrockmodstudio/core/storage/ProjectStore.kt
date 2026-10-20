package com.bedrockmodstudio.core.storage

import com.bedrockmodstudio.core.bedrock.BedrockVersionProfile
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.bedrock.MobTemperament
import com.bedrockmodstudio.core.bedrock.ProjectTemplate
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.model.Identifiers
import com.bedrockmodstudio.core.model.PackIds
import com.bedrockmodstudio.core.model.PackVersion
import com.bedrockmodstudio.core.model.ProjectMeta
import java.io.File
import java.util.UUID

data class ProjectSummary(val meta: ProjectMeta, val dir: File)

/** Un dossier de projet dont `project.json` est illisible : signalé, jamais supprimé. */
data class BrokenProject(val dir: File, val reason: String)

/** Ensemble des projets, chacun dans `root/<id>/`. */
class ProjectStore(val root: File) {
    private val trashDir get() = File(root, ".trash")

    companion object {
        const val META_FILE = ProjectMeta.FILE_NAME
    }

    init { root.mkdirs() }

    fun dirOf(id: String): File {
        require(Regex("^[A-Za-z0-9_-]+$").matches(id)) { "Identifiant de projet invalide" }
        return File(root, id)
    }

    fun files(id: String) = ProjectFiles(dirOf(id))

    fun list(): List<ProjectSummary> = scan().first.sortedByDescending { it.meta.updatedAt }

    fun broken(): List<BrokenProject> = scan().second

    private fun scan(): Pair<List<ProjectSummary>, List<BrokenProject>> {
        val ok = mutableListOf<ProjectSummary>()
        val bad = mutableListOf<BrokenProject>()
        for (dir in root.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory || dir.name.startsWith(".")) continue
            val metaFile = File(dir, META_FILE)
            if (!metaFile.isFile) { bad += BrokenProject(dir, "Fichier project.json absent."); continue }
            val meta = JsonParser.parse(metaFile.readText()).valueOrNull()?.let { ProjectMeta.fromJson(it) }
            if (meta == null) bad += BrokenProject(dir, "Fichier project.json illisible ou incomplet.")
            else ok += ProjectSummary(meta, dir)
        }
        return ok to bad
    }

    fun load(id: String): ProjectMeta {
        val f = File(dirOf(id), META_FILE)
        if (!f.isFile) throw UserFacingException("Projet introuvable.")
        val v = JsonParser.parse(f.readText()).valueOrNull() ?: throw UserFacingException("Le fichier du projet est corrompu (project.json).")
        return ProjectMeta.fromJson(v) ?: throw UserFacingException("Le fichier du projet est incomplet (project.json).")
    }

    fun saveMeta(meta: ProjectMeta) {
        val dir = dirOf(meta.id)
        val updated = meta.copy(updatedAt = System.currentTimeMillis())
        val files = ProjectFiles(dir)
        if (File(dir, META_FILE).isFile) files.snapshot(META_FILE, "Paramètres du projet")
        AtomicFiles.writeText(File(dir, META_FILE), JsonWriter.write(updated.toJson()) + "\n")
    }

    fun touch(id: String) = saveMeta(load(id))

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** Crée un projet vide mais valide (deux manifestes liés, icônes, fichiers de langue). */
    fun create(
        name: String,
        description: String = "",
        author: String = "",
        namespace: String = Identifiers.toNamespace(name),
        profile: BedrockVersionProfile = BedrockVersions.DEFAULT,
    ): ProjectMeta {
        if (name.isBlank()) throw UserFacingException("Le nom du projet est vide.")
        if (!Identifiers.isValidNamespace(namespace)) {
            throw UserFacingException("L'espace de noms « $namespace » est invalide : minuscules, chiffres et _ uniquement (et pas « minecraft »).")
        }
        val now = System.currentTimeMillis()
        val meta = ProjectMeta(
            id = newId(), name = name.trim(), description = description.trim(), author = author.trim(),
            namespace = namespace, targetVersion = profile.id, packVersion = PackVersion.INITIAL,
            behaviorPack = PackIds.generate(withScript = true), resourcePack = PackIds.generate(),
            createdAt = now, updatedAt = now,
        )
        val dir = dirOf(meta.id)
        if (dir.exists()) throw UserFacingException("Collision d'identifiant de projet, réessaie.")
        dir.mkdirs()
        val files = ProjectFiles(dir)
        ProjectTemplate.INITIAL_FOLDERS.forEach { File(dir, it).mkdirs() }
        files.apply(ProjectTemplate.initialContent(meta, profile), overwrite = false, reason = "Création")
        saveMeta(meta)
        return meta
    }

    /** Projet d'exemple : une créature originale complète, prête à exporter. */
    fun createExample(): ProjectMeta {
        val meta = create(
            name = "Exemple — Sanglier des mousses",
            description = "Projet d'exemple : une créature neutre avec modèle, texture, animations, butin et apparition naturelle.",
            author = "Bedrock Mod Studio",
            namespace = "exemple_bms",
        )
        val spec = MobSpec(
            displayName = "Sanglier des mousses", shortId = "sanglier_mousses",
            temperament = MobTemperament.NEUTRAL, health = 24, speed = 0.27, attackDamage = 4,
            bodyColor = 0xFF6B4F3A.toInt(), accentColor = 0xFF4F7A3A.toInt(),
            lootItem = "minecraft:porkchop", lootMin = 1, lootMax = 3, spawnNaturally = true,
        )
        files(meta.id).apply(MobGenerator.generate(meta.namespace, spec, BedrockVersions.byId(meta.targetVersion)!!), overwrite = false, reason = "Exemple")
        return meta
    }

    /** Déplace le projet dans la corbeille de l'application (récupérable). */
    fun moveToTrash(id: String) {
        val dir = dirOf(id)
        if (!dir.exists()) throw UserFacingException("Projet introuvable.")
        trashDir.mkdirs()
        val target = File(trashDir, "${System.currentTimeMillis()}_$id")
        if (!dir.renameTo(target)) throw UserFacingException("Impossible de déplacer le projet dans la corbeille.")
    }

    data class TrashedProject(val trashName: String, val name: String, val deletedAt: Long)

    fun listTrash(): List<TrashedProject> = (trashDir.listFiles() ?: emptyArray()).filter { it.isDirectory }.map { d ->
        val name = runCatching {
            ProjectMeta.fromJson(JsonParser.parseOrThrow(File(d, META_FILE).readText()))?.name
        }.getOrNull() ?: d.name
        TrashedProject(d.name, name, d.name.substringBefore('_').toLongOrNull() ?: d.lastModified())
    }.sortedByDescending { it.deletedAt }

    fun restoreFromTrash(trashName: String): String {
        require(Regex("^[A-Za-z0-9_-]+$").matches(trashName))
        val src = File(trashDir, trashName)
        val id = trashName.substringAfter('_')
        val target = if (dirOf(id).exists()) dirOf(newId()) else dirOf(id)
        if (!src.renameTo(target)) throw UserFacingException("Restauration impossible.")
        if (target.name != id) {
            // L'identifiant change : on le réécrit dans project.json.
            val meta = ProjectMeta.fromJson(JsonParser.parseOrThrow(File(target, META_FILE).readText()))
            if (meta != null) saveMeta(meta.copy(id = target.name))
        }
        return target.name
    }

    fun emptyTrash() { trashDir.deleteRecursively() }
}
