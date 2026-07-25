package com.fpink.capture

import android.content.Context
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.SettingsStore
import com.fpink.core.ai.AzureOpenAiClient
import com.fpink.core.storage.NoteRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

class AppContainer(context: Context) {
    val settingsStore = SettingsStore(context)

    private val fileStore = AndroidFileStore(context)
    val noteRepository = NoteRepository(fileStore)

    private val httpClient = HttpClient(OkHttp)
    val aiClient = AzureOpenAiClient(httpClient)
}
