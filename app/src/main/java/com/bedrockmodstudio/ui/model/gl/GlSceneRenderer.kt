package com.bedrockmodstudio.ui.model.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.bedrockmodstudio.core.model3d.Mat4
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.core.render.Bounds
import com.bedrockmodstudio.core.render.Environment3D
import com.bedrockmodstudio.core.render.GpuMesh
import com.bedrockmodstudio.core.render.RenderMath
import com.bedrockmodstudio.core.render.RenderMode
import com.bedrockmodstudio.core.render.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Un objet de la scène : ses sommets ([GpuMesh]), sa texture et sa place dans le monde. */
class GlBatch(
    val key: Any,
    val vertices: FloatArray,
    val texture: Bitmap?,
    val textureRevision: Any? = null,
    val model: Mat4 = Mat4(),
) {
    /** Boîte englobante dans le monde (sommets transformés par [model]). */
    val bounds: Bounds by lazy {
        if (vertices.isEmpty()) return@lazy Bounds.UNIT
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
        var i = 0
        while (i < vertices.size) {
            val x = vertices[i].toDouble(); val y = vertices[i + 1].toDouble(); val z = vertices[i + 2].toDouble()
            if (x < x0) x0 = x; if (y < y0) y0 = y; if (z < z0) z0 = z
            if (x > x1) x1 = x; if (y > y1) y1 = y; if (z > z1) z1 = z
            i += GpuMesh.STRIDE
        }
        Bounds(Vec3(x0, y0, z0), Vec3(x1, y1, z1)).transformed(model)
    }
}

/** Image à dessiner : objets, caméra orbitale (mêmes réglages que le moteur logiciel) et ambiance. */
class GlScene(
    val batches: List<GlBatch>,
    val yaw: Double,
    val pitch: Double,
    val distance: Double,
    val target: Vec3,
    val bounds: Bounds,
    val environment: Environment3D = Environment3D.STUDIO,
    val mode: RenderMode = RenderMode.TEXTURED,
    val shadows: Boolean = true,
    val ground: Boolean = true,
    /** Angle horizontal du soleil, en degrés. */
    val sunAzimuth: Double = 45.0,
    /** Une face est en surbrillance : l'image est redessinée en continu (pulsation). */
    val animated: Boolean = false,
)

/**
 * Rendu OpenGL ES 3.0 : ciel, ombres du soleil (carte de profondeur 2048², PCF 3×3), sol quadrillé,
 * modèles texturés (pixels nets, découpe des pixels transparents), brouillard et modes d'affichage.
 * Toutes les ressources GPU vivent sur le fil de rendu ; la scène est remplacée d'un bloc par [scene].
 */
class GlSceneRenderer : GLSurfaceView.Renderer {
    @Volatile var scene: GlScene? = null
    @Volatile private var captureCallback: ((Bitmap) -> Unit)? = null
    /** Appelé (sur le fil de rendu) si le GPU refuse un programme : l'écran repasse alors au moteur logiciel. */
    var onFailure: ((String) -> Unit)? = null

    private class Program(val id: Int) {
        private val locations = HashMap<String, Int>()
        fun loc(name: String) = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }

    private class GpuBatch(val vao: Int, val vbo: Int, var count: Int, var vertices: FloatArray?, var tex: Int, var texBitmap: Bitmap?, var texRevision: Any?)

    private lateinit var model: Program
    private lateinit var ground: Program
    private lateinit var sky: Program
    private lateinit var depth: Program
    private var skyVao = 0
    private var groundVao = 0
    private var groundVbo = 0
    private var groundKey: Bounds? = null
    private var shadowFbo = 0
    private var shadowTex = 0
    private var whiteTex = 0
    private var width = 1
    private var height = 1
    private var ready = false
    private val batches = HashMap<Any, GpuBatch>()
    private val start = System.nanoTime()

