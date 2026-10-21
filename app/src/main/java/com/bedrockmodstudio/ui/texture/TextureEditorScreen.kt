package com.bedrockmodstudio.ui.texture

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.bedrockmodstudio.R
import com.bedrockmodstudio.container
import com.bedrockmodstudio.core.image.PixelImage
import com.bedrockmodstudio.ui.LocalSnackbar
import com.bedrockmodstudio.ui.common.BmsTopBar
import com.bedrockmodstudio.ui.common.InfoCard
import com.bedrockmodstudio.ui.common.LoadingBox
import com.bedrockmodstudio.ui.common.PALETTE
import com.bedrockmodstudio.ui.theme.BmsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.floor

@Composable
fun TextureEditorScreen(nav: NavHostController, id: String, path: String) {
    val app = LocalContext.current.container
    val vm: TextureEditorViewModel = viewModel(key = "tex/$id/$path") { TextureEditorViewModel(app.store.files(id), path, app.errorLog) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val savedMsg = stringResource(R.string.editor_saved)
    var askLeave by remember { mutableStateOf(false) }
    var showResize by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }

    BackHandler(enabled = vm.dirty) { askLeave = true }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    fun save(thenLeave: Boolean = false) = vm.save { ok ->
        scope.launch(Dispatchers.Main.immediate) { if (ok) { snackbar.showSnackbar(savedMsg); if (thenLeave) nav.popBackStack() } }
    }

    Scaffold(topBar = {
        BmsTopBar(
            path.substringAfterLast('/') + if (vm.dirty) " •" else "",
            subtitle = vm.image?.let { "${it.width} × ${it.height} px" },
            onBack = { if (vm.dirty) askLeave = true else nav.popBackStack() },
            actions = {
                IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                IconButton(onClick = { save() }, enabled = vm.dirty) { Icon(Icons.Filled.Save, stringResource(R.string.save)) }
            },
        )
    }) { padding ->
        val img = vm.image
        when {
            vm.error != null -> Column(Modifier.padding(padding).padding(16.dp)) { InfoCard(vm.error!!, color = BmsColors.Error) }
            img == null -> LoadingBox()
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0D10))) { PixelCanvasView(vm, img) }
                ToolBar(vm, onResize = { showResize = true }, onColor = { showColor = true })
            }
        }
    }

    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_body)) },
        confirmButton = { TextButton(onClick = { askLeave = false; save(thenLeave = true) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { askLeave = false; nav.popBackStack() }) { Text(stringResource(R.string.editor_discard), color = BmsColors.Error) }
                TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    if (showResize) vm.image?.let { img -> ResizeDialog(img, onDismiss = { showResize = false }) { w, h, stretch -> showResize = false; vm.resize(w, h, stretch) } }
    if (showColor) ColorDialog(vm.color, onDismiss = { showColor = false }) { vm.color = it; showColor = false }
}

/** Toile : un doigt dessine, deux doigts zooment et déplacent. */
@Composable
private fun PixelCanvasView(vm: TextureEditorViewModel, img: PixelImage) {
    var scale by remember { mutableFloatStateOf(0f) } // 0 = à calculer pour remplir l'écran
    var offset by remember { mutableStateOf(Offset.Zero) }
    @Suppress("UNUSED_EXPRESSION") vm.revision
    val bitmap = remember(vm.revision, img) {
        Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Canvas(
        Modifier.fillMaxSize().testTag("pixel_canvas")
            .onSizeChanged { sz ->
                // Zoom initial : l'image occupe 90 % de la zone, centrée.
                if (scale == 0f && sz.width > 0) {
                    scale = (minOf(sz.width, sz.height) * 0.9f / maxOf(img.width, img.height)).coerceAtLeast(1f)
                    offset = Offset((sz.width - img.width * scale) / 2, (sz.height - img.height * scale) / 2)
                }
            }
            .pointerInput(img) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (scale == 0f) return@awaitEachGesture
                fun toPixel(p: Offset): Pair<Int, Int> =
                    floor((p.x - offset.x) / scale).toInt() to floor((p.y - offset.y) / scale).toInt()
                val (sx, sy) = toPixel(down.position)
                vm.strokeStart(sx, sy)
                var multi = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        if (!multi) { vm.strokeCancel(); multi = true }
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        val centroid = pressed.map { it.position }.reduce { a, b -> a + b } / pressed.size.toFloat()
                        val newScale = (scale * zoom).coerceIn(1f, 128f)
                        offset = centroid - (centroid - offset) * (newScale / scale) + pan
                        scale = newScale
                    } else if (!multi) {
                        val (x, y) = toPixel(pressed[0].position)
                        vm.strokeMove(x, y)
                    }
                    ev.changes.forEach { it.consume() }
                }
                if (!multi) vm.strokeEnd()
            }
        },
    ) {
        if (scale == 0f) return@Canvas
        val w = img.width * scale; val h = img.height * scale
        translate(offset.x, offset.y) {
            // Damier pour visualiser la transparence.
            val cell = maxOf(scale, 8f)
            var y = 0f; var row = 0
            while (y < h) {
                var x = 0f; var col = 0
                while (x < w) {
                    drawRect(if ((row + col) % 2 == 0) Color(0xFF3A3F47) else Color(0xFF2A2E35), Offset(x, y),
                        Size(minOf(cell, w - x), minOf(cell, h - y)))
                    x += cell; col++
                }
                y += cell; row++
            }
            drawImage(bitmap, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset.Zero, dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.None)
            if (vm.grid && scale >= 6f) {
                val gridColor = Color(0x33FFFFFF)
                for (i in 0..img.width) drawLine(gridColor, Offset(i * scale, 0f), Offset(i * scale, h))
                for (j in 0..img.height) drawLine(gridColor, Offset(0f, j * scale), Offset(w, j * scale))
            }
            if (vm.mirror) drawLine(BmsColors.Secondary.copy(alpha = 0.6f), Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 2f)
        }
    }
}

