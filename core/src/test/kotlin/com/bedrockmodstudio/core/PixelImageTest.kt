package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.image.PixelImage
import com.bedrockmodstudio.core.image.PngInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PixelImageTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun `trait continu sans trou, symetrie horizontale`() {
        val img = PixelImage(16, 16)
        img.line(0, 0, 15, 5, red)
        // Un trait de Bresenham couvre chaque colonne exactement une fois.
        for (x in 0 until 16) assertEquals(1, (0 until 16).count { img[x, it] == red }, "colonne $x")
        val m = PixelImage(8, 8)
        m.plot(1, 2, red, mirrorX = true)
        assertEquals(red, m[6, 2])
    }

    @Test
    fun `remplissage limite a la zone contigue`() {
        val img = PixelImage(5, 5)
        for (y in 0 until 5) img[2, y] = red // mur vertical
        assertEquals(10, img.floodFill(0, 0, blue))
        assertEquals(blue, img[1, 4]); assertEquals(0, img[3, 0]); assertEquals(red, img[2, 2])
        assertEquals(0, img.floodFill(0, 0, blue)) // même couleur : rien à faire
        assertEquals(0, img.floodFill(-1, 0, blue))
    }

    @Test
    fun `remplissage d'une grande image sans debordement de pile`() {
        val img = PixelImage(1024, 1024)
        assertEquals(1024 * 1024, img.floodFill(0, 0, red))
    }

    @Test
    fun `redimensionnements et export PNG`() {
        val img = PixelImage(2, 2, intArrayOf(red, blue, blue, red))
        val big = img.resized(4, 4)
        assertEquals(red, big[1, 1]); assertEquals(blue, big[2, 0]); assertEquals(red, big[3, 3])
        val canvas = img.canvasResized(3, 1)
        assertEquals(listOf(red, blue, 0), canvas.pixels.toList())
        assertEquals(PngInfo.Size(4, 4), PngInfo.read(big.toPng()))
        assertEquals(listOf(red, blue), img.usedColors())
        assertFailsWith<IllegalArgumentException> { PixelImage(0, 4) }
        assertFailsWith<IllegalArgumentException> { PixelImage(2048, 4) }
    }
}
