package com.bedrockmodstudio.core.json

/**
 * Écriture lisible : indentation de 2 espaces, et listes courtes de nombres sur une ligne
 * (`[1, 26, 50]`, `[0, 0, 1]`) comme dans les fichiers officiels.
 */
object JsonWriter {
    fun write(value: JsonValue, indent: String = "  "): String {
        val sb = StringBuilder()
        writeValue(sb, value, 0, indent)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: JsonValue, level: Int, indent: String) {
        when (v) {
            is JsonObject -> {
                if (v.size == 0) { sb.append("{}"); return }
                sb.append("{\n")
                v.entries.forEachIndexed { i, (k, child) ->
                    repeat(level + 1) { sb.append(indent) }
                    writeString(sb, k)
                    sb.append(": ")
                    writeValue(sb, child, level + 1, indent)
                    if (i < v.size - 1) sb.append(',')
                    sb.append('\n')
                }
                repeat(level) { sb.append(indent) }
                sb.append('}')
            }
            is JsonArray -> {
                if (v.size == 0) { sb.append("[]"); return }
                val inline = v.size <= 8 && v.items.all { it is JsonNumber || it is JsonBool } ||
                    v.size <= 4 && v.items.all { it is JsonString && it.value.length <= 24 }
                if (inline) {
                    sb.append('[')
                    v.items.forEachIndexed { i, child ->
                        if (i > 0) sb.append(", ")
                        writeValue(sb, child, level, indent)
                    }
                    sb.append(']')
                    return
                }
                sb.append("[\n")
                v.items.forEachIndexed { i, child ->
                    repeat(level + 1) { sb.append(indent) }
                    writeValue(sb, child, level + 1, indent)
                    if (i < v.size - 1) sb.append(',')
                    sb.append('\n')
                }
                repeat(level) { sb.append(indent) }
                sb.append(']')
            }
            is JsonString -> writeString(sb, v.value)
            is JsonNumber -> sb.append(v.raw)
            is JsonBool -> sb.append(v.value)
            JsonNull -> sb.append("null")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
