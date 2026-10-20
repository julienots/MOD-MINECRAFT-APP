package com.bedrockmodstudio.core.packaging

import com.bedrockmodstudio.core.storage.UserFacingException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Plafonds de sécurité pour les archives importées (protection contre les « bombes zip »). */
data class ZipLimits(
    val maxEntries: Int = 20_000,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
)

object SafeZip {

    data class ExtractResult(val files: List<String>, val skipped: List<String>)

    /**
     * Normalise et vérifie un nom d'entrée. Renvoie null si l'entrée doit être refusée
     * (chemin absolu, lecteur Windows, remontée `..`) : c'est la protection « zip slip ».
     */
    fun safeEntryName(raw: String): String? {
        val name = raw.replace('\\', '/')
        if (name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        if (parts.any { it.any { c -> c.code < 32 } }) return null
        return parts.joinToString("/") + if (name.endsWith("/")) "/" else ""
    }

    private fun isJunk(name: String) =
        name.startsWith("__MACOSX/") || name.substringAfterLast('/') == ".DS_Store" || name.substringAfterLast('/') == "Thumbs.db"

    /** Extrait [input] dans [dest] en respectant [limits]. En cas d'échec, [dest] est vidé. */
    fun extract(input: InputStream, dest: File, limits: ZipLimits = ZipLimits()): ExtractResult {
        dest.mkdirs()
        val canonicalDest = dest.canonicalFile
        val files = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var total = 0L
        var count = 0
        try {
            ZipInputStream(input).use { zis ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val entry: ZipEntry = zis.nextEntry ?: break
                    if (++count > limits.maxEntries) throw UserFacingException("Archive refusée : plus de ${limits.maxEntries} fichiers.")
                    val name = safeEntryName(entry.name)
                    if (name == null) { skipped += "${entry.name} (chemin dangereux refusé)"; continue }
                    if (isJunk(name)) { skipped += "$name (fichier système ignoré)"; continue }
                    val out = File(canonicalDest, name).canonicalFile
                    if (!out.path.startsWith(canonicalDest.path + File.separator)) { skipped += "${entry.name} (chemin dangereux refusé)"; continue }
                    if (entry.isDirectory || name.endsWith("/")) { out.mkdirs(); continue }
                    if (out.exists()) { skipped += "$name (en double dans l'archive, première version conservée)"; continue }
                    out.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            entryBytes += n; total += n
                            if (entryBytes > limits.maxEntryBytes) throw UserFacingException("Archive refusée : « $name » dépasse ${limits.maxEntryBytes / 1024 / 1024} Mo une fois décompressé.")
                            if (total > limits.maxTotalBytes) throw UserFacingException("Archive refusée : plus de ${limits.maxTotalBytes / 1024 / 1024} Mo une fois décompressée.")
                            fos.write(buf, 0, n)
                        }
                    }
                    files += name
                }
            }
        } catch (e: UserFacingException) {
            dest.deleteRecursively(); throw e
        } catch (e: ZipException) {
            dest.deleteRecursively(); throw UserFacingException("Ce fichier n'est pas une archive valide ou il est endommagé (${e.message}).", e)
        } catch (e: java.io.IOException) {
            dest.deleteRecursively(); throw UserFacingException("Lecture de l'archive impossible (${e.message}).", e)
        }
        if (count == 0) { dest.deleteRecursively(); throw UserFacingException("L'archive est vide ou n'est pas au format ZIP.") }
        return ExtractResult(files, skipped)
    }

    /**
     * Ajoute le contenu de [dir] dans [zos] sous [prefix], dans un ordre stable.
     * Les dossiers internes de l'éditeur (noms commençant par un point) sont exclus.
     */
    fun addDirectory(zos: ZipOutputStream, dir: File, prefix: String, includeHidden: Boolean = false) {
        val base = dir.canonicalFile
        base.walkTopDown()
            .onEnter { it == base || includeHidden || !it.name.startsWith(".") }
            .filter { it.isFile && (includeHidden || !it.name.startsWith(".")) }
            .sortedBy { it.relativeTo(base).invariantSeparatorsPath }
            .forEach { f ->
                val entry = ZipEntry(prefix + f.relativeTo(base).invariantSeparatorsPath)
                entry.time = f.lastModified()
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
    }
}
