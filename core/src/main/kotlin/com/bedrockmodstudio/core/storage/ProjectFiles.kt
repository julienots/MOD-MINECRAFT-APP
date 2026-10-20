package com.bedrockmodstudio.core.storage

import com.bedrockmodstudio.core.bedrock.GeneratedContent
import com.bedrockmodstudio.core.bedrock.LangFile
import java.io.File

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

data class SearchHit(val path: String, val line: Int, val snippet: String)

data class HistoryEntry(val id: String, val timestamp: Long, val size: Long, val reason: String)

data class TrashEntry(val id: String, val originalPath: String, val deletedAt: Long, val isDirectory: Boolean)

/**
 * Accès aux fichiers d'un projet. Toutes les opérations :
 * - refusent les chemins qui sortent du projet ;
 * - n'écrasent jamais un fichier existant sans le sauvegarder d'abord dans l'historique ;
 * - ne suppriment jamais définitivement : la suppression déplace dans la corbeille du projet.
 */
class ProjectFiles(val root: File) {
    private val canonicalRoot = root.canonicalFile
    private val historyDir get() = File(root, HISTORY_DIR)
    private val trashDir get() = File(root, TRASH_DIR)
    private val draftsDir get() = File(root, DRAFTS_DIR)

    companion object {
        const val HISTORY_DIR = ".history"
        const val TRASH_DIR = ".trash"
        const val DRAFTS_DIR = ".drafts"
        const val IMPORTED_DIR = ".imported"
        const val MAX_HISTORY_PER_FILE = 30
        val TEXT_EXTENSIONS = setOf("json", "lang", "mcfunction", "js", "ts", "txt", "md", "material", "molang")
        private val FORBIDDEN_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        fun isTextFile(path: String) = path.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

        fun validateName(name: String): String? = when {
            name.isBlank() -> "Le nom ne peut pas être vide."
            name == "." || name == ".." -> "Ce nom est réservé."
            name.startsWith(".") -> "Le nom ne peut pas commencer par un point (réservé aux dossiers internes)."
            FORBIDDEN_NAME_CHARS.containsMatchIn(name) -> "Le nom contient un caractère interdit (\\ / : * ? \" < > |)."
            name.length > 120 -> "Le nom est trop long (120 caractères maximum)."
            else -> null
        }
    }

    /** Résout un chemin relatif en refusant `..`, les chemins absolus et les dossiers internes. */
    fun resolve(relPath: String, allowInternal: Boolean = false): File {
        val clean = relPath.replace('\\', '/').trim('/')
        val f = if (clean.isEmpty()) canonicalRoot else File(canonicalRoot, clean).canonicalFile
        if (f != canonicalRoot && !f.path.startsWith(canonicalRoot.path + File.separator)) {
            throw UserFacingException("Chemin refusé : « $relPath » sort du projet.")
        }
        if (!allowInternal && clean.split('/').any { it.startsWith(".") }) {
            throw UserFacingException("Chemin réservé à l'application : « $relPath ».")
        }
        return f
    }

    fun relative(file: File): String = file.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath

    fun exists(relPath: String) = resolve(relPath, allowInternal = true).exists()

