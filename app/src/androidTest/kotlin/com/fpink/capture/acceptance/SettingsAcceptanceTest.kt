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

@RunWith(AndroidJUnit4::class)
class SettingsAcceptanceTest {
    private val encryptedKey = stringPreferencesKey("document_intelligence_encrypted_key")
    private val endpoint = "https://acceptance-fixture.cognitiveservices.azure.com"
    private val syntheticKey = "local-only-synthetic-credential-never-sent"

    @Test fun appearancePersistsIndependentlyOfEncryptedRecognitionSettings() = runBlocking {
        AcceptanceStorage().use { fixture ->
            assertEquals(ThemeMode.SYSTEM, fixture.settings.themeMode.first())
            fixture.settings.save(RecognitionProviderId.AZURE, endpoint, replacementKey = syntheticKey)
            val ciphertext = fixture.preferences.data.first()[encryptedKey]
            for (mode in ThemeMode.entries) {
                fixture.settings.saveThemeMode(mode)
                val reopened = SettingsStore(fixture.preferences, CredentialCipher(fixture.alias))
                assertEquals(mode, reopened.themeMode.first())
                assertEquals(mode.storedValue, fixture.preferences.data.first()[stringPreferencesKey("appearance_theme")])
                assertEquals(ciphertext, fixture.preferences.data.first()[encryptedKey])
                assertEquals(endpoint, reopened.recognitionSettings.first().azure.endpoint)
                assertEquals(syntheticKey, reopened.recognitionSettings.first().azure.apiKey)
                assertEquals(RecognitionProviderId.AZURE, reopened.recognitionSettings.first().provider)
            }
            fixture.settings.save(RecognitionProviderId.PADDLE, endpoint, removeKey = true)
            assertEquals(ThemeMode.DARK, fixture.settings.themeMode.first())
        }
    }

    @Test fun androidKeystoreCiphertextIsRandomizedNonPlaintextAndRoundTripsAfterStoreRecreation() = runBlocking {
        AcceptanceStorage().use { fixture ->
            fixture.settings.save(RecognitionProviderId.AZURE, endpoint, replacementKey = syntheticKey)
            val first = requireNotNull(fixture.preferences.data.first()[encryptedKey])
            assertFalse(first.contains(syntheticKey))
            assertFalse(fixture.settingsFile.readBytes().toString(Charsets.UTF_8).contains(syntheticKey))
            assertEquals(12, Base64.decode(first.substringBefore(':'), Base64.NO_WRAP).size)
            assertTrue(Base64.decode(first.substringAfter(':'), Base64.NO_WRAP).size > syntheticKey.length)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue(keyStore.containsAlias(fixture.alias))
            assertNull("Android Keystore secret keys must not be exportable", keyStore.getKey(fixture.alias, null).encoded)
            val reopened = SettingsStore(fixture.preferences, CredentialCipher(fixture.alias)).storedSettings.first()
            assertEquals(syntheticKey, reopened.settings.azure.apiKey)
            assertEquals(endpoint, reopened.settings.azure.endpoint)
            assertTrue(reopened.hasStoredKey)
            assertNull(reopened.keyError)
            fixture.settings.save(RecognitionProviderId.AZURE, endpoint, replacementKey = syntheticKey)
            val second = requireNotNull(fixture.preferences.data.first()[encryptedKey])
            assertNotEquals("GCM IV must be randomized for each save", first, second)
            assertEquals(syntheticKey, fixture.settings.recognitionSettings.first().azure.apiKey)
        }
    }

    @Test fun removingCredentialPersistsAndCannotLeaveAzureSelectedWithoutAKey() = runBlocking {
        AcceptanceStorage().use { fixture ->
            fixture.settings.save(RecognitionProviderId.AZURE, endpoint, replacementKey = syntheticKey)
            try {
                fixture.settings.save(RecognitionProviderId.AZURE, endpoint, removeKey = true)
                fail("Azure cannot remain selected without credentials")
            } catch (_: RecognitionError.Configuration) {
                assertEquals(syntheticKey, fixture.settings.recognitionSettings.first().azure.apiKey)
            }
            fixture.settings.save(RecognitionProviderId.PADDLE, endpoint, removeKey = true)
            assertNull(fixture.preferences.data.first()[encryptedKey])
            val reopened = SettingsStore(fixture.preferences, CredentialCipher(fixture.alias)).storedSettings.first()
            assertEquals(RecognitionProviderId.PADDLE, reopened.settings.provider)
            assertFalse(reopened.hasStoredKey)
            assertEquals("", reopened.settings.azure.apiKey)
            assertNull(reopened.keyError)
            assertFalse(fixture.settingsFile.readBytes().toString(Charsets.UTF_8).contains(syntheticKey))
        }
    }

    @Test fun lostTestAliasAndTamperedCiphertextProduceActionableCredentialErrors() = runBlocking {
        AcceptanceStorage().use { fixture ->
            fixture.settings.save(RecognitionProviderId.AZURE, endpoint, replacementKey = syntheticKey)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(fixture.alias) }
            val unavailable = fixture.settings.storedSettings.first()
            assertTrue(unavailable.hasStoredKey)
            assertEquals("", unavailable.settings.azure.apiKey)
            assertNotNull(unavailable.keyError)
            assertTrue(unavailable.keyError!!.contains("Replace or remove"))
            try {
                fixture.settings.recognitionSettings.first()
                fail("Lost credentials must not be supplied to Azure")
            } catch (_: RecognitionError.Configuration) {
                assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(fixture.alias))
            }
            fixture.settings.save(RecognitionProviderId.PADDLE, endpoint, replacementKey = syntheticKey)
            val encrypted = requireNotNull(fixture.preferences.data.first()[encryptedKey])
            val ciphertext = Base64.decode(encrypted.substringAfter(':'), Base64.NO_WRAP).apply {
                this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            fixture.preferences.edit {
                it[encryptedKey] = encrypted.substringBefore(':') + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            }
            val tampered = fixture.settings.storedSettings.first()
            assertTrue(tampered.hasStoredKey)
            assertEquals("", tampered.settings.azure.apiKey)
            assertNotNull(tampered.keyError)
            assertEquals(RecognitionProviderId.PADDLE, fixture.settings.recognitionSettings.first().provider)
        }
    }
}
