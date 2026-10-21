package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.ProjectIndex
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.particle.EmitDirection
import com.bedrockmodstudio.core.particle.ParticleCodec
import com.bedrockmodstudio.core.particle.ParticleGenerator
import com.bedrockmodstudio.core.particle.ParticlePreset
import com.bedrockmodstudio.core.particle.ParticleSimulator
import com.bedrockmodstudio.core.particle.ParticleSpec
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.validation.Validator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticleTest {
    @Test
    fun `chaque modele s'ecrit puis se relit a l'identique, sans element non repris`() {
        for (p in ParticlePreset.entries) {
            val spec = p.spec("ns:${p.name.lowercase()}", "textures/particle/x")
            assertEquals(emptyList(), spec.validate(), p.name)
            val read = ParticleCodec.read(ParticleCodec.toJson(spec))
            assertEquals(emptyList(), read.unsupported, p.name)
            val back = read.spec!!
            // Les couleurs passent par des flottants arrondis : comparaison à une unité près par canal.
            assertEquals(spec.copy(colorStart = 0, colorEnd = 0), back.copy(colorStart = 0, colorEnd = 0), p.name)
            for ((a, b) in listOf(spec.colorStart to back.colorStart, spec.colorEnd to back.colorEnd)) {
                for (sh in listOf(0, 8, 16, 24)) assertTrue(abs(((a ushr sh) and 0xFF) - ((b ushr sh) and 0xFF)) <= 1, p.name)
            }
        }
    }

    @Test
    fun `fichier vanilla avec Molang - signale, jamais modifie en silence`() {
        // Extrait au format de heart.json (Mojang/bedrock-samples) : durée de vie aléatoire en Molang.
        val vanilla = """
            { "format_version": "1.26.10", "particle_effect": {
              "description": { "identifier": "minecraft:heart_particle", "basic_render_parameters": { "material": "particles_alpha", "texture": "textures/particle/particles" } },
              "components": {
                "minecraft:emitter_rate_manual": { "max_particles": 100 },
                "minecraft:emitter_shape_sphere": { "radius": 0.025, "direction": [0, 0.1, 0] },
                "minecraft:particle_initial_speed": 2.5,
                "minecraft:particle_lifetime_expression": { "max_lifetime": "Math.random(0.6, 2.0)" },
                "minecraft:particle_motion_dynamic": { "linear_drag_coefficient": 5 } } } }
        """.trimIndent()
        val r = ParticleCodec.read(vanilla)
        assertTrue(r.unsupported.any { it.contains("emitter_rate_manual") })
        assertTrue(r.unsupported.any { it.contains("durée de vie") })
        assertTrue(r.unsupported.any { it.contains("direction") })
        assertEquals(2.5, r.spec!!.speed)
        assertTrue(ParticleCodec.read("{ oups").spec == null)
    }

    @Test
    fun `simulation - emission, duree de vie, gravite, salve unique`() {
        val spec = ParticleSpec("ns:t", "t", rate = 10.0, speed = 0.0, lifetime = 0.5, gravity = 10.0, direction = EmitDirection.UP)
        val sim = ParticleSimulator(spec)
        repeat(10) { sim.step(0.05) } // 0,5 s
        assertTrue(sim.particles.size in 4..5, "${sim.particles.size}")
        assertTrue(sim.particles.all { it.vel.y < 0 }) // la gravité fait tomber
        repeat(40) { sim.step(0.05) }
        assertTrue(sim.particles.size <= 5) // les particules expirent : pas d'accumulation
        val once = ParticleSimulator(ParticlePreset.HEARTS.spec("ns:c", "t"))
        repeat(100) { once.step(0.05) }
        assertEquals(0, once.particles.size) // une seule salve, toutes expirées
        val burst = ParticleSimulator(ParticlePreset.HEARTS.spec("ns:c", "t"))
        burst.step(0.01)
        assertEquals(8, burst.particles.size)
        val p = burst.particles[0]
        assertTrue(burst.colorOf(p) ushr 24 >= 250) // presque opaque au début du dégradé
    }

    @Test
    fun `generation, validation et action de comportement`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Particules")
        ContentInstaller.install(store, meta.id, ParticleGenerator.generate(meta.namespace, "etincelles", ParticlePreset.SPARKS), false, "t")
        val files = store.files(meta.id)
        val json = JsonParser.parseOrThrow(files.readText("resource_pack/particles/etincelles.json"))
        assertEquals("lookat_xyz", json.path("particle_effect", "components", "minecraft:particle_appearance_billboard", "facing_camera_mode")?.asString)
        assertEquals(listOf("${meta.namespace}:etincelles"), ProjectIndex.particles(files))
        val report = Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        files.moveToTrash("resource_pack/textures/particle/etincelles.png")
        assertTrue(Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run().issues.any { it.code == "TEXTURE_MISSING" })
    }
}
