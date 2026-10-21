package com.bedrockmodstudio.ui.texture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bedrockmodstudio.core.image.PixelImage
import com.bedrockmodstudio.core.storage.ErrorLog
import com.bedrockmodstudio.core.storage.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TextureTool(val labelFr: String) { PENCIL("Crayon"), ERASER("Gomme"), FILL("Remplir"), PICKER("Pipette") }

class TextureEditorViewModel(
    private val files: ProjectFiles,
    val path: String,
    private val errorLog: ErrorLog,
) : ViewModel() {
    var image by mutableStateOf<PixelImage?>(null); private set
    /** Incrémenté à chaque modification, pour redessiner la toile. */
    var revision by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    var dirty by mutableStateOf(false); private set
    var tool by mutableStateOf(TextureTool.PENCIL)
    var color by mutableIntStateOf(0xFF4E8A3E.toInt())
    var mirror by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)

    private val undo = ArrayDeque<PixelImage>()
    private val redo = ArrayDeque<PixelImage>()
    private var strokeStart: PixelImage? = null
    private var last: Pair<Int, Int>? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    init {
        viewModelScope.launch {
            try {
                image = withContext(Dispatchers.IO) {
                    val bytes = files.readBytes(path)
                    val opts = BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("Cette image n'a pas pu être lue (format non pris en charge).")
                    if (bmp.width > PixelImage.MAX_SIZE || bmp.height > PixelImage.MAX_SIZE) error("Image trop grande pour l'éditeur (${bmp.width} × ${bmp.height}).")
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    PixelImage(bmp.width, bmp.height, px)
                }
            } catch (e: Exception) {
                errorLog.log("Éditeur de textures : $path", e)
                error = e.message ?: "Erreur de lecture"
            }
        }
    }

    private fun touched() { revision++; dirty = true }

    private fun pushUndo(snapshot: PixelImage) {
        undo.addLast(snapshot); if (undo.size > 60) undo.removeFirst()
        redo.clear()
    }

    /** Début d'un geste à un doigt sur le pixel (x, y). */
    fun strokeStart(x: Int, y: Int) {
        val img = image ?: return
        strokeStart = img.copy()
        last = x to y
        when (tool) {
            TextureTool.PENCIL -> img.plot(x, y, color, mirror)
            TextureTool.ERASER -> img.plot(x, y, PixelImage.TRANSPARENT, mirror)
            TextureTool.FILL -> img.floodFill(x, y, color)
            TextureTool.PICKER -> if (img.inside(x, y)) { color = img[x, y]; if ((color ushr 24) == 0) color = 0xFF000000.toInt(); tool = TextureTool.PENCIL }
        }
        revision++
    }

    fun strokeMove(x: Int, y: Int) {
        val img = image ?: return
        val (lx, ly) = last ?: return
        if (lx == x && ly == y) return
        when (tool) {
            TextureTool.PENCIL -> img.line(lx, ly, x, y, color, mirror)
            TextureTool.ERASER -> img.line(lx, ly, x, y, PixelImage.TRANSPARENT, mirror)
            else -> return
        }
        last = x to y
        revision++
    }

    fun strokeEnd() {
        val start = strokeStart ?: return
        strokeStart = null; last = null
        val img = image ?: return
        if (!start.pixels.contentEquals(img.pixels)) { pushUndo(start); touched() }
    }

    /** Un deuxième doigt est arrivé : le geste devient un zoom, le trait commencé est annulé. */
    fun strokeCancel() {
        val start = strokeStart ?: return
        strokeStart = null; last = null
        image = start
        revision++
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        image?.let { redo.addLast(it) }
        image = prev; touched()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        image?.let { undo.addLast(it) }
        image = next; touched()
    }

    fun resize(w: Int, h: Int, stretch: Boolean) {
        val img = image ?: return
        if (w == img.width && h == img.height) return
        pushUndo(img.copy())
        image = if (stretch) img.resized(w, h) else img.canvasResized(w, h)
        touched()
    }

    fun clear() {
        val img = image ?: return
        pushUndo(img.copy())
        img.pixels.fill(PixelImage.TRANSPARENT); touched()
    }

    fun save(onDone: (Boolean) -> Unit) {
        val img = image ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { files.write(path, img.toPng(), "Éditeur de textures") }
                    .onFailure { errorLog.log("Enregistrement de $path", it); message = it.message }.isSuccess
            }
            if (ok) dirty = false
            onDone(ok)
        }
    }
}
