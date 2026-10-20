package com.bedrockmodstudio.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonSyntaxError
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.storage.ErrorLog
import com.bedrockmodstudio.core.storage.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * État de l'éditeur. Vit au-delà des rotations d'écran ; les brouillons sont enregistrés sur le disque
 * toutes les 1,5 s pour survivre à une fermeture inattendue de l'application.
 */
class EditorViewModel(
    private val files: ProjectFiles,
    val path: String,
    private val errorLog: ErrorLog,
    private val initialLine: Int,
) : ViewModel() {
    enum class Kind { LOADING, TEXT, IMAGE, BINARY, TOO_BIG, MISSING }

    var kind by mutableStateOf(Kind.LOADING); private set
    var value by mutableStateOf(TextFieldValue("")); private set
    var savedText by mutableStateOf(""); private set
    var jsonError by mutableStateOf<JsonSyntaxError?>(null); private set
    var draftOffer by mutableStateOf<String?>(null); private set
    var bitmap by mutableStateOf<Bitmap?>(null); private set
    var size by mutableStateOf(0L); private set
    var message by mutableStateOf<String?>(null)

    val isJson = path.endsWith(".json", ignoreCase = true) || path.endsWith(".material", ignoreCase = true)
    val dirty get() = kind == Kind.TEXT && value.text != savedText

    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var lastPush = 0L
    private var draftJob: Job? = null
    private var checkJob: Job? = null

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { load() }
            } catch (e: Exception) {
                errorLog.log("Ouverture de $path", e)
                message = e.message
                kind = Kind.MISSING
            }
        }
    }

    private suspend fun load() {
        if (!files.exists(path)) { kind = Kind.MISSING; return }
        val bytes = files.readBytes(path)
        size = bytes.size.toLong()
        val ext = path.substringAfterLast('.', "").lowercase()
        when {
            ext in setOf("png", "jpg", "jpeg") -> {
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                kind = if (bitmap != null) Kind.IMAGE else Kind.BINARY
            }
            !ProjectFiles.isTextFile(path) -> kind = Kind.BINARY
            bytes.size > 2_000_000 -> kind = Kind.TOO_BIG
            else -> {
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n", "\n")
                savedText = text
                draftOffer = files.pendingDraft(path)
                withContext(Dispatchers.Main) {
                    value = TextFieldValue(text, TextRange(offsetOfLine(text, initialLine)))
                    kind = Kind.TEXT
                    scheduleCheck()
                }
            }
        }
    }

    fun onEdit(newValue: TextFieldValue) {
        if (newValue.text != value.text) {
            val now = System.currentTimeMillis()
            if (now - lastPush > 700 || undo.isEmpty()) {
                undo.addLast(value.text); if (undo.size > 200) undo.removeFirst()
                lastPush = now
            }
            redo.clear()
            value = newValue
            scheduleDraft()
            scheduleCheck()
        } else {
            value = newValue
        }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(value.text)
        value = TextFieldValue(prev, TextRange(prev.length.coerceAtMost(value.selection.start)))
        lastPush = 0; scheduleDraft(); scheduleCheck()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(value.text)
        value = TextFieldValue(next, TextRange(next.length.coerceAtMost(value.selection.start)))
        scheduleDraft(); scheduleCheck()
    }

    fun acceptDraft() {
        val d = draftOffer ?: return
        draftOffer = null
        onEdit(TextFieldValue(d))
    }

    fun rejectDraft() {
        draftOffer = null
        viewModelScope.launch(Dispatchers.IO) { files.clearDraft(path) }
    }

    /** Met en forme le JSON. Renvoie false si le JSON est invalide. */
    fun format(): Boolean {
        val v = (JsonParser.parse(value.text) as? JsonParseResult.Success)?.value ?: return false
        onEdit(TextFieldValue(JsonWriter.write(v) + "\n"))
        return true
    }

    fun hasComments(): Boolean = value.text.contains("//") || value.text.contains("/*")

    fun goToError() {
        val e = jsonError ?: return
        value = value.copy(selection = TextRange(e.offset.coerceIn(0, value.text.length)))
    }

    suspend fun save(): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = value.text
            files.writeText(path, text, "Modification dans l'éditeur")
            files.clearDraft(path)
            withContext(Dispatchers.Main) { savedText = text }
            true
        } catch (e: Exception) {
            errorLog.log("Enregistrement de $path", e)
            withContext(Dispatchers.Main) { message = e.message }
            false
        }
    }

    fun discardDraft() { viewModelScope.launch(Dispatchers.IO) { files.clearDraft(path) } }

    private fun scheduleDraft() {
        draftJob?.cancel()
        draftJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1500)
            val text = value.text
            if (text != savedText) runCatching { files.saveDraft(path, text) }
        }
    }

    private fun scheduleCheck() {
        if (!isJson) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            delay(400)
            val text = value.text
            val err = withContext(Dispatchers.Default) { (JsonParser.parse(text) as? JsonParseResult.Failure)?.error }
            jsonError = err
        }
    }

    companion object {
        fun offsetOfLine(text: String, line: Int): Int {
            if (line <= 1) return 0
            var l = 1
            for (i in text.indices) if (text[i] == '\n') { l++; if (l == line) return i + 1 }
            return text.length
        }
    }
}
