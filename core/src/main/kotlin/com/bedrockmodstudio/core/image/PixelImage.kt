package com.bedrockmodstudio.core.image

/**
 * Image modifiable pixel par pixel (ARGB non prémultiplié), base de l'éditeur de textures.
 * Toutes les opérations ignorent les coordonnées hors de l'image.
 */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width in 1..MAX_SIZE && height in 1..MAX_SIZE) { "Dimensions de texture invalides ($width × $height, maximum $MAX_SIZE)." }
        require(pixels.size == width * height) { "Nombre de pixels incorrect" }
    }

    companion object {
        const val MAX_SIZE = 1024
        const val TRANSPARENT = 0
    }

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        if (inside(x, y)) pixels[y * width + x] = argb
    }

    fun copy() = PixelImage(width, height, pixels.copyOf())

    /** Pose un point, et son reflet horizontal si [mirrorX] est vrai (textures symétriques). */
    fun plot(x: Int, y: Int, argb: Int, mirrorX: Boolean = false) {
        this[x, y] = argb
        if (mirrorX) this[width - 1 - x, y] = argb
    }

    /** Trait continu entre deux points (algorithme de Bresenham), pour que le doigt ne laisse pas de trous. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int, mirrorX: Boolean = false) {
        var x = x0; var y = y0
        val dx = Math.abs(x1 - x0); val dy = -Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(x, y, argb, mirrorX)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Remplissage (pot de peinture) : zone contiguë de la même couleur, voisins haut/bas/gauche/droite. */
    fun floodFill(x: Int, y: Int, argb: Int): Int {
        if (!inside(x, y)) return 0
        val target = this[x, y]
        if (target == argb) return 0
        var count = 0
        val stack = ArrayDeque<Int>()
        stack.addLast(y * width + x)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (pixels[i] != target) continue
            pixels[i] = argb; count++
            val px = i % width; val py = i / width
            if (px > 0) stack.addLast(i - 1)
            if (px < width - 1) stack.addLast(i + 1)
            if (py > 0) stack.addLast(i - width)
            if (py < height - 1) stack.addLast(i + width)
        }
        return count
    }

    /** Redimensionne au plus proche voisin (garde les pixels nets). */
    fun resized(newWidth: Int, newHeight: Int): PixelImage {
        val out = PixelImage(newWidth, newHeight)
        for (y in 0 until newHeight) for (x in 0 until newWidth) {
            out.pixels[y * newWidth + x] = this[x * width / newWidth, y * height / newHeight]
        }
        return out
    }

    /** Agrandit la toile sans déformer (les pixels existants restent en haut à gauche). */
    fun canvasResized(newWidth: Int, newHeight: Int): PixelImage {
        val out = PixelImage(newWidth, newHeight)
        for (y in 0 until minOf(height, newHeight)) for (x in 0 until minOf(width, newWidth)) out.pixels[y * newWidth + x] = this[x, y]
        return out
    }

    fun toPng(): ByteArray = PngEncoder.encode(width, height, pixels)

    /** Couleurs distinctes utilisées (palette du document), les plus fréquentes d'abord. */
    fun usedColors(max: Int = 24): List<Int> =
        pixels.filter { (it ushr 24) != 0 }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(max).map { it.key }
}
