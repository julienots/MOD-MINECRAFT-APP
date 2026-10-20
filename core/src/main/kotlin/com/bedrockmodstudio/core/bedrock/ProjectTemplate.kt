package com.bedrockmodstudio.core.bedrock

import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.BP
import com.bedrockmodstudio.core.bedrock.GeneratedContent.Companion.RP
import com.bedrockmodstudio.core.image.PixelCanvas
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.jsonOf
import com.bedrockmodstudio.core.model.ProjectMeta

/** Contenu de départ d'un nouveau projet : deux packs Bedrock valides et vides. */
object ProjectTemplate {

    /** Dossiers créés d'emblée pour que l'arborescence soit lisible dans l'explorateur. */
    val INITIAL_FOLDERS = listOf(
        "$BP/entities", "$BP/items", "$BP/blocks", "$BP/recipes", "$BP/loot_tables", "$BP/spawn_rules",
        "$BP/functions", "$BP/structures",
        "$RP/entity", "$RP/models/entity", "$RP/animations", "$RP/animation_controllers", "$RP/render_controllers",
        "$RP/textures/entity", "$RP/textures/items", "$RP/textures/blocks", "$RP/sounds", "$RP/particles", "$RP/texts",
    )

    fun initialContent(meta: ProjectMeta, profile: BedrockVersionProfile): GeneratedContent {
        val c = GeneratedContent()
        c.text("$BP/manifest.json", JsonWriter.write(Manifests.behaviorPack(meta, profile, withScripts = false)) + "\n")
        c.text("$RP/manifest.json", JsonWriter.write(Manifests.resourcePack(meta, profile)) + "\n")
        val icon = packIcon(meta.name)
        c.bytes("$BP/pack_icon.png", icon)
        c.bytes("$RP/pack_icon.png", icon)
        c.text("$RP/texts/languages.json", JsonWriter.write(jsonOf(listOf("en_US", "fr_FR"))) + "\n")
        val header = "## Traductions de ${meta.name}\n"
        c.text("$RP/texts/en_US.lang", header)
        c.text("$RP/texts/fr_FR.lang", header)
        return c
    }

    /** Icône 64×64 originale : un bloc stylisé dont la couleur dépend du nom du projet. */
    fun packIcon(seedText: String): ByteArray {
        val hue = (seedText.hashCode() and 0x7FFFFFFF) % 360
        val base = hsvToArgb(hue.toFloat(), 0.55f, 0.75f)
        val canvas = PixelCanvas(64, 64, 0xFF1B1F27.toInt())
        canvas.noiseRect(8, 8, 48, 48, base, seedText.hashCode())
        canvas.fillRect(8, 8, 48, 4, PixelCanvas.shade(base, 40))
        canvas.fillRect(8, 52, 48, 4, PixelCanvas.shade(base, -50))
        canvas.fillRect(8, 8, 4, 48, PixelCanvas.shade(base, 25))
        canvas.fillRect(52, 8, 4, 48, PixelCanvas.shade(base, -35))
        // Losange central clair.
        for (y in 0 until 16) for (x in 0 until 16) {
            if (Math.abs(x - 7.5) + Math.abs(y - 7.5) <= 7) canvas[24 + x, 24 + y] = PixelCanvas.shade(base, 70)
        }
        return canvas.toPng()
    }

    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - Math.abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60).toInt() % 6) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun b8(f: Float) = ((f + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (b8(r) shl 16) or (b8(g) shl 8) or b8(b)
    }
}
