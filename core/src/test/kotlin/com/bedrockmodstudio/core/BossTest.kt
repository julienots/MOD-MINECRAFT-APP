package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.BedrockVersions
import com.bedrockmodstudio.core.bedrock.BossPhase
import com.bedrockmodstudio.core.bedrock.BossSpec
import com.bedrockmodstudio.core.bedrock.ContentInstaller
import com.bedrockmodstudio.core.bedrock.MobGenerator
import com.bedrockmodstudio.core.bedrock.MobSpec
import com.bedrockmodstudio.core.bedrock.MobTemperament
import com.bedrockmodstudio.core.json.JsonArray
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.validation.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BossTest {
    private val boss = MobSpec(
        displayName = "Parasite géant", shortId = "parasite", temperament = MobTemperament.HOSTILE, health = 300, attackDamage = 6,
        boss = BossSpec(
            darkenSky = true, immuneFire = true, immuneProjectiles = true, rareLoot = "minecraft:nether_star", rareChance = 0.1,
            phases = listOf(
                BossPhase(70, 9, 0.3, "Le parasite se met en colère !"),
                BossPhase(40, 12, 0.35, ""),
                BossPhase(15, 16, 0.4, "Dernière phase !"),
            ),
        ),
    )

    @Test
    fun `boss a phases - composants, capteur, evenements, butin rare, script`() {
        val store = ProjectStore(tempDir())
        val meta = store.create("Boss")
        ContentInstaller.install(store, meta.id, MobGenerator.generate(meta.namespace, boss, BedrockVersions.DEFAULT), false, "t")
        val files = store.files(meta.id)
        val e = JsonParser.parseOrThrow(files.readText("behavior_pack/entities/parasite.json"))
        val root = e.path("minecraft:entity") as JsonObject
        val comps = root.obj("components")!!
        assertEquals(true, comps.obj("minecraft:boss")!!["should_darken_sky"]?.asBoolean)
        val triggers = comps.obj("minecraft:environment_sensor")!!.arr("triggers")!!
        assertEquals(3, triggers.size)
        // Phase 2 : vie ≤ 70 % de 300 = 210, et propriété de phase == 1.
        val first = triggers[0] as JsonObject
        val conditions = first.obj("filters")!!.arr("all_of")!!.items.map { it as JsonObject }
        assertEquals(210, conditions.single { it.str("test") == "actor_health" }["value"]?.asInt)
        assertEquals(1, conditions.single { it.str("test") == "int_property" }["value"]?.asInt)
        assertEquals("boss:parasite_phase_2", first.str("event"))
        assertEquals(4, (root.path("description", "properties", "boss:phase", "range") as JsonArray)[1].asInt)
        assertEquals(12, root.path("component_groups", "boss:parasite_phase_3", "minecraft:attack", "damage")?.asInt)
        val ev3 = root.path("events", "boss:parasite_phase_3") as JsonObject
        assertEquals(3, ev3.path("set_property", "boss:phase")?.asInt)
        assertEquals("boss:parasite_phase_2", (ev3.path("remove", "component_groups") as JsonArray)[0].asString)
        val loot = JsonParser.parseOrThrow(files.readText("behavior_pack/loot_tables/entities/parasite.json"))
        assertEquals(2, (loot.path("pools") as JsonArray).size)
        val script = files.readText("behavior_pack/scripts/bms/boss_parasite.js")
        assertTrue(script.contains("Dernière phase !") && !script.contains("phase_3\", \"\""))
        val report = Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
        assertTrue(report.issues.none { it.code.startsWith("COMPONENT_") }, report.issues.joinToString("\n"))
        ScriptsTest.exportForTypeCheck(store, meta.id, "boss")
    }

    @Test
    fun `seuils de phase incoherents refuses`() {
        val bad = boss.copy(boss = BossSpec(phases = listOf(BossPhase(40, 5, 0.3), BossPhase(60, 5, 0.3))))
        assertTrue(bad.validate().any { it.contains("plus bas") })
        assertTrue(boss.copy(boss = BossSpec(phases = List(5) { BossPhase(90 - it * 10, 5, 0.3) })).validate().isNotEmpty())
    }
}
