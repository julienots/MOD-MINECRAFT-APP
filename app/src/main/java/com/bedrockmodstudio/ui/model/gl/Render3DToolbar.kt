package com.bedrockmodstudio.ui.model.gl

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bedrockmodstudio.R
import com.bedrockmodstudio.core.render.Environment3D
import com.bedrockmodstudio.core.render.RenderMode
import java.io.File
import java.io.FileOutputStream

/**
 * Boutons posés sur la vue 3D GPU : ambiance, mode d'affichage, ombres et sol, photo HD.
 * Les choix s'appliquent à toutes les vues 3D de l'application et sont mémorisés.
 */
@Composable
fun Render3DToolbar(capture: GpuCapture?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var envMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var lightMenu by remember { mutableStateOf(false) }
    Column(modifier.padding(6.dp).background(Color(0x99101318), RoundedCornerShape(12.dp)).testTag("render3d_toolbar")) {
        Box {
            IconButton(onClick = { envMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Landscape, stringResource(R.string.r3d_environment), tint = Color.White)
            }
            DropdownMenu(envMenu, { envMenu = false }) {
                Environment3D.entries.forEach { e ->
                    DropdownMenuItem(text = { Text((if (e == Render3D.environment) "✓ " else "") + e.labelFr) }, onClick = {
                        envMenu = false; Render3D.environment = e; Render3D.save(context)
                    })
                }
            }
        }
        Box {
            IconButton(onClick = { modeMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Contrast, stringResource(R.string.r3d_mode), tint = Color.White)
            }
            DropdownMenu(modeMenu, { modeMenu = false }) {
                RenderMode.entries.forEach { m ->
                    DropdownMenuItem(text = { Text((if (m == Render3D.mode) "✓ " else "") + m.labelFr) }, onClick = {
                        modeMenu = false; Render3D.mode = m; Render3D.save(context)
                    })
                }
            }
        }
        Box {
            IconButton(onClick = { lightMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.WbSunny, stringResource(R.string.r3d_light), tint = Color.White)
            }
            DropdownMenu(lightMenu, { lightMenu = false }) {
                DropdownMenuItem(text = { Text((if (Render3D.shadows) "✓ " else "") + stringResource(R.string.r3d_shadows)) }, onClick = {
                    Render3D.shadows = !Render3D.shadows; Render3D.save(context)
                })
                DropdownMenuItem(text = { Text((if (Render3D.ground) "✓ " else "") + stringResource(R.string.r3d_ground)) },
                    leadingIcon = { Icon(Icons.Filled.GridOn, null) }, onClick = {
                    Render3D.ground = !Render3D.ground; Render3D.save(context)
                })
                listOf(0f to R.string.r3d_sun_east, 90f to R.string.r3d_sun_south, 180f to R.string.r3d_sun_west, 270f to R.string.r3d_sun_north).forEach { (a, label) ->
                    DropdownMenuItem(text = { Text((if (Render3D.sunAzimuth == a) "✓ " else "") + stringResource(label)) }, onClick = {
                        lightMenu = false; Render3D.sunAzimuth = a; Render3D.save(context)
                    })
                }
            }
        }
        if (capture != null) {
            IconButton(onClick = { capture.capture { bmp -> sharePhoto(context, bmp) } }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.CameraAlt, stringResource(R.string.r3d_photo), tint = Color.White)
            }
        }
    }
}

/** Enregistre l'image dans les exports partageables et ouvre le menu de partage. */
fun sharePhoto(context: Context, bmp: Bitmap?) {
    if (bmp == null) { Toast.makeText(context, R.string.r3d_photo_failed, Toast.LENGTH_SHORT).show(); return }
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "vue3d_${System.currentTimeMillis()}.png")
    runCatching { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        .onFailure { Toast.makeText(context, R.string.r3d_photo_failed, Toast.LENGTH_SHORT).show(); return }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
