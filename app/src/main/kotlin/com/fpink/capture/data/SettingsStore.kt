package com.fpink.capture.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionSettings
import com.fpink.core.model.ZettelkastenCategory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val hasStoredConfig: Boolean,
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
        /** Encrypted JSON blob of the whole [RecognitionSettings.config] map for the saved provider. */
        val CONFIG = stringPreferencesKey("recognition_config_encrypted")
        val THEME = stringPreferencesKey("appearance_theme")
        val ZETTELKASTEN_ENABLED = booleanPreferencesKey("zettelkasten_enabled")
        val ZETTELKASTEN_CATEGORY_COLORS = stringPreferencesKey("zettelkasten_category_colors")
    }

    override val themeMode: Flow<ThemeMode> = store.data.map {
        ThemeMode.fromStored(it[Keys.THEME])
    }

    override suspend fun saveThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.storedValue }
    }

    /** Whether the Zettelkasten organisation beta feature is turned on in Settings. */
    val zettelkastenEnabled: Flow<Boolean> = store.data.map { it[Keys.ZETTELKASTEN_ENABLED] ?: false }

    /** The colours configured for each fixed Zettelkasten category, regardless of [zettelkastenEnabled]. */
    val zettelkastenCategoryColors: Flow<Map<ZettelkastenCategory, List<String>>> = store.data.map {
        decodeZettelkastenCategoryColors(it[Keys.ZETTELKASTEN_CATEGORY_COLORS])
    }

    /**
     * The colours to use for automatic category matching: empty whenever the beta feature is off,
     * so a disabled feature never silently recategorizes new captures.
     */
    val activeZettelkastenCategoryColors: Flow<Map<ZettelkastenCategory, List<String>>> = store.data.map { preferences ->
        if (preferences[Keys.ZETTELKASTEN_ENABLED] != true) return@map emptyMap()
        decodeZettelkastenCategoryColors(preferences[Keys.ZETTELKASTEN_CATEGORY_COLORS])
    }

    suspend fun saveZettelkastenEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        store.edit { it[Keys.ZETTELKASTEN_ENABLED] = enabled }
    }

    suspend fun saveZettelkastenCategoryColors(colors: Map<ZettelkastenCategory, List<String>>) = withContext(Dispatchers.IO) {
        store.edit { it[Keys.ZETTELKASTEN_CATEGORY_COLORS] = encodeZettelkastenCategoryColors(colors) }
    }

    val storedSettings: Flow<StoredRecognitionSettings> = store.data.map { preferences ->
        val provider = preferences[Keys.PROVIDER]?.let { RecognitionProviderId(it) } ?: RecognitionProviderId.PADDLE
        val encrypted = preferences[Keys.CONFIG]
        var keyError: String? = null
        val config = if (encrypted == null) emptyMap() else {
            try {
                Json.decodeFromString<Map<String, String>>(cipher.decrypt(encrypted))
            } catch (_: Exception) {
                keyError = "The saved recognition provider credentials are unavailable. Replace or remove them in Settings."
                emptyMap()
            }
        }
        StoredRecognitionSettings(
            RecognitionSettings(provider, config),
            hasStoredConfig = encrypted != null,
            keyError = keyError,
        )
    }.flowOn(Dispatchers.IO)

    val recognitionSettings: Flow<RecognitionSettings> = storedSettings.map {
        if (it.settings.provider != RecognitionProviderId.PADDLE && it.keyError != null) {
            throw RecognitionError.Configuration(it.keyError)
        }
        it.settings
    }

    /**
     * Persists [provider] and, when [config] is non-empty, an encrypted blob of every entry in it
     * (never a subset), so no provider-specific field is ever left unencrypted. This layer performs
     * no provider-specific validation: callers are responsible for validating [config] before saving.
     * Pass [removeConfig] to clear any previously stored config (e.g. when switching back to PaddleOCR).
     */
    suspend fun save(
        provider: RecognitionProviderId,
        config: Map<String, String> = emptyMap(),
        removeConfig: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val encrypted = config.takeIf { it.isNotEmpty() }?.let {
            try {
                cipher.encrypt(Json.encodeToString(it))
            } catch (_: Exception) {
                throw RecognitionError.Configuration("Could not protect the credentials with Android Keystore. Nothing was saved.")
            }
        }
        store.edit { preferences ->
            preferences[Keys.PROVIDER] = provider.id
            when {
                removeConfig -> preferences.remove(Keys.CONFIG)
                encrypted != null -> preferences[Keys.CONFIG] = encrypted
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