    fun capture(callback: (Bitmap) -> Unit) { captureCallback = callback }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        batches.clear(); groundKey = null; ready = false
        try {
            model = Program(link(Shaders.MODEL_VS, Shaders.MODEL_FS))
            ground = Program(link(Shaders.GROUND_VS, Shaders.GROUND_FS))
            sky = Program(link(Shaders.SKY_VS, Shaders.SKY_FS))
            depth = Program(link(Shaders.DEPTH_VS, Shaders.DEPTH_FS))
        } catch (e: RuntimeException) {
            Log.e("BmsGl", "Programme GPU refusé", e)
            onFailure?.invoke(e.message ?: "GPU")
            return
        }
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(2, ids, 0); skyVao = ids[0]; groundVao = ids[1]
        GLES30.glGenBuffers(1, ids, 0); groundVbo = ids[0]
        // Carte d'ombre : texture de profondeur avec comparaison matérielle (sampler2DShadow).
        GLES30.glGenTextures(2, ids, 0); shadowTex = ids[0]; whiteTex = ids[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, SHADOW_SIZE, SHADOW_SIZE, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        texParams(GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        GLES30.glGenFramebuffers(1, ids, 0); shadowFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // Texture blanche 1×1 pour les objets sans image.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        val white = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).also { it.position(0) }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, white)
        texParams(GLES30.GL_NEAREST)
        ready = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = scene
        if (!ready || s == null) {
            GLES30.glClearColor(0.08f, 0.09f, 0.11f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        sync(s)
        val env = s.environment
        val view = RenderMath.view(s.yaw, s.pitch, s.distance, s.target)
        val eye = RenderMath.eye(s.yaw, s.pitch, s.distance, s.target)
        val worldBounds = s.bounds
        val r = worldBounds.radius.coerceAtLeast(8.0)
        val groundRadius = maxOf(r * 12, 256.0)
        val near = maxOf(0.5, s.distance / 200)
        val far = s.distance + groundRadius * 2 + r * 4
        val proj = RenderMath.perspective(width, height, near, far)
        val sun = RenderMath.sunDirection(s.sunAzimuth, env.sunElevation)
        val lightVP = RenderMath.lightMatrix(sun, worldBounds.center, r * 1.25)
        val time = (System.nanoTime() - start) / 1e9f
        val shadowsOn = s.shadows && s.mode != RenderMode.XRAY && s.mode != RenderMode.NORMALS

        // 1. Ombres.
        if (shadowsOn) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
            GLES30.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(2f, 4f)
            GLES30.glUseProgram(depth.id)
            mat(depth, "uLightVP", lightVP)
            GLES30.glUniform1i(depth.loc("uTex"), 0)
            for ((key, b) in batches) {
                val src = s.batches.firstOrNull { it.key == key } ?: continue
                mat(depth, "uModel", src.model)
                bindTex(b)
                GLES30.glUniform1f(depth.loc("uHasTex"), if (b.tex != 0) 1f else 0f)
                GLES30.glBindVertexArray(b.vao); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, b.count)
            }
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
        }

        // 2. Ciel.
        GLES30.glClearColor(env.skyHorizon.r, env.skyHorizon.g, env.skyHorizon.b, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(sky.id)
        mat(sky, "uInvViewProj", RenderMath.inverse(proj * view))
        vec(sky, "uEye", eye); rgb(sky, "uSkyTop", env.skyTop.array()); rgb(sky, "uSkyHorizon", env.skyHorizon.array())
        rgb(sky, "uSkyBottom", env.skyBottom.array()); vec(sky, "uSunDir", sun); rgb(sky, "uSunColor", env.sunColor.array())
        GLES30.glUniform1f(sky.loc("uStars"), env.stars); GLES30.glUniform1f(sky.loc("uSunDisc"), env.sunDisc)
        GLES30.glBindVertexArray(skyVao); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        val fogNear = (s.distance + r * env.fogStart).toFloat()
        val fogFar = (s.distance + r * env.fogEnd).toFloat()
        fun lighting(p: Program) {
            mat(p, "uView", view); mat(p, "uProj", proj); mat(p, "uLightVP", lightVP)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
            GLES30.glUniform1i(p.loc("uShadow"), 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform1f(p.loc("uShadowOn"), if (shadowsOn) 1f else 0f)
            vec(p, "uSunDir", sun); rgb(p, "uSunColor", env.sunColor.array())
            rgb(p, "uAmbSky", env.ambientSky.array()); rgb(p, "uAmbGround", env.ambientGround.array())
            rgb(p, "uFog", env.fog.array()); GLES30.glUniform2f(p.loc("uFogRange"), fogNear, fogFar)
            vec(p, "uEye", eye)
        }

        // 3. Sol.
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        if (s.ground) {
            updateGround(worldBounds, groundRadius)
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(ground.id)
            lighting(ground)
            rgb(ground, "uGround", env.ground.array()); rgb(ground, "uGrid", env.grid.array())
            vec(ground, "uCenter", worldBounds.center); GLES30.glUniform1f(ground.loc("uRadius"), groundRadius.toFloat())
            GLES30.glBindVertexArray(groundVao); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // 4. Modèles.
        val xray = s.mode == RenderMode.XRAY
        if (xray) {
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE); GLES30.glDepthMask(false)
        }
        GLES30.glUseProgram(model.id)
        lighting(model)
        GLES30.glUniform1i(model.loc("uTex"), 0)
        GLES30.glUniform1i(model.loc("uMode"), s.mode.code)
        GLES30.glUniform1f(model.loc("uTime"), time)
        GLES30.glUniform1f(model.loc("uAlpha"), 1f)
        for (src in s.batches) {
            val b = batches[src.key] ?: continue
            mat(model, "uModel", src.model)
            bindTex(b)
            GLES30.glUniform1f(model.loc("uHasTex"), if (b.tex != 0) 1f else 0f)
            GLES30.glBindVertexArray(b.vao); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, b.count)
        }
        if (xray) { GLES30.glDepthMask(true); GLES30.glDisable(GLES30.GL_BLEND) }
        GLES30.glBindVertexArray(0)

        captureCallback?.let { cb ->
            captureCallback = null
            cb(readPixels())
        }
    }

    private fun bindTex(b: GpuBatch) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (b.tex != 0) b.tex else whiteTex)
    }

