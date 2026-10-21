package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.assistant.OfflinePlanner
import com.bedrockmodstudio.core.assistant.PlanJson
import com.bedrockmodstudio.core.assistant.StepType
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.ItemKind
import com.bedrockmodstudio.core.bedrock.MobTemperament
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantTest {
    @Test
    fun `demande en francais - plusieurs elements compris`() {
        val plan = OfflinePlanner.plan("Je veux un zombie de feu rouge hostile avec 40 points de vie, une épée de glace bleue et une tour")
        assertEquals(listOf(StepType.CREATURE, StepType.ITEM, StepType.STRUCTURE), plan.steps.map { it.type }, plan.toString())
        val mob = plan.steps[0]
        assertEquals(MobTemperament.HOSTILE, mob.temperament)
        assertEquals(40, mob.health)
        assertEquals(0xFFD32F2F.toInt(), mob.color)
        assertEquals("Zombie de feu rouge", mob.name)
        assertEquals(ItemKind.SWORD, plan.steps[1].itemKind)
        assertEquals(0xFF1E88E5.toInt(), plan.steps[1].color)
        val p2 = OfflinePlanner.plan("un bloc « Lampe de lune » lumineux, des particules de fumée et un boss dragon de 10 coeurs")
        assertEquals(listOf(StepType.BLOCK, StepType.PARTICLE, StepType.CREATURE), p2.steps.map { it.type }, p2.toString())
        assertEquals("Lampe de lune", p2.steps[0].name)
        assertEquals(15, p2.steps[0].light)
        assertTrue(p2.steps[2].boss); assertEquals(20, p2.steps[2].health)
    }

    @Test
    fun `demande hors sujet ou Java - explication, aucune etape`() {
        val p = OfflinePlanner.plan("installe forge et optifine")
        assertTrue(p.steps.isEmpty())
        assertTrue(p.notes.any { it.contains("Java") })
    }

    @Test
    fun `plan JSON - aller-retour, valeurs bornees, etapes invalides ecartees`() {
        val plan = OfflinePlanner.plan("un loup gentil, une pioche, un minerai vert et une dimension Abysse")
        assertEquals(plan.steps, PlanJson.parse(PlanJson.toJson(plan)).steps)
        val hostile = PlanJson.parse("""{"steps":[{"type":"creature","name":"Titan","health":999999,"speed":50,"color":"#ff0000","temperament":"hostile"},
            {"type":"virus","name":"x"},{"type":"item","name":""}],"notes":["ok"]}""")
        assertEquals(1, hostile.steps.size)
        assertEquals(1024, hostile.steps[0].health); assertEquals(1.0, hostile.steps[0].speed)
        assertEquals(2, hostile.notes.count { it.contains("ignorée") })
        assertTrue(PlanJson.parse("pas du json").steps.isEmpty())
        JsonParser.parseOrThrow(com.bedrockmodstudio.core.json.JsonWriter.write(PlanJson.schema()))
    }

    @Test
    fun `chaque type d'etape s'installe et le projet reste valide`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Assistant")
        val plan = OfflinePlanner.plan("un boss golem hostile, une épée magique, un bloc de briques, des étincelles, une maison, une dimension Nuages")
        assertEquals(StepType.entries.toSet(), plan.steps.map { it.type }.toSet(), plan.toString())
        ContentInstaller.install(store, meta.id, plan.generate(meta.namespace, BedrockVersions.DEFAULT), false, "t")
        val report = Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
    }
}
