package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.model3d.AnimationCodec
import com.bedrockmodstudio.core.model3d.BonePose
import com.bedrockmodstudio.core.model3d.Channel
import com.bedrockmodstudio.core.model3d.Cube
import com.bedrockmodstudio.core.model3d.Face
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.Keyframe
import com.bedrockmodstudio.core.model3d.MeshBuilder
import com.bedrockmodstudio.core.model3d.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Model3dTest {
    private fun mobFiles() = MobGenerator.generate("ns", MobSpec("Bête", shortId = "bete"), BedrockVersions.DEFAULT).files

    @Test
    fun `modele genere relu puis reecrit a l'identique`() {
        val text = String(mobFiles()["resource_pack/models/entity/bete.geo.json"]!!)
        val geo = GeoCodec.parse(text)
        val m = geo.models.single()
        assertEquals("geometry.ns.bete", m.identifier)
        assertEquals(6, m.bones.size)
        assertEquals(listOf("head", "leg0", "leg1", "leg2", "leg3"), m.children("body").map { it.name })
        assertEquals(emptyList(), m.validate())
        assertEquals(geo, GeoCodec.parse(GeoCodec.write(geo)))
    }

    @Test
    fun `ancien format converti et champs inconnus conserves`() {
        val legacy = """{"format_version": "1.8.0", "geometry.vieux": {"texturewidth": 32, "textureheight": 32,
            "bones": [{"name": "b", "pivot": [0, 0, 0], "locators": {"tete": [0, 8, 0]},
            "cubes": [{"origin": [0, 0, 0], "size": [2, 3, 4], "uv": {"north": {"uv": [1, 2], "uv_size": [2, 3]}}}]}]}}"""
        val geo = GeoCodec.parse(legacy)
        assertTrue(geo.legacy)
        val out = GeoCodec.write(geo)
        assertTrue(out.contains("\"minecraft:geometry\"") && out.contains("\"locators\"") && out.contains("\"uv_size\""))
        val again = GeoCodec.parse(out).models.single()
        assertEquals(32, again.textureWidth)
        assertEquals(1, MeshBuilder.faceRects(again.bones[0].cubes[0]).size) // seule la face nord a des UV
    }

    @Test
    fun `patron boite et maillage`() {
        val r = MeshBuilder.boxUv(0.0, 0.0, 8.0, 8.0, 8.0)
        assertEquals(listOf(8.0, 8.0, 8.0, 8.0), r[Face.NORTH]!!.toList()) // avant de la tête : là où les yeux sont peints
        assertEquals(listOf(8.0, 0.0, 8.0, 8.0), r[Face.UP]!!.toList())
        val m = GeoCodec.starter("geometry.ns.test")
        val faces = MeshBuilder.build(m)
        assertEquals(6, faces.size)
        val north = faces.single { it.face == Face.NORTH }
        assertTrue(north.corners.all { it.z == -4.0 })
        assertTrue(north.normal.z < 0) // normale vers l'extérieur
        for (f in faces) {
            val center = f.corners.reduce { a, b -> a + b } * 0.25
            val fromCenter = center - Vec3(0.0, 4.0, 0.0) // centre du cube de départ
            assertTrue(fromCenter.x * f.normal.x + fromCenter.y * f.normal.y + fromCenter.z * f.normal.z > 0, "face ${f.face}")
        }
        // Rotation d'os de 90° autour de Y : la face nord passe à l'ouest/est (x constant).
        val turned = MeshBuilder.build(m, mapOf("body" to BonePose(rotation = Vec3(0.0, 90.0, 0.0))))
        val n2 = turned.single { it.face == Face.NORTH }
        assertTrue(n2.corners.map { Math.round(it.x * 1000) }.toSet().size == 1)
        val obj = MeshBuilder.toObj(m)
        assertEquals(24, obj.lines().count { it.startsWith("v ") })
        assertEquals(6, obj.lines().count { it.startsWith("f ") })
    }

    @Test
    fun `animations - lecture, echantillonnage, molang conserve, modeles`() {
        val text = String(mobFiles()["resource_pack/animations/bete.animation.json"]!!)
        val file = AnimationCodec.parse(text)
        val walk = file.animations.first { it.name.endsWith(".walk") }
        assertTrue(walk.bones["leg0"]!!.raw.containsKey(Channel.ROTATION)) // expression Molang conservée
        assertTrue(AnimationCodec.write(file).contains("query.modified_distance_moved"))

        val a = AnimationCodec.template("attack", "animation.ns.attaque", listOf("bras"))
        val mid = a.sample(0.15)["bras"]!!.rotation
        assertEquals(-60.0, mid.x, 1e-9)
        assertEquals(-20.0, a.sample(0.225)["bras"]!!.rotation.x, 1e-6) // à mi-chemin entre -60 et 20
        val edited = a.withKeyframe("bras", Channel.POSITION, Keyframe(0.25, Vec3(0.0, 2.0, 0.0))).withoutKeyframe("bras", Channel.ROTATION, 0.3)
        val round = AnimationCodec.parse(AnimationCodec.write(com.bedrockmodstudio.core.model3d.AnimationFile("1.8.0", listOf(edited)))).animations.single()
        assertEquals(edited.bones["bras"]!!.keyframes, round.bones["bras"]!!.keyframes)
        assertEquals(0.5, round.length)
        for ((k, _) in AnimationCodec.TEMPLATES) assertTrue(AnimationCodec.template(k, "animation.x.$k", listOf("a", "b")).bones.isNotEmpty())
        assertEquals(Cube(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)).inflate, 0.0)
    }
}
