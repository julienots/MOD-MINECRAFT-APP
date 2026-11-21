package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.model3d.Face
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.Mat4
import com.bedrockmodstudio.core.model3d.MeshBuilder
import com.bedrockmodstudio.core.model3d.ModelTemplate
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.core.render.Bounds
import com.bedrockmodstudio.core.render.DemoWorld
import com.bedrockmodstudio.core.render.Environment3D
import com.bedrockmodstudio.core.render.GpuMesh
import com.bedrockmodstudio.core.render.RenderMath
import com.bedrockmodstudio.core.render.RenderMode
import com.bedrockmodstudio.core.render.Shaders
import java.io.File
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moteur 3D GPU : la caméra doit tomber pile sur la projection du moteur logiciel (les touchers en dépendent).
 * Le test exporte aussi `build/gl-check/` : shaders + scènes réelles, à ouvrir dans un navigateur WebGL 2
 * (même langage GLSL ES 3.00 qu'Android) pour vérifier la compilation et l'image sans téléphone.
 */
class RenderTest {

    /** Copie de la projection du moteur logiciel (`project()` dans l'application). */
    private fun cpuProject(p: Vec3, yaw: Double, pitch: Double, distance: Double, target: Vec3, w: Int, h: Int): Triple<Double, Double, Double> {
        val cy = cos(Math.toRadians(yaw)); val sy = sin(Math.toRadians(yaw))
        val cp = cos(Math.toRadians(pitch)); val sp = sin(Math.toRadians(pitch))
        val d = p - target
        val x1 = d.x * cy - d.z * sy; val z1 = d.x * sy + d.z * cy
        val y2 = d.y * cp + z1 * sp; val z2 = -d.y * sp + z1 * cp
        val z = z2 + distance
        val f = minOf(w, h) * 1.1
        return Triple(w / 2 + f * x1 / z, h / 2 - f * y2 / z, z)
    }

    @Test
    fun `camera GPU identique au moteur logiciel`() {
        val target = Vec3(3.0, 9.0, -2.0)
        for ((yaw, pitch) in listOf(35.0 to 20.0, -120.0 to 60.0, 200.0 to -30.0)) {
            val w = 1080; val h = 720; val dist = 64.0
            val vp = RenderMath.perspective(w, h, 0.5, 2000.0) * RenderMath.view(yaw, pitch, dist, target)
            for (p in listOf(Vec3(0.0, 0.0, 0.0), Vec3(8.0, 24.0, -6.0), Vec3(-12.0, 3.0, 9.0))) {
                val c = RenderMath.clip(vp, p)
                val sx = (c[0] / c[3] + 1) / 2 * w
                val sy = (1 - c[1] / c[3]) / 2 * h
                val (ex, ey, ez) = cpuProject(p, yaw, pitch, dist, target, w, h)
                assertTrue(abs(sx - ex) < 1e-6 && abs(sy - ey) < 1e-6, "écran ($sx, $sy) ≠ ($ex, $ey)")
                assertTrue(abs(c[3] - ez) < 1e-9)
                assertTrue(c[2] / c[3] in -1.0..1.0)
            }
            // Angle vertical positif : l'œil est au-dessus de la cible.
            assertEquals(pitch > 0, RenderMath.eye(yaw, pitch, dist, target).y > target.y)
            // L'œil calculé est bien à l'origine de la vue.
            val eye = RenderMath.view(yaw, pitch, dist, target).apply(RenderMath.eye(yaw, pitch, dist, target))
            assertTrue(abs(eye.x) + abs(eye.y) + abs(eye.z) < 1e-9, eye.toString())
        }
    }

    @Test
    fun `inverse, matrice lumiere et boites`() {
        val m = RenderMath.perspective(800, 600, 1.0, 500.0) * RenderMath.view(30.0, 25.0, 50.0, Vec3(1.0, 2.0, 3.0))
        val id = RenderMath.inverse(m) * m
        for (i in 0 until 16) assertEquals(if (i % 5 == 0) 1.0 else 0.0, id.m[i], 1e-9)
        val light = RenderMath.lightMatrix(RenderMath.sunDirection(45.0, 50.0), Vec3(0.0, 8.0, 0.0), 20.0)
        for (p in listOf(Vec3(0.0, 8.0, 0.0), Vec3(19.0, 8.0, 0.0), Vec3(0.0, -10.0, 5.0))) {
            val c = RenderMath.clip(light, p)
            assertTrue(c.take(3).all { it in -1.0..1.0 }, "hors de la carte d'ombre : ${c.toList()}")
        }
        val b = Bounds(Vec3(-1.0, 0.0, -1.0), Vec3(1.0, 2.0, 1.0)).transformed(Mat4.translate(Vec3(10.0, 0.0, 0.0)))
        assertEquals(9.0, b.min.x, 1e-9); assertEquals(11.0, b.max.x, 1e-9)
    }

    @Test
    fun `sommets GPU - 6 par face, UV normalisees, couleur ou texture, surbrillance`() {
        val (model, _) = ModelTemplate.QUADRUPED.build("geometry.test")
        val faces = MeshBuilder.build(model)
        val first = faces.first().boneIndex
        val v = GpuMesh.pack(faces, model.textureWidth, model.textureHeight, textured = true, highlight = { it.boneIndex == first })
        assertEquals(faces.size * 6 * GpuMesh.STRIDE, v.size)
        for (i in v.indices step GpuMesh.STRIDE) {
            assertTrue(v[i + 6] in 0f..1f && v[i + 7] in 0f..1f, "UV hors de la texture : ${v[i + 6]}, ${v[i + 7]}")
            assertEquals(1f, v[i + 11]) // texturé
        }
        assertTrue((v.indices step GpuMesh.STRIDE).any { v[it + 14] == 1f })
        val flat = GpuMesh.pack(faces, 64, 64, textured = true, color = { 0xFFFF0000.toInt() })
        assertEquals(1f, flat[8]); assertEquals(0f, flat[9]); assertEquals(0f, flat[11])
    }

    @Test
    fun `monde de demonstration - plateau plat, faces visibles seulement, emplacements`() {
        val world = DemoWorld.build(plateau = 5)
        assertTrue(world.faces.size in 500..40_000, "${world.faces.size} faces")
        // Le dessus du plateau est à y = 0 et aucune face « dessus » du plateau n'est cachée.
        val tops = world.faces.filter { it.face == Face.UP && abs(it.corners[0].y) < 1e-9 && abs(it.corners[0].x) < 64 && abs(it.corners[0].z) < 64 }
        assertTrue(tops.size >= 49, "${tops.size} dessus au centre")
        val atlas = DemoWorld.atlas()
        assertEquals(DemoWorld.ATLAS_WIDTH, atlas.width)
        val boxes = listOf(Bounds(Vec3(-4.0, 3.0, -4.0), Vec3(4.0, 20.0, 4.0)), Bounds.UNIT, Bounds.UNIT)
        val mats = DemoWorld.placements(boxes, 48.0)
        assertEquals(3, mats.size)
        assertEquals(0.0, boxes[0].transformed(mats[0]).min.y, 1e-9) // posé sur le plateau
        assertTrue(DemoWorld.plateauFor(9, 48.0) >= 4)
    }

    @Test
    fun `export du banc d'essai WebGL`() {
        val out = File("build/gl-check").apply { mkdirs() }
        val sb = StringBuilder("// Généré par RenderTest — ne pas modifier.\n")
        fun js(name: String, value: String) = sb.append("window.$name = ").append(value).append(";\n")
        fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun floats(a: FloatArray) = a.joinToString(",", "[", "]") { String.format(Locale.ROOT, "%.4f", it) }
        fun mat(m: Mat4) = floats(RenderMath.gl(m))
        js("SHADERS", Shaders.ALL.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:{vs:${str(v.first)},fs:${str(v.second)}}" })

        // Une vraie créature générée par l'assistant, avec sa texture.
        val gen = MobGenerator.generate("demo", MobSpec("Golem", shortId = "golem", bodyColor = 0xFF7E57C2.toInt(), accentColor = 0xFFFFD54F.toInt()), BedrockVersions.DEFAULT)
        val geo = GeoCodec.parse(gen.files.entries.first { it.key.endsWith(".geo.json") }.value.toString(Charsets.UTF_8)).models.first()
        val png = gen.files.entries.first { it.key.endsWith("textures/entity/golem.png") }.value
        val mobFaces = MeshBuilder.build(geo)
        val mobVerts = GpuMesh.pack(mobFaces, geo.textureWidth, geo.textureHeight, textured = true, highlight = { it.boneIndex == 1 })
        val world = DemoWorld.build(plateau = 5)
        val worldVerts = GpuMesh.pack(world.faces, DemoWorld.ATLAS_WIDTH, DemoWorld.ATLAS_HEIGHT, textured = true)
        js("MOB", "{v:${floats(mobVerts)},png:${str(Base64.getEncoder().encodeToString(png))}}")
        js("WORLD", "{v:${floats(worldVerts)},png:${str(Base64.getEncoder().encodeToString(DemoWorld.atlas().toPng()))}}")

        val w = 640; val h = 420
        val mobBounds = Bounds.of(mobFaces)
        val scenes = ArrayList<String>()
        fun scene(title: String, env: Environment3D, mode: RenderMode, objects: List<Pair<String, Mat4>>, bounds: Bounds, yaw: Double, pitch: Double, dist: Double) {
            val view = RenderMath.view(yaw, pitch, dist, bounds.center)
            val r = bounds.radius.coerceAtLeast(8.0)
            val groundR = maxOf(r * 12, 256.0)
            val proj = RenderMath.perspective(w, h, maxOf(0.5, dist / 200), dist + groundR * 2 + r * 4)
            val sun = RenderMath.sunDirection(45.0, env.sunElevation)
            val light = RenderMath.lightMatrix(sun, bounds.center, r * 1.25)
            val eye = RenderMath.eye(yaw, pitch, dist, bounds.center)
            fun v3(v: Vec3) = floats(floatArrayOf(v.x.toFloat(), v.y.toFloat(), v.z.toFloat()))
            fun c3(c: com.bedrockmodstudio.core.render.Rgb) = floats(c.array())
            scenes += """{title:${str(title)},mode:${mode.code},shadows:${if (mode == RenderMode.XRAY || mode == RenderMode.NORMALS) 0 else 1},
                |view:${mat(view)},proj:${mat(proj)},invVP:${mat(RenderMath.inverse(proj * view))},light:${mat(light)},eye:${v3(eye)},sun:${v3(sun)},
                |center:${v3(bounds.center)},groundY:${bounds.min.y - 0.02},groundR:$groundR,fog:[${dist + r * env.fogStart},${dist + r * env.fogEnd}],
                |env:{skyTop:${c3(env.skyTop)},skyHorizon:${c3(env.skyHorizon)},skyBottom:${c3(env.skyBottom)},sunColor:${c3(env.sunColor)},
                |ambSky:${c3(env.ambientSky)},ambGround:${c3(env.ambientGround)},fog:${c3(env.fog)},ground:${c3(env.ground)},grid:${c3(env.grid)},
                |stars:${env.stars},sunDisc:${env.sunDisc}},
                |objects:[${objects.joinToString(",") { (k, m) -> "{mesh:${str(k)},model:${mat(m)}}" }}]}""".trimMargin()
        }
        scene("Studio — texturé, ombres, surbrillance", Environment3D.STUDIO, RenderMode.TEXTURED, listOf("MOB" to Mat4()), mobBounds, 35.0, 20.0, 60.0)
        scene("Jour — fil de fer", Environment3D.DAY, RenderMode.WIREFRAME, listOf("MOB" to Mat4()), mobBounds, -40.0, 15.0, 55.0)
        val worldBounds = Bounds.of(world.faces)
        val placed = DemoWorld.placements(List(3) { mobBounds }, 40.0)
        scene("Vitrine — coucher de soleil", Environment3D.SUNSET, RenderMode.TEXTURED,
            listOf("WORLD" to Mat4()) + placed.map { "MOB" to it }, worldBounds, 30.0, 28.0, worldBounds.radius * 1.5)
        scene("Nuit — argile", Environment3D.NIGHT, RenderMode.CLAY, listOf("WORLD" to Mat4()) + placed.map { "MOB" to it }, worldBounds, 210.0, 35.0, worldBounds.radius * 1.4)
        scene("Nether — rayons X", Environment3D.NETHER, RenderMode.XRAY, listOf("MOB" to Mat4()), mobBounds, 120.0, 10.0, 60.0)
        scene("End — normales", Environment3D.END, RenderMode.NORMALS, listOf("MOB" to Mat4()), mobBounds, 0.0, 0.0, 60.0)
        js("SCENES", scenes.joinToString(",\n", "[", "]"))
        js("SIZE", "[$w,$h]")
        File(out, "data.js").writeText(sb.toString())
        File(out, "index.html").writeText(javaClass.getResource("/gl-check/index.html")!!.readText())
        assertTrue(File(out, "data.js").length() > 10_000)
    }
}
