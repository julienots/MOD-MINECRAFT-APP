package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.model3d.GeoCodec
import com.bedrockmodstudio.core.model3d.MeshBuilder
import com.bedrockmodstudio.core.model3d.ModelBinding
import com.bedrockmodstudio.core.model3d.ModelTemplate
import com.bedrockmodstudio.core.model3d.ModelUse
import com.bedrockmodstudio.core.model3d.UvPacker
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.storage.UserFacingException
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelTemplatesTest {
    @Test
    fun `chaque modele - UV sans chevauchement dans la texture, texture entierement peinte sous les UV`() {
        for (t in ModelTemplate.entries) {
            val (model, img) = t.build("geometry.t.${t.name.lowercase()}")
            assertEquals(model.textureWidth, img.width); assertEquals(model.textureHeight, img.height)
            assertTrue(model.validate().isEmpty(), t.name)
            val cubes = model.bones.flatMap { it.cubes }
            val rects = cubes.map { c -> val (w, h) = UvPacker.boxSize(c.size); intArrayOf(c.uv.first.toInt(), c.uv.second.toInt(), w, h) }
            for ((i, a) in rects.withIndex()) {
                assertTrue(a[0] >= 0 && a[1] >= 0 && a[0] + a[2] <= img.width && a[1] + a[3] <= img.height, "${t.name} : UV hors texture")
                for (b in rects.drop(i + 1)) {
                    val overlap = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]
                    assertTrue(!overlap, "${t.name} : UV qui se chevauchent")
                }
            }
            // Chaque face visible correspond à des pixels opaques de la texture.
            for (f in MeshBuilder.build(model)) {
                val u = f.uvs.minOf { it.first }.toInt(); val v = f.uvs.minOf { it.second }.toInt()
                assertTrue(img[u.coerceAtMost(img.width - 1), v.coerceAtMost(img.height - 1)] ushr 24 == 0xFF, "${t.name} : face non peinte")
            }
            if (t.use == ModelUse.BLOCK) assertEquals(emptyList(), ModelBinding.blockProblems(model), t.name)
        }
    }

    @Test
    fun `bloc 3D et creature - association, validation sans erreur, modele trop grand refuse`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val ns = meta.namespace
        val files = store.files(meta.id)
        // Bloc : le projet d'exemple en contient un.
        ContentInstaller.install(store, meta.id, ModelBinding.create(ns, "table_magique", ModelTemplate.TABLE, 0xFF8B5A2B.toInt(), 0xFF6B4F3A.toInt()), false, "t")
        val block = ModelBinding.targets(files, ModelUse.BLOCK).first()
        val model = GeoCodec.parse(files.readText(ModelBinding.modelPath(ModelUse.BLOCK, "table_magique"))).models.single()
        ContentInstaller.install(store, meta.id, ModelBinding.bindBlock(files, block, model, ModelBinding.texturePath(ModelUse.BLOCK, "table_magique")), true, "t")
        val comps = JsonParser.parseOrThrow(files.readText(block.path)).path("minecraft:block", "components")
        assertEquals("geometry.$ns.table_magique", comps.path("minecraft:geometry")?.asString)
        assertEquals("alpha_test", comps.path("minecraft:material_instances", "*", "render_method")?.asString)
        assertTrue(files.readText("resource_pack/textures/terrain_texture.json").contains("bms_model_table_magique"))
        // Créature : nouveau modèle humanoïde.
        ContentInstaller.install(store, meta.id, ModelBinding.create(ns, "gardien", ModelTemplate.HUMANOID, 0xFF2E6FA8.toInt(), 0xFFE0A030.toInt()), false, "t")
        val creature = ModelBinding.targets(files, ModelUse.CREATURE).first()
        ContentInstaller.install(store, meta.id, ModelBinding.bindCreature(files, creature, "geometry.$ns.gardien", "textures/entity/gardien"), true, "t")
        assertEquals("geometry.$ns.gardien", JsonParser.parseOrThrow(files.readText(creature.path)).path("minecraft:client_entity", "description", "geometry", "default")?.asString)
        val report = Validator(store.dirOf(meta.id), ns, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        // Un modèle qui dépasse du bloc est refusé, et un modèle de bloc absent est signalé.
        assertFailsWith<UserFacingException> { ModelBinding.bindBlock(files, block, ModelTemplate.HUMANOID.build("geometry.x").first, "textures/blocks/x") }
        files.moveToTrash(ModelBinding.modelPath(ModelUse.BLOCK, "table_magique"))
        assertTrue(Validator(store.dirOf(meta.id), ns, BedrockVersions.DEFAULT).run().errors.any { it.code == "GEOMETRY_MISSING" })
    }
}
