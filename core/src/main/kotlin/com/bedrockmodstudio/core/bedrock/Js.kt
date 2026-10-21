package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.json.JsonString
import com.bedrockmodstudio.core.json.JsonWriter

/** Aides pour écrire du JavaScript généré sûr (textes correctement échappés). */
object Js {
    /** Littéral de chaîne JavaScript (l'échappement JSON est un sous-ensemble valide de JS). */
    fun str(s: String): String = JsonWriter.write(JsonString(s)).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    fun num(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** En-tête commun des scripts générés. */
    fun header(title: String) = """
        |// $title
        |// Fichier généré par Bedrock Mod Studio. Tu peux le modifier ; une nouvelle génération du même
        |// contenu le remplacera après confirmation (l'ancienne version reste dans l'historique).
        |""".trimMargin()
}
