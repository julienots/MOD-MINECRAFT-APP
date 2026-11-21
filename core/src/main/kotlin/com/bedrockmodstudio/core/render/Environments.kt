package com.bedrockmodstudio.core.render

/** Couleur RVB linéaire simple (0..1). */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun array() = floatArrayOf(r, g, b)

    companion object {
        fun hex(argb: Int) = Rgb(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)
    }
}

/**
 * Ambiances de la vue 3D : ciel, soleil (ou lune), lumière ambiante, brouillard et sol.
 * Inspirées des lieux de Minecraft, sans reprendre ses images.
 */
enum class Environment3D(
    val labelFr: String,
    val skyTop: Rgb,
    val skyHorizon: Rgb,
    val skyBottom: Rgb,
    val sunColor: Rgb,
    val sunElevation: Double,
    val ambientSky: Rgb,
    val ambientGround: Rgb,
    val fog: Rgb,
    /** Distance de brouillard, en multiples du rayon de la scène. */
    val fogStart: Float,
    val fogEnd: Float,
    val ground: Rgb,
    val grid: Rgb,
    /** 0 = pas d'étoiles, 1 = ciel étoilé. */
    val stars: Float,
    /** Taille apparente du disque solaire (0 = invisible). */
    val sunDisc: Float,
) {
    STUDIO("Studio",
        Rgb.hex(0x2B3240), Rgb.hex(0x1D222C), Rgb.hex(0x15181D), Rgb(1.0f, 0.97f, 0.92f), 50.0,
        Rgb(0.42f, 0.45f, 0.52f), Rgb(0.20f, 0.19f, 0.18f), Rgb.hex(0x15181D), 6f, 14f,
        Rgb.hex(0x20252E), Rgb.hex(0x3A4252), 0f, 0f),
    DAY("Jour",
        Rgb.hex(0x5E9BE8), Rgb.hex(0xBFDBF7), Rgb.hex(0x8FB5D8), Rgb(1.0f, 0.96f, 0.86f), 55.0,
        Rgb(0.48f, 0.56f, 0.68f), Rgb(0.30f, 0.27f, 0.22f), Rgb.hex(0xBFDBF7), 5f, 14f,
        Rgb.hex(0x5C8A3A), Rgb.hex(0x4A7330), 0f, 1f),
    SUNSET("Coucher de soleil",
        Rgb.hex(0x35407A), Rgb.hex(0xF39A5B), Rgb.hex(0x6C4A6E), Rgb(1.0f, 0.62f, 0.36f), 12.0,
        Rgb(0.42f, 0.36f, 0.50f), Rgb(0.30f, 0.20f, 0.16f), Rgb.hex(0xE58D61), 4f, 12f,
        Rgb.hex(0x4F6B34), Rgb.hex(0x41582A), 0.15f, 1.6f),
    NIGHT("Nuit",
        Rgb.hex(0x070B1C), Rgb.hex(0x1B2645), Rgb.hex(0x0A0F1E), Rgb(0.55f, 0.65f, 0.95f), 40.0,
        Rgb(0.13f, 0.16f, 0.28f), Rgb(0.06f, 0.06f, 0.08f), Rgb.hex(0x10172C), 4f, 11f,
        Rgb.hex(0x1F3020), Rgb.hex(0x2A3F2A), 1f, 0.7f),
    NETHER("Nether",
        Rgb.hex(0x3A0C0A), Rgb.hex(0x7A2414), Rgb.hex(0x2A0806), Rgb(1.0f, 0.55f, 0.30f), 65.0,
        Rgb(0.45f, 0.20f, 0.14f), Rgb(0.35f, 0.10f, 0.05f), Rgb.hex(0x5E1A10), 2.5f, 8f,
        Rgb.hex(0x5A2420), Rgb.hex(0x7A3328), 0f, 0f),
    END("End",
        Rgb.hex(0x140E22), Rgb.hex(0x2C1F45), Rgb.hex(0x0D0916), Rgb(0.85f, 0.80f, 1.0f), 70.0,
        Rgb(0.30f, 0.24f, 0.42f), Rgb(0.16f, 0.15f, 0.12f), Rgb.hex(0x1E1530), 4f, 12f,
        Rgb.hex(0xCFCB92), Rgb.hex(0xB5B07A), 0.6f, 0f),
}

/** Manières d'afficher le modèle. */
enum class RenderMode(val labelFr: String, val code: Int) {
    TEXTURED("Texturé", 0),
    CLAY("Argile", 1),
    WIREFRAME("Fil de fer", 2),
    NORMALS("Normales", 3),
    XRAY("Rayons X", 4),
}
