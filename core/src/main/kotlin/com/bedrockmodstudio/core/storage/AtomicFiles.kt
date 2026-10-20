package com.bedrockmodstudio.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AtomicFiles {
    /**
     * Écrit d'abord dans un fichier temporaire synchronisé sur le disque, puis le renomme.
     * Une coupure (batterie, plantage) laisse soit l'ancien fichier, soit le nouveau, jamais un fichier à moitié écrit.
     */
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Certains systèmes de fichiers refusent de renommer par-dessus un fichier existant.
            val backup = File(target.parentFile, ".${target.name}.old")
            if (target.exists() && !target.renameTo(backup)) { tmp.delete(); throw IOException("Impossible de remplacer ${target.name}") }
            if (!tmp.renameTo(target)) { backup.renameTo(target); tmp.delete(); throw IOException("Impossible d'écrire ${target.name}") }
            backup.delete()
        }
    }

    fun writeText(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))

    /** Nom libre dans [dir] : « nom.ext », puis « nom (2).ext », « nom (3).ext »… */
    fun uniqueFile(dir: File, baseName: String, extension: String): File {
        val ext = if (extension.isEmpty()) "" else ".$extension"
        var f = File(dir, "$baseName$ext")
        var n = 2
        while (f.exists()) f = File(dir, "$baseName ($n)$ext").also { n++ }
        return f
    }
}

/** Erreur destinée à être montrée telle quelle à l'utilisateur (message en français). */
class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)
