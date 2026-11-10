package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.model3d.Bone
import com.bedrockmodstudio.core.model3d.BonePose
import com.bedrockmodstudio.core.model3d.Cube
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.GeoModel
import com.bedrockmodstudio.core.model3d.HeldItem
import com.bedrockmodstudio.core.model3d.HoldPreset
import com.bedrockmodstudio.core.model3d.HoldTransform
import com.bedrockmodstudio.core.model3d.HoldView
import com.bedrockmodstudio.core.model3d.MeshBuilder
import com.bedrockmodstudio.core.model3d.ModelBinding
import com.bedrockmodstudio.core.model3d.ModelTemplate
import com.bedrockmodstudio.core.model3d.ModelUse
import com.bedrockmodstudio.core.model3d.RideSeats
import com.bedrockmodstudio.core.model3d.RideSpec
import com.bedrockmodstudio.core.model3d.Seat
import com.bedrockmodstudio.core.model3d.Vec3
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeldItemAndRideTest {

    @Test
    fun `rotations dans le sens de Minecraft - bras a -90 vers l'avant, corps du cochon pose sur les pattes`() {
        val arm = GeoModel("geometry.t", 64, 64, listOf(Bone("arm", pivot = Vec3(0.0, 24.0, 0.0), cubes = listOf(Cube(Vec3(-2.0, 12.0, -2.0), Vec3(4.0, 12.0, 4.0))))))
        val raised = MeshBuilder.build(arm, mapOf("arm" to BonePose(rotation = Vec3(-90.0, 0.0, 0.0)))).flatMap { it.corners }
        assertTrue(raised.minOf { it.z } < -11.0, "le bras doit se lever vers l'avant (−Z)")
        // Corps du cochon vanilla : origine [-5, 11, -7], taille [10, 16, 8], pivot [0, 13, 2], rotation [90, 0, 0].
        val pig = GeoModel("geometry.t", 64, 32, listOf(Bone("body", pivot = Vec3(0.0, 13.0, 2.0), rotation = Vec3(90.0, 0.0, 0.0),
            cubes = listOf(Cube(Vec3(-5.0, 11.0, -7.0), Vec3(10.0, 16.0, 8.0))))))
        val pts = MeshBuilder.build(pig).flatMap { it.corners }
        assertEquals(4.0, pts.minOf { it.y }, 1e-6) // sous le haut des pattes (6 px) : le corps repose dessus
        assertEquals(-12.0, pts.minOf { it.z }, 1e-6) // rejoint la tête, à l'avant
    }

    @Test
    fun `objet 3D en main - attachable, animations, liaison a la main, relecture, validation`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val ns = meta.namespace
        val files = store.files(meta.id)
        ContentInstaller.install(store, meta.id, ModelBinding.create(ns, "epee_3d", ModelTemplate.SWORD_3D, 0xFF4FC3F7.toInt(), 0xFF8D6E63.toInt()), false, "t")
        val geoPath = ModelBinding.modelPath(ModelUse.ITEM, "epee_3d")
        val item = HeldItem.items(files).first { it.id.endsWith(":epee_croc") }
        val fp = HoldTransform(Vec3(1.0, 2.0, -3.0), Vec3(-20.0, 5.0, 0.0), 1.2)
        val tp = HoldPreset.SWORD.thirdPerson
        ContentInstaller.install(store, meta.id, HeldItem.generate(files, HeldItem.Spec(item.id, geoPath, "textures/entity/epee_3d", fp, tp)), true, "t")

        val att = JsonParser.parseOrThrow(files.readText(HeldItem.attachablePath(item.id))).path("minecraft:attachable", "description")
        assertEquals(item.id, att.path("identifier")?.asString)
        assertEquals("geometry.$ns.epee_3d", att.path("geometry", "default")?.asString)
        assertEquals("controller.render.item_default", (att.path("render_controllers") as com.bedrockmodstudio.core.json.JsonArray)[0].asString)
        val geo = GeoCodec.parse(files.readText(geoPath))
        assertEquals("1.16.0", geo.formatVersion)
        assertEquals(HeldItem.BINDING, geo.models[0].bone(HeldItem.rootBone(geo.models[0]))!!.extra.str("binding"))
        // Relecture des réglages pour les modifier plus tard.
        val (rfp, rtp) = assertNotNull(HeldItem.read(files, item.id))
        assertEquals(fp, rfp); assertEquals(tp, rtp)
        // Régénérer ne crée pas un second os de liaison.
        ContentInstaller.install(store, meta.id, HeldItem.generate(files, HeldItem.Spec(item.id, geoPath, "textures/entity/epee_3d", fp, tp)), true, "t")
        assertEquals(1, GeoCodec.parse(files.readText(geoPath)).models[0].bones.count { it.extra.str("binding") != null })

        val report = Validator(store.dirOf(meta.id), ns, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        // Une animation de placement supprimée est signalée.
        files.moveToTrash(HeldItem.animationPath(item.id))
        assertTrue(Validator(store.dirOf(meta.id), ns, BedrockVersions.DEFAULT).run().errors.any { it.code == "ANIMATION_MISSING" })
    }

    @Test
    fun `apercu en main - l'objet suit la main, le deplacement suit les reglages`() {
        val (model, _) = ModelTemplate.CRYSTAL.build("geometry.t.cristal")
        for (view in HoldView.entries) {
            val (body, itemFaces) = HeldItem.preview(model, view, HoldTransform())
            assertTrue(body.isNotEmpty() && itemFaces.isNotEmpty())
            val hand = HeldItem.handMatrix(view).apply(Vec3.ZERO)
            val center = itemFaces.flatMap { it.corners }.let { c -> c.reduce { a, b -> a + b } * (1.0 / c.size) }
            assertTrue((center - hand).let { Math.sqrt(it.x * it.x + it.y * it.y + it.z * it.z) } < 12.0, "$view : l'objet doit être dans la main")
            // Déplacer de 5 px dans le repère de la main déplace l'objet de 5 px.
            val moved = HeldItem.preview(model, view, HoldTransform(position = Vec3(0.0, 5.0, 0.0))).second.flatMap { it.corners }
                .let { c -> c.reduce { a, b -> a + b } * (1.0 / c.size) }
            val d = moved - center
            assertEquals(5.0, Math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z), 1e-6)
            // Le glissement à l'écran est converti dans le repère de la main, puis ramené au monde.
            val w = Vec3(1.0, 2.0, 3.0)
            val back = HeldItem.handMatrix(view).let { m -> m.apply(HeldItem.worldToHand(view, w)) - m.apply(Vec3.ZERO) }
            assertEquals(w.x, back.x, 1e-9); assertEquals(w.y, back.y, 1e-9); assertEquals(w.z, back.z, 1e-9)
        }
        // Placement borné (pas de valeurs absurdes écrites dans le pack).
        val c = HoldTransform(Vec3(500.0, 0.0, 0.0), Vec3(370.0, -190.0, 0.0), 50.0).clamped()
        assertEquals(64.0, c.position.x); assertEquals(10.0, c.rotation.x, 1e-9); assertEquals(170.0, c.rotation.y, 1e-9); assertEquals(8.0, c.scale)
    }

    @Test
    fun `places sur une creature - ecriture, relecture, conduite, reste du fichier conserve, refus`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val files = store.files(meta.id)
        val creature = RideSeats.creatures(files).first()
        val before = JsonParser.parseOrThrow(files.readText(creature.path)).path("minecraft:entity", "components") as JsonObject
        val spec = RideSpec(listOf(Seat(Vec3(0.0, 1.1, -0.2)), Seat(Vec3(0.0, 1.1, 0.6), rotateRiderBy = 180.0, lockRiderRotation = 90.0)), controllable = true)
        ContentInstaller.install(store, meta.id, RideSeats.apply(files, creature, spec), true, "t")
        val text = files.readText(creature.path)
        val comps = JsonParser.parseOrThrow(text).path("minecraft:entity", "components") as JsonObject
        assertEquals(2, comps.path("minecraft:rideable", "seat_count")?.asInt)
        assertTrue(comps.containsKey("minecraft:input_ground_controlled"))
        before.keys.forEach { assertTrue(comps.containsKey(it), "composant $it perdu") }
        assertEquals(spec, RideSeats.read(text))
        // Sans conduite : le composant de conduite est retiré.
        ContentInstaller.install(store, meta.id, RideSeats.apply(files, creature, spec.copy(controllable = false)), true, "t")
        assertTrue(!(JsonParser.parseOrThrow(files.readText(creature.path)).path("minecraft:entity", "components") as JsonObject).containsKey("minecraft:input_ground_controlled"))
        assertFailsWith<UserFacingException> { RideSeats.apply(files, creature, RideSpec(emptyList())) }
        assertFailsWith<UserFacingException> { RideSeats.apply(files, creature, RideSpec(listOf(Seat(Vec3(40.0, 1.0, 0.0))))) }
        val report = Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        // Aperçu : le modèle de la créature est retrouvé, le passager est assis au-dessus de sa place.
        val look = RideSeats.look(files, creature)
        assertNotNull(look.model)
        val rider = RideSeats.riderFaces(spec.seats[0], 0).flatMap { it.corners }
        assertTrue(rider.minOf { it.y } > 1.1 * 16 - 12 && rider.maxOf { it.y } > 1.1 * 16 + 12)
    }
}
