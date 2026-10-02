package com.fpink.recognition.models

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelAssetsTest {
    @Test
    fun `labels ignore only the trailing newline`() {
        assertEquals(listOf("a", " ", "b"), labels("a\n \nb\n"))
        assertEquals(listOf("a", ""), labels("a\n\n"))
    }

    @Test
    fun `bundled dictionaries have the class counts the recognizers expect`() {
        val assets = File("src/main/assets")
        assertEquals(18_708, labels(File(assets, ModelAssets.PP_OCRV6_MEDIUM_REC_DICT.path).readText()).size)
        assertEquals(1_622, labels(File(assets, ModelAssets.KRAKEN_ALPHABET.path).readText()).size)
    }

    @Test
    fun `asset pins agree with the lock file`() {
        val lock = File("artifacts.lock.json").readText()
        listOf(
            ModelAssets.PP_OCRV6_SMALL_DET,
            ModelAssets.PP_OCRV6_MEDIUM_REC,
            ModelAssets.PP_OCRV6_MEDIUM_REC_DICT,
            ModelAssets.KRAKEN_REC,
            ModelAssets.KRAKEN_ALPHABET,
        ).forEach { asset ->
            val entry = Regex("\"destination\": \"src/main/assets/${Regex.escape(asset.path)}\",\\s*\"bytes\": (\\d+),\\s*\"sha256\": \"([0-9a-f]{64})\"")
                .find(lock) ?: error("${asset.path} missing from lock file")
            assertEquals(asset.bytes, entry.groupValues[1].toLong())
            assertEquals(asset.sha256, entry.groupValues[2])
        }
    }
}