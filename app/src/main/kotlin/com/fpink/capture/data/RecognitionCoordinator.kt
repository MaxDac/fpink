package com.fpink.capture.data

import com.fpink.core.ai.NoteProcessor
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProvider
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionSettings
import com.fpink.core.model.Note
import com.fpink.core.model.ZettelkastenCategory
import com.fpink.core.model.matchZettelkastenCategory
import com.fpink.core.storage.NoteRepository
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface RecognitionJobState {
    data class Working(val message: String = "Preparing image…", val saving: Boolean = false) : RecognitionJobState
    data class Failed(val message: String, val retryable: Boolean) : RecognitionJobState
    data class Complete(
        val count: Int,
        val cleanupWarning: Boolean = false,
        val previouslySaved: Boolean = false,
    ) : RecognitionJobState
}

/** Application lifetime ownership prevents rotation/navigation from starting a second request. */
class RecognitionCoordinator internal constructor(
    private val readSettings: suspend () -> RecognitionSettings,
    private val rememberSelection: suspend (String, RecognitionSettings) -> Unit,
    private val restoreSelection: suspend (String) -> RecognitionSettings,
    private val loadImage: suspend (String) -> PreparedImage,
    private val discardImage: suspend (String) -> Unit,
    private val invalidateSelection: suspend (String) -> Unit,
    private val isCancelled: suspend (String) -> Boolean,
    private val repository: NoteRepository,
    private val processor: NoteProcessor,
    private val providerFactory: (RecognitionSettings) -> RecognitionProvider,
    private val readZettelkastenCategoryColors: suspend () -> Map<ZettelkastenCategory, List<String>> = { emptyMap() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(
        images: ImageImportStore,
        settings: SettingsStore,
        repository: NoteRepository,
        processor: NoteProcessor,
        providerFactory: (RecognitionSettings) -> RecognitionProvider,
    ) : this(
        { settings.recognitionSettings.first() },
        images::rememberSelection,
        { sourceId -> images.restoreSelection(sourceId) { settings.storedSettings.first().settings } },
        images::load,
        images::discard,
        images::invalidateSelection,
        images::isCancelled,
        repository,
        processor,
        providerFactory,
        { settings.activeZettelkastenCategoryColors.first() },
    )

    private class Record {
        val state = MutableStateFlow<RecognitionJobState>(RecognitionJobState.Working())
        var snapshot: RecognitionSettings? = null
        var task: Job? = null
        var batch: Pair<ByteArray, List<Note>>? = null
        @Volatile var generation = 0
        @Volatile var discarding = false
    }

    private val records = ConcurrentHashMap<String, Record>()
    private val inference = Mutex()

    suspend fun confirm(sourceId: String): RecognitionSettings {
        requireNotCancelled(sourceId)
        val selected = readSettings()
        val snapshot = if (selected.provider == RecognitionProviderId.PADDLE) RecognitionSettings() else selected
        rememberSelection(sourceId, snapshot)
        records.getOrPut(sourceId) { Record() }.snapshot = snapshot
        return snapshot
    }

    fun state(sourceId: String): StateFlow<RecognitionJobState> =
        records.getOrPut(sourceId) { Record() }.state

    @Synchronized
    fun start(sourceId: String, retry: Boolean = false) {
        val record = records.getOrPut(sourceId) { Record() }
        if (record.discarding) return
        if (record.task?.isActive == true || record.state.value is RecognitionJobState.Complete) return
        if (!retry && record.state.value is RecognitionJobState.Failed) return
        val generation = ++record.generation
        record.state.value = RecognitionJobState.Working()
        record.task = scope.launch {
            try {
                inference.withLock {
                    requireNotCancelled(sourceId)
                    if (repository.isSourceCommitted(sourceId).getOrThrow()) {
                        val existing = repository.list().getOrThrow().count { it.sourceId == sourceId }
                        finish(sourceId, record, generation, existing, previouslySaved = true)
                        return@withLock
                    }
                    val snapshot = record.snapshot ?: restoreSelection(sourceId).also { record.snapshot = it }
                    if (record.batch == null) {
                        val image = loadImage(sourceId)
                        requireNotCancelled(sourceId)
                        record.state.value = RecognitionJobState.Working(
                            if (snapshot.provider == RecognitionProviderId.PADDLE) {
                                "Recognizing on this device with PaddleOCR…"
                            } else {
                                "Uploading to your selected recognition provider and recognizing…"
                            },
                        )
                        val recognition = providerFactory(snapshot).recognize(image).getOrThrow()
                        currentCoroutineContext().ensureActive()
                        requireNotCancelled(sourceId)
                        record.state.value = RecognitionJobState.Working("Grouping paragraphs and measuring ink colour locally…")
                        val paragraphs = processor.process(image, recognition).getOrThrow().filter { it.text.isNotBlank() }
                        if (paragraphs.isEmpty()) {
                            throw NoTextException()
                        }
                        val capturedAt = Clock.System.now()
                        // Read once per batch: a mid-batch settings change must not split one job across schemes.
                        val categoryColors = readZettelkastenCategoryColors()
                        record.batch = image.bytes to paragraphs.mapIndexed { index, paragraph ->
                            Note(
                                id = "$sourceId-$index",
                                capturedAt = capturedAt,
                                imagePath = "images/$sourceId.png",
                                text = paragraph.text,
                                inkColorHex = paragraph.inkColorHex,
                                inkColorName = paragraph.inkColorName,
                                confidence = paragraph.confidence,
                                sourceId = sourceId,
                                paragraphIndex = index,
                                paragraphPolygon = paragraph.polygon,
                                recognitionProvider = recognition.provider.id,
                                recognitionModelVersion = recognition.modelVersion,
                                inkColorOrigin = paragraph.colorOrigin,
                                zettelkastenCategory = matchZettelkastenCategory(paragraph.inkColorHex, categoryColors),
                            )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    requireNotCancelled(sourceId)
                    synchronized(record) {
                        if (record.generation != generation) return@withLock
                        record.state.value = RecognitionJobState.Working("Saving all paragraph notes locally…", saving = true)
                    }
                    val (bytes, notes) = checkNotNull(record.batch)
                    repository.saveBatch(sourceId, bytes, "png", notes).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    finish(sourceId, record, generation, notes.size)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (record.generation == generation) record.state.value = error.toJobFailure()
            }
        }
    }

    suspend fun discard(sourceId: String): Boolean {
        val record = records[sourceId]
        val running = record?.let {
            synchronized(it) {
                if ((it.state.value as? RecognitionJobState.Working)?.saving == true) return false
                it.discarding = true
                ++it.generation
                it.task
            }
        }
        try {
            withContext(NonCancellable) { invalidateSelection(sourceId) }
        } catch (cancelled: CancellationException) {
            running?.cancel()
            throw cancelled
        } catch (failure: Exception) {
            running?.cancel()
            record?.state?.value = RecognitionJobState.Failed(
                "Cancellation could not be recorded safely. Retry Discard before leaving this job.",
                retryable = false,
            )
            throw CancellationPersistenceException(failure)
        }
        running?.cancelAndJoin()
        record?.apply {
            batch = null
            snapshot = null
            state.value = RecognitionJobState.Failed(CANCELLED_JOB_MESSAGE, retryable = false)
        }
        discardImage(sourceId)
        records.remove(sourceId)
        return true
    }

    private suspend fun requireNotCancelled(sourceId: String) {
        if (isCancelled(sourceId)) throw RecognitionError.Configuration(CANCELLED_JOB_MESSAGE)
    }

    fun release(sourceId: String) {
        if (records[sourceId]?.state?.value is RecognitionJobState.Complete) records.remove(sourceId)
    }

    private suspend fun finish(
        sourceId: String,
        record: Record,
        generation: Int,
        count: Int,
        previouslySaved: Boolean = false,
    ) {
        val cleanupWarning = try {
            discardImage(sourceId)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
        if (record.generation == generation) {
            record.batch = null
            record.snapshot = null
            record.state.value = RecognitionJobState.Complete(count, cleanupWarning, previouslySaved)
        }
    }
}

private class NoTextException : Exception()

class CancellationPersistenceException(cause: Throwable) : IOException("Cancellation was not recorded safely.", cause)

internal fun Throwable.toJobFailure(): RecognitionJobState.Failed = when (this) {
    is RecognitionError.Configuration -> RecognitionJobState.Failed(
        message ?: "Check recognition settings before trying another image.", false,
    )
    is RecognitionError.Authentication -> RecognitionJobState.Failed(
        "The selected provider rejected the credentials or resource access. Check the settings for that provider. No other provider was used.", false,
    )
    is RecognitionError.ModelUnavailable -> RecognitionJobState.Failed(
        "The bundled PaddleOCR model or native runtime is unavailable. Check model readiness in Settings. No cloud request was made.", false,
    )
    is RecognitionError.UnsupportedDevice -> RecognitionJobState.Failed(
        "PaddleOCR cannot run on this device or input. Check Settings for model/device support; no provider was switched automatically.", false,
    )
    is RecognitionError.Network -> RecognitionJobState.Failed(
        "The selected provider could not be reached or timed out. Check your connection, then retry this same job.", true,
    )
    is RecognitionError.RateLimited -> RecognitionJobState.Failed(
        "The selected provider is rate limited. Wait before retrying this same job.", true,
    )
    is RecognitionError.MalformedResponse -> RecognitionJobState.Failed(
        "The recognition provider returned an unsupported response. Retry, or choose a clearer image.", true,
    )
    is NoTextException -> RecognitionJobState.Failed(
        "No readable text was found. No notes were created. Choose a clearer, closer image.", false,
    )
    is IllegalArgumentException -> RecognitionJobState.Failed(
        "The image or saved job is invalid. Choose the image again.", false,
    )
    is IOException -> RecognitionJobState.Failed(
        "Could not read or save private app data. Check free space and retry; existing notes are unchanged.", true,
    )
    else -> RecognitionJobState.Failed(
        "Processing could not complete. Check settings and available storage, then retry this job.", true,
    )
}