@Composable
private fun ToolBar(vm: TextureEditorViewModel, onResize: () -> Unit, onColor: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((t, icon) in listOf(
                TextureTool.PENCIL to Icons.Filled.Brush, TextureTool.ERASER to Icons.Filled.AutoFixNormal,
                TextureTool.FILL to Icons.Filled.FormatColorFill, TextureTool.PICKER to Icons.Filled.Colorize,
            )) FilterChip(selected = vm.tool == t, onClick = { vm.tool = t }, label = { Text(t.labelFr) }, leadingIcon = { Icon(icon, null) })
            IconToggleButton(checked = vm.mirror, onCheckedChange = { vm.mirror = it }) {
                Icon(Icons.Filled.Flip, stringResource(R.string.tex_mirror), tint = if (vm.mirror) BmsColors.Secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconToggleButton(checked = vm.grid, onCheckedChange = { vm.grid = it }) {
                Icon(Icons.Filled.Grid4x4, stringResource(R.string.tex_grid), tint = if (vm.grid) BmsColors.Primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onResize) { Icon(Icons.Filled.AspectRatio, stringResource(R.string.tex_resize)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // Couleur actuelle : touche pour la régler précisément.
            Box(Modifier.size(44.dp).background(Color(vm.color), CircleShape).border(3.dp, Color.White, CircleShape).clickable(onClick = onColor))
            val docColors = remember(vm.revision) { vm.image?.usedColors(12) ?: emptyList() }
            (docColors + PALETTE).distinct().forEach { c ->
                Box(Modifier.size(34.dp).background(Color(c), CircleShape)
                    .border(1.dp, if (c == vm.color) Color.White else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { vm.color = c; if (vm.tool == TextureTool.ERASER || vm.tool == TextureTool.PICKER) vm.tool = TextureTool.PENCIL })
            }
        }
    }
}

@Composable
private fun ResizeDialog(img: PixelImage, onDismiss: () -> Unit, onApply: (Int, Int, Boolean) -> Unit) {
    var w by remember { mutableStateOf(img.width) }
    var h by remember { mutableStateOf(img.height) }
    var stretch by remember { mutableStateOf(false) }
    val sizes = listOf(16, 32, 64, 128, 256)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tex_resize)) },
        text = {
            Column {
                Text(stringResource(R.string.tex_width, w))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizes.forEach { s -> FilterChip(selected = w == s, onClick = { w = s }, label = { Text("$s") }) }
                }
                Text(stringResource(R.string.tex_height, h), Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizes.forEach { s -> FilterChip(selected = h == s, onClick = { h = s }, label = { Text("$s") }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.tex_stretch), Modifier.weight(1f)); Switch(stretch, { stretch = it })
                }
                Text(stringResource(R.string.tex_resize_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(w, h, stretch) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Sélecteur de couleur : teinte, saturation, luminosité et opacité. */
@Composable
private fun ColorDialog(initial: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf((initial ushr 24) / 255f) }
    val argb = android.graphics.Color.HSVToColor((alpha * 255).toInt(), floatArrayOf(hue, sat, value))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tex_color)) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().size(48.dp).background(Color(argb)))
                Text(stringResource(R.string.tex_hue)); Slider(hue, { hue = it }, valueRange = 0f..360f)
                Text(stringResource(R.string.tex_saturation)); Slider(sat, { sat = it })
                Text(stringResource(R.string.tex_brightness)); Slider(value, { value = it })
                Text(stringResource(R.string.tex_opacity)); Slider(alpha, { alpha = it })
                Text(String.format("#%08X", argb), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(argb) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
