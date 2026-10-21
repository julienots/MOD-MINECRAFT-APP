package com.bedrockmodstudio.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.bedrockmodstudio.ui.theme.BmsColors

/**
 * Coloration syntaxique JSON (avec commentaires) et texte générique (.lang, .mcfunction).
 * Ne modifie pas le texte : seules les couleurs changent, les positions restent identiques.
 */
class SyntaxHighlighter(private val language: Language) : VisualTransformation {
    enum class Language { JSON, LANG, MCFUNCTION, JS, PLAIN }

    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > MAX_HIGHLIGHT || language == Language.PLAIN) return TransformedText(text, OffsetMapping.Identity)
        val styled = when (language) {
            Language.JSON -> json(text.text)
            Language.LANG -> lineBased(text.text, "##") { line -> line.indexOf('=') }
            Language.MCFUNCTION -> lineBased(text.text, "#") { line -> line.indexOf(' ') }
            Language.JS -> js(text.text)
            Language.PLAIN -> text
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    companion object {
        const val MAX_HIGHLIGHT = 120_000

        fun forPath(path: String) = SyntaxHighlighter(
            when (path.substringAfterLast('.', "").lowercase()) {
                "json", "material" -> Language.JSON
                "lang" -> Language.LANG
                "mcfunction" -> Language.MCFUNCTION
                "js", "ts" -> Language.JS
                else -> Language.PLAIN
            }
        )
    }

    private fun json(s: String): AnnotatedString = buildAnnotatedString {
        append(s)
        var i = 0
        fun style(start: Int, end: Int, color: androidx.compose.ui.graphics.Color) =
            addStyle(SpanStyle(color = color), start, end.coerceAtMost(s.length))
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                    val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    style(i, end, BmsColors.JsonComment); i = end
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    style(i, end, BmsColors.JsonComment); i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < s.length && s[j] != '"' && s[j] != '\n') { if (s[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(s.length)
                    var k = end
                    while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
                    style(i, end, if (k < s.length && s[k] == ':') BmsColors.JsonKey else BmsColors.JsonString)
                    i = end
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < s.length && (s[j].isDigit() || s[j] in ".eE+-")) j++
                    style(i, j, BmsColors.JsonNumber); i = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < s.length && s[j].isLetter()) j++
                    if (s.substring(i, j) in setOf("true", "false", "null")) style(i, j, BmsColors.JsonKeyword)
                    i = j
                }
                c in "{}[],:" -> { style(i, i + 1, BmsColors.JsonPunct); i++ }
                else -> i++
            }
        }
    }

    private val jsKeywords = setOf(
        "import", "from", "export", "const", "let", "var", "function", "return", "if", "else", "for", "while", "of", "in",
        "new", "class", "extends", "async", "await", "true", "false", "null", "undefined", "this", "break", "continue",
        "switch", "case", "default", "try", "catch", "finally", "throw", "typeof", "instanceof",
    )

    private fun js(s: String): AnnotatedString = buildAnnotatedString {
        append(s)
        var i = 0
        fun style(a: Int, b: Int, c: androidx.compose.ui.graphics.Color) = addStyle(SpanStyle(color = c), a, b.coerceAtMost(s.length))
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> { val e = s.indexOf('\n', i).let { if (it < 0) s.length else it }; style(i, e, BmsColors.JsonComment); i = e }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> { val e = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }; style(i, e, BmsColors.JsonComment); i = e }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < s.length && s[j] != c && (c == '`' || s[j] != '\n')) { if (s[j] == '\\') j++; j++ }
                    style(i, j + 1, BmsColors.JsonString); i = j + 1
                }
                c.isDigit() -> { var j = i; while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '.')) j++; style(i, j, BmsColors.JsonNumber); i = j }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i; while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '$')) j++
                    if (s.substring(i, j) in jsKeywords) style(i, j, BmsColors.JsonKeyword)
                    else if (j < s.length && s[j] == '(') style(i, j, BmsColors.JsonKey)
                    i = j
                }
                else -> i++
            }
        }
    }

    /** Une ligne par instruction : commentaire, puis partie gauche colorée (clé ou commande). */
    private fun lineBased(s: String, comment: String, split: (String) -> Int): AnnotatedString = buildAnnotatedString {
        append(s)
        var start = 0
        for (line in s.split('\n')) {
            val trimmed = line.trimStart()
            val offset = start + (line.length - trimmed.length)
            if (trimmed.startsWith(comment)) {
                addStyle(SpanStyle(color = BmsColors.JsonComment), offset, offset + trimmed.length)
            } else if (trimmed.isNotEmpty()) {
                val cut = split(trimmed).let { if (it < 0) trimmed.length else it }
                addStyle(SpanStyle(color = BmsColors.JsonKey), offset, offset + cut)
                if (cut < trimmed.length) addStyle(SpanStyle(color = BmsColors.JsonString), offset + cut + 1, offset + trimmed.length)
            }
            start += line.length + 1
        }
    }
}
