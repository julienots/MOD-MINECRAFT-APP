package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.bedrock.KnownComponents
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import com.bedrockmodstudio.core.library.ComponentLibrary
import com.bedrockmodstudio.core.library.ComponentTarget
import com.bedrockmodstudio.core.storage.ProjectStore
import com.bedrockmodstudio.core.validation.Validator
import com.bedrockmodstudio.core.bedrock.BedrockVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComponentLibraryTest {
    @Test
    fun `chaque composant existe dans les listes officielles et son exemple est du JSON valide`() {
        for (c in ComponentLibrary.ALL) {
            val known = when (c.target) { ComponentTarget.ENTITY -> KnownComponents.ENTITY; ComponentTarget.ITEM -> KnownComponents.ITEM; ComponentTarget.BLOCK -> KnownComponents.BLOCK }
            assertTrue(c.name in known, "${c.name} absent des composants connus")
            c.value // lève une exception si l'exemple est invalide
        }
        assertEquals(ComponentLibrary.ALL.size, ComponentLibrary.ALL.map { it.target to it.name }.toSet().size) // pas de doublon
        assertTrue(ComponentLibrary.search("feu", null).any { it.name == "minecraft:fire_immune" })
        assertTrue(ComponentLibrary.search("", ComponentTarget.BLOCK).all { it.target == ComponentTarget.BLOCK })
    }

    @Test
    fun `insertion dans les fichiers du projet d'exemple, remplacement seulement sur demande`() {
        val store = ProjectStore(tempDir())
        val meta = store.createExample()
        val files = store.files(meta.id)
        val entity = files.walk("behavior_pack/entities").first { it.endsWith(".json") }
        val comp = ComponentLibrary.ALL.first { it.name == "minecraft:fire_immune" }
        val r = ComponentLibrary.insert(files.readText(entity), comp, replace = false)
        assertIs<ComponentLibrary.InsertResult.Ok>(r)
        files.writeText(entity, r.text)
        assertTrue(JsonParser.parseOrThrow(files.readText(entity)).path("minecraft:entity", "components", "minecraft:fire_immune") is JsonObject)
        assertEquals(ComponentLibrary.InsertResult.Problem("EXISTS"), ComponentLibrary.insert(files.readText(entity), comp, replace = false))
        assertTrue((ComponentLibrary.insert(files.readText(entity), comp, replace = true) as ComponentLibrary.InsertResult.Ok).replaced)
        // Mauvais type de fichier et JSON cassé : refus explicite.
        assertIs<ComponentLibrary.InsertResult.Problem>(ComponentLibrary.insert(files.readText(entity), ComponentLibrary.ALL.first { it.target == ComponentTarget.ITEM }, false))
        assertIs<ComponentLibrary.InsertResult.Problem>(ComponentLibrary.insert("{ oups", comp, false))
        // Tous les composants de créature ajoutés ensemble : le projet reste valide.
        var text = files.readText(entity)
        ComponentLibrary.ALL.filter { it.target == ComponentTarget.ENTITY && it.name != "minecraft:loot" }.forEach {
            text = (ComponentLibrary.insert(text, it, replace = true) as ComponentLibrary.InsertResult.Ok).text
        }
        files.writeText(entity, text)
        val report = Validator(store.dirOf(meta.id), meta.namespace, BedrockVersions.DEFAULT).run()
        assertEquals(emptyList(), report.errors, report.issues.joinToString("\n"))
    }
}
