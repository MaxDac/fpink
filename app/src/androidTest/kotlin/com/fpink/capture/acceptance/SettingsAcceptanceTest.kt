package com.fpink.capture.acceptance

import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.CredentialCipher
import com.fpink.capture.data.SettingsStore
import com.fpink.capture.data.ThemeMode
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProviderId
import java.security.KeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * These tests exercise [SettingsStore]'s generic, provider-agnostic encrypted storage using a
 * synthetic non-PaddleOCR provider id and config map: this build never ships a second provider,
 * but the storage/encryption guarantees below must hold for whichever provider a private,
 * non-public build (e.g. one adding ABBYY or MyScript) plugs in.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAcceptanceTest {
    private val encryptedConfig = stringPreferencesKey("recognition_config_encrypted")
    private val syntheticProvider = RecognitionProviderId("acceptance-fixture")
    private val syntheticKey = "local-only-synthetic-credential-never-sent"
    private fun config() = mapOf("endpoint" to "https://acceptance-fixture.example", "apiKey" to syntheticKey)

    @Test fun appearancePersistsIndependentlyOfEncryptedRecognitionSettings() = runBlocking {
        AcceptanceStorage().use { fixture ->
            assertEquals(ThemeMode.SYSTEM, fixture.settings.themeMode.first())
            fixture.settings.save(syntheticProvider, config())
            val ciphertext = fixture.preferences.data.first()[encryptedConfig]
            for (mode in ThemeMode.entries) {
                fixture.settings.saveThemeMode(mode)
                val reopened = SettingsStore(fixture.preferences, CredentialCipher(fixture.alias))
                assertEquals(mode, reopened.themeMode.first())
                assertEquals(mode.storedValue, fixture.preferences.data.first()[stringPreferencesKey("appearance_theme")])
                assertEquals(ciphertext, fixture.preferences.data.first()[encryptedConfig])
                assertEquals(config(), reopened.recognitionSettings.first().config)
                assertEquals(syntheticProvider, reopened.recognitionSettings.first().provider)
            }
            fixture.settings.save(RecognitionProviderId.PADDLE, removeConfig = true)
            assertEquals(ThemeMode.DARK, fixture.settings.themeMode.first())
        }
    }

    @Test fun androidKeystoreCiphertextIsRandomizedNonPlaintextAndRoundTripsAfterStoreRecreation() = runBlocking {
        AcceptanceStorage().use { fixture ->
            fixture.settings.save(syntheticProvider, config())
            val first = requireNotNull(fixture.preferences.data.first()[encryptedConfig])
            assertFalse(first.contains(syntheticKey))
            assertFalse(fixture.settingsFile.readBytes().toString(Charsets.UTF_8).contains(syntheticKey))
            assertEquals(12, Base64.decode(first.substringBefore(':'), Base64.NO_WRAP).size)
            assertTrue(Base64.decode(first.substringAfter(':'), Base64.NO_WRAP).size > syntheticKey.length)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue(keyStore.containsAlias(fixture.alias))
            assertNull("Android Keystore secret keys must not be exportable", keyStore.getKey(fixture.alias, null).encoded)
            val reopened = SettingsStore(fixture.preferences, CredentialCipher(fixture.alias)).storedSettings.first()
            assertEquals(config(), reopened.settings.config)
            assertTrue(reopened.hasStoredConfig)
            assertNull(reopened.keyError)
            fixture.settings.save(syntheticProvider, config())
            val second = requireNotNull(fixture.preferences.data.first()[encryptedConfig])
            assertNotEquals("GCM IV must be randomized for each save", first, second)
            assertEquals(config(), fixture.settings.recognitionSettings.first().config)
        }
    }

    @Test fun removingConfigClearsStorageAndReturnsToPaddleDefaults() = runBlocking {
        AcceptanceStorage().use { fixture ->
            fixture.settings.save(syntheticProvider, config())
            fixture.settings.save(RecognitionProviderId.PADDLE, removeConfig = true)
            assertNull(fixture.preferences.data.first()[encryptedConfig])
            val reopened = SettingsStore(fixture.preferences, CredentialCipher(fixture.alias)).storedSettings.first()
            assertEquals(RecognitionProviderId.PADDLE, reopened.settings.provider)
            assertFalse(reopened.hasStoredConfig)
            assertEquals(emptyMap<String, String>(), reopened.settings.config)
            assertNull(reopened.keyError)
            assertFalse(fixture.settingsFile.readBytes().toString(Charsets.UTF_8).contains(syntheticKey))
        }
    }

    @Test fun lostKeystoreAliasAndTamperedCiphertextProduceActionableCredentialErrors() = runBlocking {
        AcceptanceStorage().use { fixture ->
            fixture.settings.save(syntheticProvider, config())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixture.alias) }
            val unavailable = fixture.settings.storedSettings.first()
            assertTrue(unavailable.hasStoredConfig)
            assertEquals(emptyMap<String, String>(), unavailable.settings.config)
            assertNotNull(unavailable.keyError)
            assertTrue(unavailable.keyError!!.contains("Replace or remove"))
            try {
                fixture.settings.recognitionSettings.first()
                fail("Lost credentials must not be silently supplied to the selected provider")
            } catch (_: RecognitionError.Configuration) {
                assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(fixture.alias))
            }
            fixture.settings.save(RecognitionProviderId.PADDLE, config())
            val encrypted = requireNotNull(fixture.preferences.data.first()[encryptedConfig])
            val ciphertext = Base64.decode(encrypted.substringAfter(':'), Base64.NO_WRAP).apply {
                this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            fixture.preferences.edit {
                it[encryptedConfig] = encrypted.substringBefore(':') + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            }
            val tampered = fixture.settings.storedSettings.first()
            assertTrue(tampered.hasStoredConfig)
            assertEquals(emptyMap<String, String>(), tampered.settings.config)
            assertNotNull(tampered.keyError)
            // PADDLE never needs config, so a broken blob under it must not block recognitionSettings.
            assertEquals(RecognitionProviderId.PADDLE, fixture.settings.recognitionSettings.first().provider)
        }
    }
}
