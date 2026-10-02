package com.fpink.capture.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fpink.core.ai.RecognitionStrategyId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecognitionStrategyMigrationTest {
    private val legacy = stringPreferencesKey("recognition_provider")
    private val strategy = stringPreferencesKey("recognition_strategy")
    private val config = stringPreferencesKey("recognition_config_encrypted")

    @Test fun `retired and missing providers map to the cursive default`() {
        assertEquals(RecognitionStrategyId.CURSIVE, RecognitionStrategyId.DEFAULT)
        listOf(null, "", "kraken", "paddle", "cursive").forEach {
            assertEquals(RecognitionStrategyId.CURSIVE, storedStrategyId(it), "$it")
        }
        assertEquals(RecognitionStrategyId.PRINTED, storedStrategyId("printed"))
        assertEquals(RecognitionStrategyId("azure"), storedStrategyId("azure"))
    }

    @Test fun `migrates built-in providers and keeps plugin ids and their encrypted config`() = runBlocking {
        assertFalse(RecognitionStrategyMigration.shouldMigrate(emptyPreferences()))
        listOf("paddle" to "cursive", "kraken" to "cursive", "azure" to "azure").forEach { (old, new) ->
            val before = mutablePreferencesOf(legacy to old, config to "iv:secret")
            assertTrue(RecognitionStrategyMigration.shouldMigrate(before))
            val after = RecognitionStrategyMigration.migrate(before)
            assertNull(after[legacy])
            assertEquals(new, after[strategy])
            assertEquals("iv:secret", after[config])
        }
    }

    @Test fun `an already saved strategy wins over a stale provider key`() = runBlocking {
        val after = RecognitionStrategyMigration.migrate(mutablePreferencesOf(legacy to "paddle", strategy to "printed"))
        assertEquals("printed", after[strategy])
        assertNull(after[legacy])
    }
}