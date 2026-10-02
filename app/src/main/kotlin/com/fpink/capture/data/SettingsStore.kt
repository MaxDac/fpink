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
import com.fpink.core.ai.RecognitionStrategyId
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
private val LEGACY_PROVIDER_KEY = stringPreferencesKey("recognition_provider")
private val STRATEGY_KEY = stringPreferencesKey("recognition_strategy")

/**
 * Maps a stored strategy, or a provider id saved before strategies existed, to a strategy id.
 * Ids of retired on-device providers and a missing value map to the default;
 * any other id is a plugin strategy and is kept as is.
 */
internal fun storedStrategyId(stored: String?): RecognitionStrategyId = when (stored) {
    null, "", "kraken", "paddle" -> RecognitionStrategyId.DEFAULT
    else -> RecognitionStrategyId(stored)
}

/** Moves `recognition_provider` to `recognition_strategy`; the encrypted plugin config is untouched. */
internal object RecognitionStrategyMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[LEGACY_PROVIDER_KEY] != null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            val legacy = remove(LEGACY_PROVIDER_KEY)
            if (this[STRATEGY_KEY] == null) this[STRATEGY_KEY] = storedStrategyId(legacy).id
        }

    override suspend fun cleanUp() = Unit
}

private val Context.recognitionDataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = {
        listOf(RecognitionStrategyMigration, object : DataMigration<Preferences> {
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
        val STRATEGY = STRATEGY_KEY
        /** Encrypted JSON blob of the whole [RecognitionSettings.config] map for the saved plugin strategy. */
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
        val strategy = storedStrategyId(preferences[Keys.STRATEGY])
        val encrypted = preferences[Keys.CONFIG]
        var keyError: String? = null
        val config = if (encrypted == null) emptyMap() else {
            try {
                Json.decodeFromString<Map<String, String>>(cipher.decrypt(encrypted))
            } catch (_: Exception) {
                keyError = "The saved recognition service credentials are unavailable. Replace or remove them in Settings."
                emptyMap()
            }
        }
        StoredRecognitionSettings(
            RecognitionSettings(strategy, config),
            hasStoredConfig = encrypted != null,
            keyError = keyError,
        )
    }.flowOn(Dispatchers.IO)

    val recognitionSettings: Flow<RecognitionSettings> = storedSettings.map {
        if (it.settings.strategy !in RecognitionStrategyId.BUILT_IN && it.keyError != null) {
            throw RecognitionError.Configuration(it.keyError)
        }
        it.settings
    }

    /**
     * Persists the default [strategy] and, when [config] is non-empty, an encrypted blob of every
     * entry in it (never a subset), so no strategy-specific field is ever left unencrypted. This layer
     * performs no strategy-specific validation: callers are responsible for validating [config] before
     * saving. Pass [removeConfig] to clear any previously stored config (e.g. when switching to a
     * built-in on-device strategy).
     */
    suspend fun save(
        strategy: RecognitionStrategyId,
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
            preferences[Keys.STRATEGY] = strategy.id
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
