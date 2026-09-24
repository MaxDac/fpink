package com.fpink.capture

import android.content.Context
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.SettingsStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProvider
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionProviderRegistry
import com.fpink.core.ai.RecognitionSettings
import com.fpink.core.storage.NoteRepository
import com.fpink.recognition.kraken.KrakenOcrProvider
import com.fpink.recognition.paddle.PaddleOcrProvider

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(context)
    val imageImports = ImageImportStore(context)

    private val fileStore = AndroidFileStore(context)
    val noteRepository = NoteRepository(fileStore)

    val noteProcessor = DefaultNoteProcessor()
    val recognitionCoordinator = RecognitionCoordinator(
        imageImports, settingsStore, noteRepository, noteProcessor, ::recognitionProvider,
    )

    /**
     * `PADDLE` and `KRAKEN` are the providers built into this (FOSS) build. Any other id is
     * resolved through [RecognitionProviderRegistry], which only finds a match when a private,
     * non-public provider module (e.g. Azure or MyScript) has been compiled into the app.
     */
    fun recognitionProvider(settings: RecognitionSettings): RecognitionProvider = when (settings.provider) {
        RecognitionProviderId.PADDLE -> PaddleOcrProvider(appContext)
        RecognitionProviderId.KRAKEN -> KrakenOcrProvider(appContext)
        else -> RecognitionProviderRegistry.find(settings.provider)?.create(appContext, settings)
            ?: throw RecognitionError.Configuration("The selected recognition provider is not available in this build.")
    }

    fun recognitionReadinessChecks(): Map<RecognitionProviderId, suspend () -> Result<Unit>> = mapOf(
        RecognitionProviderId.PADDLE to { PaddleOcrProvider.readiness(appContext) },
        RecognitionProviderId.KRAKEN to { KrakenOcrProvider.readiness(appContext) },
    )
}
