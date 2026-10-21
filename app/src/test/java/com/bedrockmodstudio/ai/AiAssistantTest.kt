package com.bedrockmodstudio.ai

import com.anthropic.core.jsonMapper
import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vérifie la requête construite pour l'IA facultative, sans aucun appel réseau. */
class AiAssistantTest {
    @Test
    fun `requete - modele, schema du plan et demande transmise comme donnee`() {
        val params = AiAssistant.buildParams("un dragon violet")
        val body = JsonParser.parseOrThrow(jsonMapper().writeValueAsString(params._body())) as JsonObject
        assertEquals(AiAssistant.MODEL, body.str("model"))
        assertEquals("json_schema", body.path("output_config", "format", "type")?.asString)
        assertEquals("object", body.path("output_config", "format", "schema", "type")?.asString)
        assertTrue(body.path("output_config", "format", "schema", "properties", "steps") is JsonObject)
        assertEquals("default", body.str("fallbacks"))
        assertTrue(body.toString().contains("<demande>\\nun dragon violet\\n</demande>"))
        assertEquals("server-side-fallback-2026-07-01", params._headers().values("anthropic-beta").single())
    }
}
