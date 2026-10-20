package com.bedrockmodstudio.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

fun tempDir(prefix: String = "bms"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        for ((name, data) in entries) {
            z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
        }
    }
    return bos.toByteArray()
}

fun zipEntries(file: File): List<String> = ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toList() }

fun zipText(file: File, name: String): String = ZipFile(file).use { z -> z.getInputStream(z.getEntry(name)).readBytes().toString(Charsets.UTF_8) }

fun ok(outcome: com.bedrockmodstudio.core.packaging.ExportOutcome): com.bedrockmodstudio.core.packaging.ExportOutcome.Success =
    outcome as? com.bedrockmodstudio.core.packaging.ExportOutcome.Success ?: throw AssertionError("Export échoué : $outcome")
