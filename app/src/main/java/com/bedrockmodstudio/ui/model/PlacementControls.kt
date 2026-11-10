package com.bedrockmodstudio.ui.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bedrockmodstudio.core.storage.ProjectFiles
import java.util.Locale

internal fun fmt(d: Double) = if (d == Math.floor(d) && Math.abs(d) < 1e9) d.toLong().toString()
else String.format(Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')

/** Réglage d'un nombre : nom, valeur, curseur et boutons − / + pour les petits ajustements. */
@Composable
fun AxisSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, unit: String = "", onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange((value - step).coerceIn(range)) }, modifier = Modifier.width(44.dp)) { Text("−") }
        Slider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = { onChange(Math.round(it / step) * step) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onChange((value + step).coerceIn(range)) }, modifier = Modifier.width(44.dp)) { Text("+") }
        Text(fmt(Math.round(value * 100) / 100.0) + unit, Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/** Lit une texture PNG du projet (sans mise à l'échelle), ou null. */
fun loadBitmap(files: ProjectFiles, path: String?): Bitmap? {
    if (path == null || !files.exists(path)) return null
    return runCatching {
        val bytes = files.readBytes(path)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false })
    }.getOrNull()
}
