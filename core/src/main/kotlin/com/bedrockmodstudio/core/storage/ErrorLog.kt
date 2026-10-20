package com.bedrockmodstudio.core.storage

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal des erreurs de l'application, consultable dans les paramètres. Taille plafonnée. */
class ErrorLog(private val file: File, private val maxBytes: Long = 512 * 1024) {

    @Synchronized
    fun log(context: String, error: Throwable? = null, message: String? = null) {
        file.parentFile?.mkdirs()
        if (file.length() > maxBytes) {
            val keep = file.readText().takeLast((maxBytes / 2).toInt())
            file.writeText("… (début du journal tronqué)\n$keep")
        }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val sb = StringBuilder("[$date] $context")
        if (message != null) sb.append(" : ").append(message)
        sb.append('\n')
        if (error != null) {
            val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString().lines().take(40).joinToString("\n")).append('\n')
        }
        file.appendText(sb.toString())
    }

    fun read(): String = if (file.isFile) file.readText() else ""

    /** Vidage sur demande explicite de l'utilisateur. */
    fun clear() { file.delete() }
}
