package com.bedrockmodstudio.core

import com.bedrockmodstudio.core.json.JsonObject
import com.bedrockmodstudio.core.json.JsonParseResult
import com.bedrockmodstudio.core.json.JsonParser
import com.bedrockmodstudio.core.json.JsonWriter
import com.bedrockmodstudio.core.json.path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonTest {

    @Test
    fun `accepte les commentaires comme Minecraft`() {
        val text = """
            // Do not copy this file directly into your resource pack
            {
              /* bloc */ "format_version": "1.26.50", // fin de ligne
              "minecraft:entity": { "description": { "identifier": "a:b" } }
            }
        """.trimIndent()
        val v = assertIs<JsonParseResult.Success>(JsonParser.parse(text)).value
        assertEquals("a:b", v.path("minecraft:entity", "description", "identifier")?.asString)
    }

    @Test
    fun `accepte le BOM UTF-8`() {
        assertIs<JsonParseResult.Success>(JsonParser.parse("﻿{\"a\": 1}"))
    }

    @Test
    fun `localise les erreurs avec ligne et colonne`() {
        val r = assertIs<JsonParseResult.Failure>(JsonParser.parse("{\n  \"a\": 1\n  \"b\": 2\n}"))
        assertEquals(3, r.error.line)
        assertEquals(3, r.error.column)
        assertTrue(r.error.message.contains("\"a\""))
    }

    @Test
    fun `refuse la virgule finale`() {
        val r = assertIs<JsonParseResult.Failure>(JsonParser.parse("{\"a\": [1, 2,]}"))
        assertTrue(r.error.message.contains("Virgule en trop"))
    }

    @Test
    fun `refuse commentaire non ferme, guillemets simples et fichier vide`() {
        assertIs<JsonParseResult.Failure>(JsonParser.parse("{ /* oups "))
        assertIs<JsonParseResult.Failure>(JsonParser.parse("{'a': 1}"))
        assertIs<JsonParseResult.Failure>(JsonParser.parse("   "))
        assertIs<JsonParseResult.Failure>(JsonParser.parse("{\"a\": tru}"))
        assertIs<JsonParseResult.Failure>(JsonParser.parse("{\"a\": 1} x"))
    }

    @Test
    fun `signale les cles en double`() {
        val r = JsonParser.parse("{\"a\": 1, \"a\": 2}")
        assertEquals(1, r.warnings.size)
        assertEquals("2", ((r.valueOrNull() as JsonObject)["a"]).toString())
    }

    @Test
    fun `ecriture puis relecture identiques, ordre des cles conserve`() {
        val src = """{"z": 1, "a": [1, 26, 50], "s": "é\"\n", "n": -1.5e3, "o": {"t": true, "f": null}}"""
        val v = JsonParser.parseOrThrow(src)
        val out = JsonWriter.write(v)
        assertEquals(v, JsonParser.parseOrThrow(out))
        assertTrue(out.indexOf("\"z\"") < out.indexOf("\"a\""))
        assertTrue(out.contains("[1, 26, 50]"))
        assertTrue(out.contains("-1.5e3"))
    }

    @Test
    fun `refuse une imbrication extreme sans planter`() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertIs<JsonParseResult.Failure>(JsonParser.parse(deep))
    }
}
