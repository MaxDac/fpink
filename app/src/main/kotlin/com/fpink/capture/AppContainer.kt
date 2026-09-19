package com.fpink.capture

import android.content.Context
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.SettingsStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.core.ai.AzureReadProvider
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.ai.RecognitionProvider
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionSettings
import com.fpink.core.storage.NoteRepository
import com.fpink.recognition.paddle.PaddleOcrProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(context)
    val imageImports = ImageImportStore(context)

    private val fileStore = AndroidFileStore(context)
    val noteRepository = NoteRepository(fileStore)

    private val httpClient by lazy { HttpClient(OkHttp) { followRedirects = false } }
    val noteProcessor = DefaultNoteProcessor()
    val recognitionCoordinator = RecognitionCoordinator(
        imageImports, settingsStore, noteRepository, noteProcessor, ::recognitionProvider,
    )

    fun recognitionProvider(settings: RecognitionSettings): RecognitionProvider = when (settings.provider) {
        RecognitionProviderId.PADDLE -> PaddleOcrProvider(appContext)
        RecognitionProviderId.AZURE -> AzureReadProvider(httpClient, settings.azure)
    }

    suspend fun testAzureConnection(settings: RecognitionSettings): Result<Unit> =
        AzureReadProvider(httpClient, settings.azure).testConnection()
}
