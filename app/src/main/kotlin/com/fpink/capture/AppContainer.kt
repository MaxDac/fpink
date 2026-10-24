package com.fpink.capture

import android.content.Context
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.data.RecognitionOption
import com.fpink.capture.data.SettingsStore
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionSettings
import com.fpink.core.ai.RecognitionStrategy
import com.fpink.core.ai.RecognitionStrategyId
import com.fpink.core.ai.RecognitionStrategyRegistry
import com.fpink.core.storage.NoteRepository
import com.fpink.recognition.strategies.BuiltInStrategies
import com.fpink.capture.diagnostics.DiagnosticReporter
import com.fpink.capture.diagnostics.DiagnosticStore
import java.io.File

class AppContainer(
    context: Context,
    val diagnostics: DiagnosticStore = DiagnosticStore(
        File(context.noBackupFilesDir, "diagnostics"),
        File(context.cacheDir, "diagnostics-share"),
    ),
) {
    val appContext: Context = context.applicationContext
    val diagnosticReporter: DiagnosticReporter = diagnostics

    val settingsStore = SettingsStore(context, reporter = diagnosticReporter)
    val imageImports = ImageImportStore(context, reporter = diagnosticReporter)

    private val fileStore = AndroidFileStore(context)
    val noteRepository = NoteRepository(fileStore)

    val noteProcessor = DefaultNoteProcessor()
    val recognitionCoordinator = RecognitionCoordinator(
        imageImports, settingsStore, noteRepository, noteProcessor, ::recognitionStrategy,
        reporter = diagnosticReporter,
    )

    /**
     * `printed` and `cursive` are the on-device strategies built into this (FOSS) build. Any
     * other id is resolved through [RecognitionStrategyRegistry], which only finds a match when
     * a private, non-public strategy module (e.g. Azure or MyScript) has been compiled into the app.
     */
    fun recognitionStrategy(settings: RecognitionSettings): RecognitionStrategy =
        BuiltInStrategies.create(appContext, settings.strategy)
            ?: RecognitionStrategyRegistry.find(settings.strategy)?.create(appContext, settings)
            ?: throw RecognitionError.Configuration("The selected recognition is not available in this build.")

    /** Built-in strategies first, then any plugin strategies bundled into this build. */
    fun recognitionOptions(): List<RecognitionOption> =
        BuiltInStrategies.ids.map { RecognitionOption(it, checkNotNull(BuiltInStrategies.label(it)), requiresNetwork = false) } +
            RecognitionStrategyRegistry.all().map { RecognitionOption(it.id, it.label, it.requiresNetwork) }

    /** Why built-in (on-device) recognition cannot run on this device, or null when it can. */
    fun onDeviceUnsupportedReason(): String? =
        BuiltInStrategies.deviceSupport(appContext).exceptionOrNull()?.let { it.message ?: "On-device recognition cannot run on this device." }

    fun recognitionReadinessChecks(): Map<RecognitionStrategyId, suspend () -> Result<Unit>> =
        BuiltInStrategies.ids.associateWith { id -> suspend { checkNotNull(BuiltInStrategies.create(appContext, id)).readiness() } }
}