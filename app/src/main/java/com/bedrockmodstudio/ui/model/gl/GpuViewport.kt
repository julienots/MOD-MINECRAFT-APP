package com.bedrockmodstudio.ui.model.gl

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bedrockmodstudio.core.render.Environment3D
import com.bedrockmodstudio.core.render.RenderMode
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Réglages de la vue 3D, partagés par tous les écrans et conservés entre deux lancements.
 * Le moteur GPU est utilisé quand le téléphone gère OpenGL ES 3.0 ; sinon (ou si l'utilisateur le
 * demande) le moteur logiciel prend le relais, avec les mêmes gestes et la même caméra.
 */
object Render3D {
    private const val PREFS = "bms_render3d"
    var gpuEnabled by mutableStateOf(true)
    var environment by mutableStateOf(Environment3D.STUDIO)
    var mode by mutableStateOf(RenderMode.TEXTURED)
    var shadows by mutableStateOf(true)
    var ground by mutableStateOf(true)
    var sunAzimuth by mutableStateOf(45f)
    /** Le GPU a refusé nos programmes pendant cette session : moteur logiciel jusqu'au prochain lancement. */
    var gpuFailed by mutableStateOf(false)
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        gpuEnabled = p.getBoolean("gpu", true)
        environment = Environment3D.entries.firstOrNull { it.name == p.getString("env", null) } ?: Environment3D.STUDIO
        mode = RenderMode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: RenderMode.TEXTURED
        shadows = p.getBoolean("shadows", true)
        ground = p.getBoolean("ground", true)
        sunAzimuth = p.getFloat("sun", 45f)
    }

    fun save(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putBoolean("gpu", gpuEnabled); putString("env", environment.name); putString("mode", mode.name)
        putBoolean("shadows", shadows); putBoolean("ground", ground); putFloat("sun", sunAzimuth)
    }

    /** OpenGL ES 3.0 disponible (jamais dans l'Android simulé des tests, qui n'a pas de GPU). */
    fun gpuSupported(context: Context): Boolean {
        if (Build.FINGERPRINT == "robolectric" || Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
    }

    fun useGpu(context: Context) = gpuEnabled && !gpuFailed && gpuSupported(context)
}

/** Choisit une configuration avec anticrénelage 4× si possible, sinon une configuration simple avec profondeur. */
private class MultisampleChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
        fun pick(samples: Int): EGLConfig? {
            val attrs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE,
            )
            val out = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            return if (egl.eglChooseConfig(display, attrs, out, 1, n) && n[0] > 0) out[0] else null
        }
        return pick(4) ?: pick(0) ?: run {
            val attrs = intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE)
            val out = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            egl.eglChooseConfig(display, attrs, out, 1, n)
            out[0] ?: throw IllegalStateException("Aucune configuration OpenGL ES 3 disponible")
        }
    }
}

/** Poignée pour demander une capture d'image de la vue GPU. */
class GpuCapture {
    internal var renderer: GlSceneRenderer? = null
    internal var view: GLSurfaceView? = null

    /** [onResult] est appelé sur le fil principal avec l'image, ou null si la vue GPU n'est pas active. */
    fun capture(onResult: (Bitmap?) -> Unit) {
        val r = renderer; val v = view
        if (r == null || v == null) { onResult(null); return }
        val main = Handler(Looper.getMainLooper())
        r.capture { bmp -> main.post { onResult(bmp) } }
        v.requestRender()
    }
}

/** Vue OpenGL ES 3 d'une [GlScene]. Les gestes sont gérés par une couche Compose posée au-dessus. */
@Composable
fun GpuSceneView(scene: GlScene, modifier: Modifier = Modifier, capture: GpuCapture? = null) {
    val context = LocalContext.current
    val renderer = remember { GlSceneRenderer() }
    renderer.onFailure = { Handler(Looper.getMainLooper()).post { Render3D.gpuFailed = true } }
    val holder = remember { arrayOfNulls<GLSurfaceView>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); capture?.renderer = null; capture?.view = null }
    }
    AndroidView(
        modifier = modifier,
        factory = {
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(MultisampleChooser())
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                holder[0] = this
            }
        },
        update = { v ->
            renderer.scene = scene
            capture?.renderer = renderer; capture?.view = v
            val wanted = if (scene.animated) GLSurfaceView.RENDERMODE_CONTINUOUSLY else GLSurfaceView.RENDERMODE_WHEN_DIRTY
            if (v.renderMode != wanted) v.renderMode = wanted
            v.requestRender()
        },
    )
}