    /** Envoie au GPU ce qui a changé : sommets (nouveau tableau), texture (nouvelle image ou révision). */
    private fun sync(s: GlScene) {
        val keys = s.batches.map { it.key }.toSet()
        batches.keys.filter { it !in keys }.forEach { k -> batches.remove(k)?.let(::release) }
        for (src in s.batches) {
            val b = batches.getOrPut(src.key) {
                val ids = IntArray(1)
                GLES30.glGenVertexArrays(1, ids, 0); val vao = ids[0]
                GLES30.glGenBuffers(1, ids, 0); val vbo = ids[0]
                GpuBatch(vao, vbo, 0, null, 0, null, null)
            }
            if (b.vertices !== src.vertices) {
                b.vertices = src.vertices
                b.count = src.vertices.size / GpuMesh.STRIDE
                GLES30.glBindVertexArray(b.vao)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, src.vertices.size * 4, floats(src.vertices), GLES30.GL_DYNAMIC_DRAW)
                val stride = GpuMesh.STRIDE * 4
                attrib(0, 3, stride, 0); attrib(1, 3, stride, 3); attrib(2, 2, stride, 6); attrib(3, 4, stride, 8); attrib(4, 3, stride, 12)
                GLES30.glBindVertexArray(0)
            }
            val bmp = src.texture
            if (bmp !== b.texBitmap || src.textureRevision != b.texRevision) {
                b.texBitmap = bmp; b.texRevision = src.textureRevision
                if (bmp == null || bmp.isRecycled) {
                    if (b.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(b.tex), 0)
                    b.tex = 0
                } else {
                    if (b.tex == 0) { val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); b.tex = ids[0] }
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.tex)
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                    texParams(GLES30.GL_NEAREST) // pixels nets, comme dans Minecraft
                }
            }
        }
    }

    private fun release(b: GpuBatch) {
        GLES30.glDeleteVertexArrays(1, intArrayOf(b.vao), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(b.vbo), 0)
        if (b.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(b.tex), 0)
    }

    private fun updateGround(bounds: Bounds, radius: Double) {
        if (groundKey == bounds) return
        groundKey = bounds
        val y = (bounds.min.y - 0.02).toFloat()
        val cx = bounds.center.x.toFloat(); val cz = bounds.center.z.toFloat(); val rr = radius.toFloat()
        val v = floatArrayOf(
            cx - rr, y, cz - rr, cx + rr, y, cz - rr, cx + rr, y, cz + rr,
            cx - rr, y, cz - rr, cx + rr, y, cz + rr, cx - rr, y, cz + rr,
        )
        GLES30.glBindVertexArray(groundVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, groundVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, floats(v), GLES30.GL_STATIC_DRAW)
        attrib(0, 3, 12, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun readPixels(): Bitmap {
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buf.position(0)
        raw.copyPixelsFromBuffer(buf)
        // OpenGL lit de bas en haut : on retourne l'image.
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return Bitmap.createBitmap(raw, 0, 0, width, height, m, false).also { raw.recycle() }
    }

    private fun attrib(index: Int, size: Int, stride: Int, offsetFloats: Int) {
        GLES30.glEnableVertexAttribArray(index)
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offsetFloats * 4)
    }

    private fun texParams(filter: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun mat(p: Program, name: String, m: Mat4) = GLES30.glUniformMatrix4fv(p.loc(name), 1, false, RenderMath.gl(m), 0)
    private fun vec(p: Program, name: String, v: Vec3) = GLES30.glUniform3f(p.loc(name), v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
    private fun rgb(p: Program, name: String, c: FloatArray) = GLES30.glUniform3f(p.loc(name), c[0], c[1], c[2])

    companion object {
        const val SHADOW_SIZE = 2048

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(a); it.position(0) }

        private fun compile(type: Int, src: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, src)
            GLES30.glCompileShader(id)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(id)
                GLES30.glDeleteShader(id)
                throw RuntimeException("Compilation du shader : $log")
            }
            return id
        }

        fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Liaison du programme : ${GLES30.glGetProgramInfoLog(p)}")
            return p
        }
    }
}
