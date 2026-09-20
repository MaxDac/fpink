package com.fpink.capture.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fpink.core.ai.AzureReadConfig
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

private val legacyKeys = listOf("azure_endpoint", "azure_deployment", "azure_api_version", "azure_api_key")
private val Context.recognitionDataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = {
        listOf(object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) =
                legacyKeys.any { currentData[stringPreferencesKey(it)] != null }

            override suspend fun migrate(currentData: Preferences): Preferences =
                currentData.toMutablePreferences().apply {
                    legacyKeys.forEach { remove(stringPreferencesKey(it)) }
                }

            override suspend fun cleanUp() = Unit
        })
    },
)

data class StoredRecognitionSettings(
    val settings: RecognitionSettings,
    val hasStoredKey: Boolean,
    val keyError: String? = null,
)

class SettingsStore internal constructor(
    private val store: DataStore<Preferences>,
    private val cipher: CredentialCipher,
) : ThemeSettings {
    constructor(context: Context) : this(context.applicationContext.recognitionDataStore, CredentialCipher())

    init {
        // Purge retired plaintext credentials even if this launch only opens existing notes.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { store.data.first() }
        }
    }

    private object Keys {
        val PROVIDER = stringPreferencesKey("recognition_provider")
        val ENDPOINT = stringPreferencesKey("document_intelligence_endpoint")
        val KEY = stringPreferencesKey("document_intelligence_encrypted_key")
        val THEME = stringPreferencesKey("appearance_theme")
    }

    override val themeMode: Flow<ThemeMode> = store.data.map {
        ThemeMode.fromStored(it[Keys.THEME])
    }

    override suspend fun saveThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.storedValue }
    }

    val storedSettings: Flow<StoredRecognitionSettings> = store.data.map { preferences ->
        val provider = preferences[Keys.PROVIDER]?.let { stored ->
            RecognitionProviderId.entries.find { it.name == stored }
                ?: throw RecognitionError.Configuration("The saved recognition provider is not supported. Choose one in Settings.")
        } ?: RecognitionProviderId.PADDLE
        val encrypted = preferences[Keys.KEY]
        var keyError: String? = null
        val key = if (encrypted == null) "" else {
            try {
                cipher.decrypt(encrypted)
            } catch (_: Exception) {
                keyError = "The saved Azure key is unavailable. Replace or remove it in Settings."
                ""
            }
        }
        StoredRecognitionSettings(
            RecognitionSettings(provider, AzureReadConfig(preferences[Keys.ENDPOINT].orEmpty(), key)),
            hasStoredKey = encrypted != null,
            keyError = keyError,
        )
    }.flowOn(Dispatchers.IO)

    val recognitionSettings: Flow<RecognitionSettings> = storedSettings.map {
        if (it.settings.provider == RecognitionProviderId.AZURE && it.keyError != null) {
            throw RecognitionError.Configuration(it.keyError)
        }
        it.settings
    }

    suspend fun save(
        provider: RecognitionProviderId,
        endpoint: String,
        replacementKey: String? = null,
        removeKey: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val normalizedEndpoint = endpoint.trim().trimEnd('/')
        if (normalizedEndpoint.isNotEmpty() && !isValidAzureEndpoint(normalizedEndpoint)) {
            throw RecognitionError.Configuration("Use an Azure Document Intelligence HTTPS resource or regional endpoint, without a path, query or credentials.")
        }
        val encrypted = replacementKey?.takeIf { it.isNotBlank() }?.let {
            try {
                cipher.encrypt(it.trim())
            } catch (_: Exception) {
                throw RecognitionError.Configuration("Could not protect the key with Android Keystore. Nothing was saved.")
            }
        }
        store.edit { preferences ->
            if (provider == RecognitionProviderId.AZURE) {
                if (normalizedEndpoint.isEmpty()) {
                    throw RecognitionError.Configuration("Enter your Document Intelligence resource endpoint.")
                }
                val keyAvailable = !removeKey && (encrypted != null || preferences[Keys.KEY]?.let {
                    try { cipher.decrypt(it).isNotBlank() } catch (_: Exception) { false }
                } == true)
                if (!keyAvailable) {
                    throw RecognitionError.Configuration("Enter a working Azure API key, or select PaddleOCR before removing it.")
                }
            }
            preferences[Keys.PROVIDER] = provider.name
            preferences[Keys.ENDPOINT] = normalizedEndpoint
            when {
                removeKey -> preferences.remove(Keys.KEY)
                encrypted != null -> preferences[Keys.KEY] = encrypted
            }
        }
        Unit
    }
}

internal class CredentialCipher(private val alias: String = "fpink.document-intelligence.key.v1") {
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)}"
    }

    fun decrypt(value: String): String {
        val parts = value.split(':')
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
