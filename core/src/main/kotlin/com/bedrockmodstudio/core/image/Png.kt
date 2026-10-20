package com.bedrockmodstudio.core.image

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Encodeur PNG RGBA 8 bits, sans dépendance à Android ni à AWT,
 * pour générer les textures et icônes des projets dans le module testable sur la JVM.
 */
object PngEncoder {
    /** [argb] : un entier 0xAARRGGBB par pixel, ligne par ligne. */
    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0) { "Dimensions invalides" }
        require(argb.size == width * height) { "Nombre de pixels incorrect" }
        val raw = ByteArray(height * (1 + width * 4))
        var i = 0
        for (y in 0 until height) {
            raw[i++] = 0 // filtre « None »
            for (x in 0 until width) {
                val p = argb[y * width + x]
                raw[i++] = (p shr 16).toByte()
                raw[i++] = (p shr 8).toByte()
                raw[i++] = p.toByte()
                raw[i++] = (p ushr 24).toByte()
            }
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw); deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) compressed.write(buf, 0, deflater.deflate(buf))
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(width); writeInt(height); write(8); write(6); write(0); write(0); write(0) }
        }.toByteArray()
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        d.write(typeBytes); d.write(data)
        val crc = CRC32(); crc.update(typeBytes); crc.update(data)
        d.writeInt(crc.value.toInt())
    }
}

/** Lecture des dimensions d'un PNG sans le décoder (validation des textures). */
object PngInfo {
    data class Size(val width: Int, val height: Int)

    fun read(bytes: ByteArray): Size? {
        if (bytes.size < 24) return null
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) if (bytes[i] != sig[i]) return null
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        return Size(int(16), int(20))
    }
}

/** Petite toile en mémoire pour dessiner des textures procédurales. */
class PixelCanvas(val width: Int, val height: Int, fill: Int = 0) {
    val pixels = IntArray(width * height) { fill }

    operator fun set(x: Int, y: Int, argb: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = argb
    }
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        for (yy in y until y + h) for (xx in x until x + w) this[xx, yy] = argb
    }

    /** Remplit un rectangle en faisant légèrement varier la teinte, pour un rendu « pixel art ». */
    fun noiseRect(x: Int, y: Int, w: Int, h: Int, argb: Int, seed: Int, amount: Int = 18) {
        var s = seed
        for (yy in y until y + h) for (xx in x until x + w) {
            s = s * 1103515245 + 12345
            val d = ((s ushr 16) % (2 * amount + 1)) - amount
            this[xx, yy] = shade(argb, d)
        }
    }

    fun toPng(): ByteArray = PngEncoder.encode(width, height, pixels)

    companion object {
        fun shade(argb: Int, delta: Int): Int {
            fun c(v: Int) = (v + delta).coerceIn(0, 255)
            val a = argb ushr 24; val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
            return (a shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
    }
}
