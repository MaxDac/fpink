package com.fpink.capture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fpink.core.ai.AiConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val ENDPOINT = stringPreferencesKey("azure_endpoint")
        val DEPLOYMENT = stringPreferencesKey("azure_deployment")
        val API_VERSION = stringPreferencesKey("azure_api_version")
        val API_KEY = stringPreferencesKey("azure_api_key")
    }

    val aiConfig: Flow<AiConfig?> = context.dataStore.data.map { prefs ->
        val endpoint = prefs[Keys.ENDPOINT] ?: return@map null
        val deployment = prefs[Keys.DEPLOYMENT] ?: return@map null
        val apiVersion = prefs[Keys.API_VERSION] ?: return@map null
        val apiKey = prefs[Keys.API_KEY] ?: return@map null
        AiConfig(endpoint, deployment, apiVersion, apiKey)
    }

    suspend fun saveConfig(config: AiConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ENDPOINT] = config.endpoint
            prefs[Keys.DEPLOYMENT] = config.deployment
            prefs[Keys.API_VERSION] = config.apiVersion
            prefs[Keys.API_KEY] = config.apiKey
        }
    }
}
