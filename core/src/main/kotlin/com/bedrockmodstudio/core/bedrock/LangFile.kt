package com.bedrockmodstudio.core.bedrock

/**
 * Fichier de traduction Bedrock (`texts/xx_XX.lang`) : lignes `clé=valeur`, commentaires `##`.
 * Les modifications conservent toutes les lignes existantes, dans le même ordre.
 */
class LangFile(text: String = "") {
    private val lines = text.replace("\r\n", "\n").split('\n').toMutableList().also {
        if (it.size == 1 && it[0].isEmpty()) it.clear()
    }

    val entries: Map<String, String>
        get() = lines.mapNotNull { parse(it) }.toMap()

    operator fun get(key: String): String? = entries[key]

    /** Ajoute ou remplace une entrée. */
    operator fun set(key: String, value: String) {
        require('=' !in key && '\n' !in key) { "Clé de traduction invalide : $key" }
        val clean = value.replace('\n', ' ')
        val idx = lines.indexOfFirst { parse(it)?.first == key }
        if (idx >= 0) lines[idx] = "$key=$clean" else lines += "$key=$clean"
    }

    /** Ajoute seulement si la clé n'existe pas (ne jamais écraser une traduction existante en silence). */
    fun putIfAbsent(key: String, value: String): Boolean {
        if (entries.containsKey(key)) return false
        this[key] = value
        return true
    }

    override fun toString(): String = lines.joinToString("\n", postfix = "\n")

    companion object {
        fun parse(line: String): Pair<String, String>? {
            val t = line.trimStart()
            if (t.isEmpty() || t.startsWith("##")) return null
            val eq = t.indexOf('=')
            if (eq <= 0) return null
            // Un commentaire en fin de ligne est introduit par une tabulation suivie de ##.
            val value = t.substring(eq + 1).substringBefore("\t##").trimEnd()
            return t.substring(0, eq) to value
        }
    }
}
