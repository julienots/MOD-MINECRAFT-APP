package com.bedrockmodstudio.core.json

/** Erreur de syntaxe localisée (ligne et colonne commencent à 1). */
data class JsonSyntaxError(val message: String, val line: Int, val column: Int, val offset: Int)

/** Avertissement non bloquant, par exemple une clé en double. */
data class JsonWarning(val message: String, val line: Int, val column: Int)

sealed class JsonParseResult {
    abstract val warnings: List<JsonWarning>

    data class Success(val value: JsonValue, override val warnings: List<JsonWarning>) : JsonParseResult()
    data class Failure(val error: JsonSyntaxError, override val warnings: List<JsonWarning>) : JsonParseResult()

    fun valueOrNull(): JsonValue? = (this as? Success)?.value
}

/**
 * Parseur JSON au comportement de Minecraft Bedrock :
 * - accepte les commentaires `//` et `/* */` (présents dans les échantillons officiels de Mojang) ;
 * - accepte un BOM UTF-8 en tête de fichier ;
 * - refuse tout le reste de ce qui n'est pas du JSON standard (virgules finales, guillemets simples...),
 *   avec un message en français et la position exacte.
 */
class JsonParser private constructor(private val text: String) {
    private var pos = 0
    private var line = 1
    private var col = 1
    private val warnings = mutableListOf<JsonWarning>()

    private class Fail(val error: JsonSyntaxError) : Exception(error.message, null, false, false)

    companion object {
        /** Profondeur maximale, pour ne pas épuiser la pile sur un fichier malveillant. */
        const val MAX_DEPTH = 512

        fun parse(text: String): JsonParseResult {
            val p = JsonParser(text)
            return try {
                if (p.peekChar() == '﻿') p.advance()
                p.skipWhitespace()
                if (p.atEnd()) p.fail("Le fichier est vide : un objet JSON était attendu.")
                val value = p.readValue(0)
                p.skipWhitespace()
                if (!p.atEnd()) p.fail("Contenu inattendu après la fin du JSON : « ${p.peekChar()} ».")
                JsonParseResult.Success(value, p.warnings)
            } catch (e: Fail) {
                JsonParseResult.Failure(e.error, p.warnings)
            }
        }

        /** Raccourci pour du contenu de confiance (généré par l'application). */
        fun parseOrThrow(text: String): JsonValue = when (val r = parse(text)) {
            is JsonParseResult.Success -> r.value
            is JsonParseResult.Failure -> throw IllegalArgumentException(
                "JSON invalide (ligne ${r.error.line}, colonne ${r.error.column}) : ${r.error.message}"
            )
        }
    }

    private fun atEnd() = pos >= text.length
    private fun peekChar(): Char = if (atEnd()) '\u0000' else text[pos]

    private fun advance(): Char {
        val c = text[pos++]
        if (c == '\n') { line++; col = 1 } else col++
        return c
    }

    private fun fail(message: String): Nothing = throw Fail(JsonSyntaxError(message, line, col, pos))

    private fun skipWhitespace() {
        while (!atEnd()) {
            val c = peekChar()
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> advance()
                c == '/' && pos + 1 < text.length && text[pos + 1] == '/' -> {
                    while (!atEnd() && peekChar() != '\n') advance()
                }
                c == '/' && pos + 1 < text.length && text[pos + 1] == '*' -> {
                    val startLine = line; val startCol = col
                    advance(); advance()
                    while (true) {
                        if (atEnd()) throw Fail(JsonSyntaxError(
                            "Commentaire /* ouvert ligne $startLine jamais refermé par */.", startLine, startCol, pos))
                        if (peekChar() == '*' && pos + 1 < text.length && text[pos + 1] == '/') {
                            advance(); advance(); break
                        }
                        advance()
                    }
                }
                else -> return
            }
        }
    }

    private fun readValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("Imbrication trop profonde (plus de $MAX_DEPTH niveaux).")
        skipWhitespace()
        val startLine = line; val startCol = col
        val v = when (val c = peekChar()) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonString(readString())
            't', 'f', 'n' -> readLiteral()
            '\'' -> fail("Les guillemets simples ne sont pas autorisés en JSON : utilise des guillemets doubles \".")
            '\u0000' -> fail("Fin de fichier inattendue : une valeur était attendue.")
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Caractère inattendu « $c » : une valeur JSON était attendue.")
        }
        v.line = startLine; v.column = startCol
        return v
    }

    private fun readObject(depth: Int): JsonObject {
        val result = JsonObject()
        advance() // {
        skipWhitespace()
        if (peekChar() == '}') { advance(); return result }
        while (true) {
            skipWhitespace()
            if (peekChar() == '}') fail("Virgule en trop avant « } ».")
            if (peekChar() != '"') fail("Nom de propriété attendu entre guillemets doubles.")
            val keyLine = line; val keyCol = col
            val key = readString()
            skipWhitespace()
            if (peekChar() != ':') fail("« : » attendu après la clé \"$key\".")
            advance()
            val value = readValue(depth + 1)
            if (result.containsKey(key)) {
                warnings += JsonWarning("La clé \"$key\" apparaît plusieurs fois : seule la dernière sera prise en compte.", keyLine, keyCol)
            }
            result[key] = value
            skipWhitespace()
            when (peekChar()) {
                ',' -> advance()
                '}' -> { advance(); return result }
                '\u0000' -> fail("Fin de fichier inattendue : « } » manquant.")
                else -> fail("« , » ou « } » attendu après la valeur de \"$key\".")
            }
        }
    }

    private fun readArray(depth: Int): JsonArray {
        val result = JsonArray()
        advance() // [
        skipWhitespace()
        if (peekChar() == ']') { advance(); return result }
        while (true) {
            skipWhitespace()
            if (peekChar() == ']') fail("Virgule en trop avant « ] ».")
            result.add(readValue(depth + 1))
            skipWhitespace()
            when (peekChar()) {
                ',' -> advance()
                ']' -> { advance(); return result }
                '\u0000' -> fail("Fin de fichier inattendue : « ] » manquant.")
                else -> fail("« , » ou « ] » attendu dans la liste.")
            }
        }
    }

    private fun readString(): String {
        advance() // "
        val sb = StringBuilder()
        while (true) {
            if (atEnd()) fail("Texte non terminé : guillemet fermant \" manquant.")
            val c = advance()
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (atEnd()) fail("Échappement incomplet en fin de fichier.")
                    when (val e = advance()) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail("Échappement \\u incomplet.")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("Échappement \\u$hex invalide.")
                            repeat(4) { advance() }
                            sb.append(code.toChar())
                        }
                        else -> fail("Échappement « \\$e » inconnu.")
                    }
                }
                c == '\n' -> fail("Retour à la ligne dans un texte : ferme le guillemet ou utilise \\n.")
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): JsonNumber {
        val start = pos
        if (peekChar() == '-') advance()
        if (peekChar() == '0') advance()
        else if (peekChar() in '1'..'9') while (peekChar() in '0'..'9') advance()
        else fail("Nombre invalide.")
        if (peekChar() == '.') {
            advance()
            if (peekChar() !in '0'..'9') fail("Chiffre attendu après le point décimal.")
            while (peekChar() in '0'..'9') advance()
        }
        if (peekChar() == 'e' || peekChar() == 'E') {
            advance()
            if (peekChar() == '+' || peekChar() == '-') advance()
            if (peekChar() !in '0'..'9') fail("Exposant invalide.")
            while (peekChar() in '0'..'9') advance()
        }
        return JsonNumber(text.substring(start, pos))
    }

    private fun readLiteral(): JsonValue {
        for ((word, value) in listOf("true" to JsonBool(true), "false" to JsonBool(false), "null" to JsonNull)) {
            if (text.startsWith(word, pos)) {
                repeat(word.length) { advance() }
                return if (value === JsonNull) JsonNull else JsonBool((value as JsonBool).value)
            }
        }
        val start = pos
        while (!atEnd() && peekChar().isLetterOrDigit()) advance()
        fail("Mot inconnu « ${text.substring(start, pos)} » : true, false ou null attendu.")
    }
}