    fun list(relDir: String = ""): List<FileEntry> {
        val dir = resolve(relDir)
        if (!dir.isDirectory) throw UserFacingException("« $relDir » n'est pas un dossier.")
        return (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && !(relDir.isEmpty() && it.name == ProjectStore.META_FILE) }
            .map { FileEntry(relative(it), it.name, it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** Tous les fichiers du projet (hors dossiers internes), chemins relatifs. */
    fun walk(relDir: String = ""): List<String> {
        val dir = resolve(relDir)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .onEnter { it == dir || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { relative(it) }
            .sorted()
            .toList()
    }

    fun readBytes(relPath: String): ByteArray {
        val f = resolve(relPath, allowInternal = true)
        if (!f.isFile) throw UserFacingException("Fichier introuvable : $relPath")
        return f.readBytes()
    }

    fun readText(relPath: String): String = readBytes(relPath).toString(Charsets.UTF_8).removePrefix("\uFEFF")

    /** Écrit un fichier ; l'ancienne version (s'il y en a une) part dans l'historique. */
    fun write(relPath: String, bytes: ByteArray, reason: String = "Modification") {
        val f = resolve(relPath)
        if (f.isDirectory) throw UserFacingException("« $relPath » est un dossier.")
        if (f.isFile) {
            if (f.readBytes().contentEquals(bytes)) return
            snapshot(relPath, reason)
        }
        AtomicFiles.write(f, bytes)
    }

    fun writeText(relPath: String, text: String, reason: String = "Modification") =
        write(relPath, text.toByteArray(Charsets.UTF_8), reason)

    /** Crée un fichier ; refuse s'il existe déjà. */
    fun createFile(relPath: String, bytes: ByteArray = ByteArray(0)) {
        val f = resolve(relPath)
        validateName(f.name)?.let { throw UserFacingException(it) }
        if (f.exists()) throw UserFacingException("« ${f.name} » existe déjà.")
        AtomicFiles.write(f, bytes)
    }

    /** Ajoute un fichier externe dans [relDir] ; si le nom est pris, utilise « nom (2).ext ». Renvoie le chemin créé. */
    fun importFile(relDir: String, fileName: String, bytes: ByteArray): String {
        val dir = resolve(relDir)
        if (!dir.isDirectory) throw UserFacingException("Dossier de destination introuvable.")
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\')
        validateName(clean)?.let { throw UserFacingException(it) }
        val ext = clean.substringAfterLast('.', "")
        val base = if (ext.isEmpty()) clean else clean.substringBeforeLast('.')
        val target = AtomicFiles.uniqueFile(dir, base, ext)
        AtomicFiles.write(target, bytes)
        return relative(target)
    }

    fun createFolder(relPath: String) {
        val f = resolve(relPath)
        validateName(f.name)?.let { throw UserFacingException(it) }
        if (f.exists()) throw UserFacingException("« ${f.name} » existe déjà.")
        if (!f.mkdirs()) throw UserFacingException("Impossible de créer le dossier « ${f.name} ».")
    }

    fun rename(relPath: String, newName: String): String {
        val f = resolve(relPath)
        if (f == canonicalRoot) throw UserFacingException("Impossible de renommer la racine du projet.")
        validateName(newName)?.let { throw UserFacingException(it) }
        val target = File(f.parentFile, newName)
        if (target.exists()) throw UserFacingException("« $newName » existe déjà dans ce dossier.")
        if (!f.renameTo(target)) throw UserFacingException("Impossible de renommer « ${f.name} ».")
        // L'historique suit le fichier renommé.
        val oldHist = File(historyDir, relPath)
        if (oldHist.exists()) oldHist.renameTo(File(historyDir, relative(target)).also { it.parentFile?.mkdirs() })
        return relative(target)
    }

    /** Duplique un fichier ou un dossier à côté de l'original (« nom_copie.json »). */
    fun duplicate(relPath: String): String {
        val f = resolve(relPath)
        if (!f.exists() || f == canonicalRoot) throw UserFacingException("Impossible de dupliquer « $relPath ».")
        val ext = if (f.isFile) f.extension else ""
        val base = if (ext.isEmpty()) f.name else f.nameWithoutExtension
        var target = File(f.parentFile, if (ext.isEmpty()) "${base}_copie" else "${base}_copie.$ext")
        var n = 2
        while (target.exists()) {
            target = File(f.parentFile, if (ext.isEmpty()) "${base}_copie$n" else "${base}_copie$n.$ext"); n++
        }
        if (f.isDirectory) f.copyRecursively(target) else f.copyTo(target)
        return relative(target)
    }

    /** « Supprime » en déplaçant dans la corbeille du projet, d'où l'on peut restaurer. */
    fun moveToTrash(relPath: String): TrashEntry {
        val f = resolve(relPath)
        if (f == canonicalRoot || !f.exists()) throw UserFacingException("Impossible de supprimer « $relPath ».")
        val id = "${System.currentTimeMillis()}_${(1000..9999).random()}"
        val slot = File(trashDir, id).apply { mkdirs() }
        File(slot, "path.txt").writeText(relPath)
        if (!f.renameTo(File(slot, "content"))) throw UserFacingException("Impossible de déplacer « ${f.name} » dans la corbeille.")
        return TrashEntry(id, relPath, System.currentTimeMillis(), File(slot, "content").isDirectory)
    }

    fun listTrash(): List<TrashEntry> = (trashDir.listFiles() ?: emptyArray()).mapNotNull { slot ->
        val path = File(slot, "path.txt").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
        val ts = slot.name.substringBefore('_').toLongOrNull() ?: slot.lastModified()
        TrashEntry(slot.name, path, ts, File(slot, "content").isDirectory)
    }.sortedByDescending { it.deletedAt }

    /** Restaure depuis la corbeille ; si l'emplacement est occupé, restaure sous un nouveau nom. */
    fun restoreFromTrash(id: String): String {
        val slot = File(trashDir, id)
        val content = File(slot, "content")
        val path = File(slot, "path.txt").takeIf { it.isFile }?.readText()
            ?: throw UserFacingException("Élément introuvable dans la corbeille.")
        var target = resolve(path)
        if (target.exists()) {
            val ext = if (content.isFile) target.extension else ""
            target = AtomicFiles.uniqueFile(target.parentFile, if (ext.isEmpty()) target.name + "_restaure" else target.nameWithoutExtension + "_restaure", ext)
        }
        target.parentFile?.mkdirs()
        if (!content.renameTo(target)) throw UserFacingException("Restauration impossible.")
        slot.deleteRecursively()
        return relative(target)
    }

    /** Vidage définitif : uniquement sur demande explicite (confirmée dans l'interface). */
    fun emptyTrash() { trashDir.deleteRecursively() }

    // ---- Historique des versions ----

    fun snapshot(relPath: String, reason: String) {
        val f = resolve(relPath)
        if (!f.isFile) return
        val dir = File(historyDir, relPath).apply { mkdirs() }
        val id = "${System.currentTimeMillis()}"
        var target = File(dir, "$id.snap")
        var n = 1
        while (target.exists()) target = File(dir, "${id}_${n++}.snap")
        f.copyTo(target)
        File(dir, target.nameWithoutExtension + ".txt").writeText(reason)
        val snaps = dir.listFiles { x -> x.extension == "snap" }!!.sortedBy { it.name }
        snaps.dropLast(MAX_HISTORY_PER_FILE).forEach { old ->
            old.delete(); File(dir, old.nameWithoutExtension + ".txt").delete()
        }
    }

    fun history(relPath: String): List<HistoryEntry> {
        val dir = File(historyDir, relPath)
        return (dir.listFiles { x -> x.extension == "snap" } ?: emptyArray()).map { s ->
            HistoryEntry(
                s.nameWithoutExtension,
                s.nameWithoutExtension.substringBefore('_').toLongOrNull() ?: s.lastModified(),
                s.length(),
                File(dir, s.nameWithoutExtension + ".txt").takeIf { it.isFile }?.readText() ?: "",
            )
        }.sortedByDescending { it.id }
    }

    fun readHistory(relPath: String, id: String): ByteArray {
        if (!Regex("^[0-9_]+$").matches(id)) throw UserFacingException("Version introuvable.")
        resolve(relPath)
        val f = File(File(historyDir, relPath), "$id.snap")
        if (!f.isFile) throw UserFacingException("Version introuvable.")
        return f.readBytes()
    }

    /** Restaure une version ; la version actuelle est elle-même sauvegardée avant. */
    fun restoreHistory(relPath: String, id: String) {
        write(relPath, readHistory(relPath, id), reason = "Avant restauration")
    }

    // ---- Brouillons (récupération après fermeture inattendue) ----

    fun saveDraft(relPath: String, text: String) {
        resolve(relPath)
        AtomicFiles.writeText(File(draftsDir, relPath), text)
    }

    /** Brouillon plus récent que le fichier, s'il existe. */
    fun pendingDraft(relPath: String): String? {
        val d = File(draftsDir, relPath)
        if (!d.isFile) return null
        val f = resolve(relPath)
        if (f.isFile && f.lastModified() > d.lastModified()) return null
        val text = d.readText()
        return if (f.isFile && text == readText(relPath)) null else text
    }

    fun clearDraft(relPath: String) { File(draftsDir, relPath).delete() }

    fun allDrafts(): List<String> {
        val d = draftsDir
        if (!d.exists()) return emptyList()
        return d.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(d).invariantSeparatorsPath }.toList()
    }

    // ---- Recherche ----

    fun search(query: String, maxHits: Int = 500): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (path in walk()) {
            if (path.contains(query, ignoreCase = true)) hits += SearchHit(path, 0, path)
            if (!isTextFile(path)) continue
            val f = resolve(path)
            if (f.length() > 2_000_000) continue
            f.readLines().forEachIndexed { i, line ->
                if (line.contains(query, ignoreCase = true)) {
                    hits += SearchHit(path, i + 1, line.trim().take(160))
                    if (hits.size >= maxHits) return hits
                }
            }
        }
        return hits
    }

    // ---- Application d'un contenu généré ----

    data class ApplyResult(val written: List<String>, val skippedExisting: List<String>, val langKeysAdded: Int)

    /**
     * Écrit les fichiers générés. Un fichier déjà présent n'est remplacé que si [overwrite] est vrai,
     * et dans ce cas l'ancienne version reste dans l'historique.
     */
    fun apply(content: GeneratedContent, overwrite: Boolean, reason: String): ApplyResult {
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for ((path, bytes) in content.files) {
            val exists = resolve(path).exists()
            if (exists && !overwrite) { skipped += path; continue }
            write(path, bytes, reason)
            written += path
        }
        var added = 0
        if (content.lang.isNotEmpty()) {
            for (langPath in GeneratedContent.LANG_FILES) {
                val lang = LangFile(if (exists(langPath)) readText(langPath) else "")
                content.lang.forEach { (k, v) -> if (overwrite) { lang[k] = v; added++ } else if (lang.putIfAbsent(k, v)) added++ }
                writeText(langPath, lang.toString(), reason)
            }
        }
        return ApplyResult(written, skipped, added)
    }

    /** Fichiers qui seraient remplacés par [content]. */
    fun conflicts(content: GeneratedContent): List<String> = content.files.keys.filter { resolve(it).exists() }
}
