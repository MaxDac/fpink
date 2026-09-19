package com.fpink.core.storage

import com.fpink.core.model.Note
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Flat note records remain independently editable. A batch becomes visible only when its
 * manifest is atomically published, after its image and every note have been written.
 *
 * Recovery rolls back unpublished batches and finishes published deletions. Empty manifests
 * are retained as retry tombstones: repeating a completed source never resurrects deleted
 * notes or overwrites edits. An I/O failure after publication can therefore return failure
 * even though the operation committed; retrying is safe.
 */
class NoteRepository(
    private val fileStore: FileStore,
    private val json: Json = Json { prettyPrint = true },
) {
    companion object {
        private const val NOTES_DIR = "notes"
        private const val IMAGES_DIR = "images"
        private const val BATCHES_DIR = "batches"
        private const val STAGING_DIR = "staging"
        private const val DELETIONS_DIR = "deletions"
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,159}")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

        // App repositories can be recreated while an old coroutine is still completing.
        private val mutex = Mutex()
    }

    @Serializable
    private data class Batch(
        val sourceId: String,
        val imageExtension: String,
        val originalNoteIds: List<String>,
        val noteIds: List<String> = originalNoteIds,
    ) {
        val imagePath: String get() = "$IMAGES_DIR/$sourceId.$imageExtension"
    }

    suspend fun list(): Result<List<Note>> = operation {
        visibleNotes().sortedWith(
            compareByDescending<Note> { it.capturedAt }
                .thenBy { it.sourceId ?: it.id }
                .thenBy { it.paragraphIndex ?: Int.MAX_VALUE }
                .thenBy { it.id },
        )
    }

    suspend fun get(id: String): Result<Note> = operation {
        validateId(id)
        visibleNote(id)
    }

    /**
     * Includes completed sources whose paragraphs were all deleted. Processing can use this
     * before recognition on restart, instead of mistaking an empty note list for a new source.
     */
    suspend fun isSourceCommitted(sourceId: String): Result<Boolean> = operation {
        validateId(sourceId)
        if (!fileStore.exists(batchPath(sourceId)).getOrThrow()) return@operation false
        readBatchNotes(readBatch(batchPath(sourceId), sourceId))
        true
    }

    suspend fun save(note: Note): Result<Unit> = operation {
        validateNote(note)
        val previous = if (fileStore.exists(notePath(note.id)).getOrThrow()) {
            visibleNote(note.id)
        } else {
            null
        }
        if (note.sourceId != null || previous?.sourceId != null) {
            require(previous != null) { "New paragraph notes must be saved with saveBatch" }
            require(
                note.sourceId == previous.sourceId &&
                    note.imagePath == previous.imagePath &&
                    note.paragraphIndex == previous.paragraphIndex,
            ) { "An edit cannot change paragraph ownership or ordering" }
        }
        require(!isRetiredBatchId(note.id)) { "A deleted paragraph ID cannot be reused" }
        writeNote(note)
    }

    /**
     * [sourceId] is an app-generated stable ID, never a gallery filename. Notes must use
     * "$sourceId-${note.paragraphIndex}" IDs and share "images/$sourceId.$imageExtension".
     * FileStore.write must atomically replace and durably flush a single record.
     */
    suspend fun saveBatch(
        sourceId: String,
        imageBytes: ByteArray,
        imageExtension: String,
        notes: List<Note>,
    ): Result<Unit> = operation {
        validateId(sourceId)
        validateExtension(imageExtension)
        require(imageBytes.isNotEmpty()) { "A source image is required" }
        require(notes.isNotEmpty()) { "A batch must contain at least one paragraph" }
        val ordered = notes.sortedBy { it.paragraphIndex }
        val batch = Batch(sourceId, imageExtension, ordered.map { it.id })
        validateBatch(batch)
        ordered.forEach { note ->
            validateNote(note)
            val paragraphIndex = note.paragraphIndex
            require(note.sourceId == sourceId && note.imagePath == batch.imagePath) {
                "Every paragraph must reference the batch source image"
            }
            require(paragraphIndex != null && paragraphIndex >= 0) {
                "Every paragraph must have a nonnegative index"
            }
            require(note.id == "$sourceId-$paragraphIndex") {
                "Paragraph IDs must be deterministic source-index pairs"
            }
        }
        if (fileStore.exists(batchPath(sourceId)).getOrThrow()) {
            val committed = readBatch(batchPath(sourceId), sourceId)
            require(
                committed.imageExtension == batch.imageExtension &&
                    committed.originalNoteIds == batch.originalNoteIds,
            ) { "The source ID already belongs to a different batch" }
            readBatchNotes(committed)
            return@operation
        }
        require(!fileStore.exists(batch.imagePath).getOrThrow()) {
            "The source image path is already in use"
        }
        require(visibleNotes().none { it.imagePath == batch.imagePath }) {
            "The source image is already referenced by another note"
        }
        batch.originalNoteIds.forEach { id ->
            require(!fileStore.exists(notePath(id)).getOrThrow() && !isRetiredBatchId(id)) {
                "A paragraph ID is already in use"
            }
        }

        // The intent is durable before creating anything recovery may need to remove.
        writeBatch(stagingPath(sourceId), batch)
        fileStore.write(batch.imagePath, imageBytes).getOrThrow()
        ordered.forEach { writeNote(it) }
        writeBatch(batchPath(sourceId), batch)
        deleteIfExists(stagingPath(sourceId))
    }

    suspend fun saveImage(noteId: String, imageBytes: ByteArray): Result<Unit> = operation {
        validateId(noteId)
        val path = "$IMAGES_DIR/$noteId.jpg"
        require(batches().none { it.imagePath == path }) {
            "A batch source image cannot be replaced through saveImage"
        }
        fileStore.write(path, imageBytes).getOrThrow()
    }

    suspend fun delete(id: String): Result<Unit> = operation {
        validateId(id)
        if (!fileStore.exists(notePath(id)).getOrThrow()) return@operation
        val note = visibleNote(id)
        val sourceId = note.sourceId
        if (sourceId == null) {
            // A legacy deletion also needs an intent so an image-delete failure is retryable.
            fileStore.write(deletionPath(id), json.encodeToString(note).encodeToByteArray()).getOrThrow()
            finishLegacyDeletion(note)
        } else {
            val batch = readBatch(batchPath(sourceId), sourceId)
            val updated = batch.copy(noteIds = batch.noteIds - id)
            writeBatch(batchPath(sourceId), updated)
            finishBatchDeletions(updated)
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): Result<T> = try {
        mutex.withLock {
            recover()
            Result.success(block())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun recover() {
        recordIds(STAGING_DIR).forEach { sourceId ->
            val staged = readBatch(stagingPath(sourceId), sourceId)
            require(staged.noteIds == staged.originalNoteIds) { "Invalid batch staging record" }
            if (fileStore.exists(batchPath(sourceId)).getOrThrow()) {
                val committed = readBatch(batchPath(sourceId), sourceId)
                require(
                    committed.imageExtension == staged.imageExtension &&
                        committed.originalNoteIds == staged.originalNoteIds,
                ) { "Staged batch conflicts with its completion record" }
                readBatchNotes(committed)
            } else {
                staged.originalNoteIds.forEach { deleteOwnedBatchNote(it, sourceId) }
                deleteIfExists(staged.imagePath)
            }
            deleteIfExists(stagingPath(sourceId))
        }
        batches().forEach { finishBatchDeletions(it) }
        recordIds(DELETIONS_DIR).forEach { id ->
            val note = readNote(deletionPath(id), id)
            require(note.sourceId == null) { "Invalid legacy deletion record" }
            finishLegacyDeletion(note)
        }
    }

    private suspend fun visibleNotes(): List<Note> {
        val committed = batches().associateBy { it.sourceId }
        val result = mutableListOf<Note>()
        recordIds(NOTES_DIR).forEach { id ->
            val note = readNote(notePath(id), id)
            if (note.sourceId == null) {
                result += note
            } else {
                val batch = committed[note.sourceId]
                if (batch != null && id in batch.noteIds) {
                    validateBatchNote(note, batch)
                    result += note
                }
            }
        }
        // A missing member is corruption, not permission to expose the rest of a batch.
        val found = result.mapTo(mutableSetOf()) { it.id }
        committed.values.forEach { batch ->
            require(batch.noteIds.all { it in found }) { "A committed paragraph record is missing" }
            requireSourceImage(batch)
        }
        return result
    }

    private suspend fun visibleNote(id: String): Note {
        val note = readNote(notePath(id), id)
        val sourceId = note.sourceId
        if (sourceId != null) {
            val batch = readBatch(batchPath(sourceId), sourceId)
            require(id in batch.noteIds) { "The paragraph is not a committed batch member" }
            // Do not expose one paragraph if another committed record has been lost.
            readBatchNotes(batch)
        }
        return note
    }

    private suspend fun readBatchNotes(batch: Batch): List<Note> {
        requireSourceImage(batch)
        return batch.noteIds.map { id ->
            readNote(notePath(id), id).also { validateBatchNote(it, batch) }
        }
    }

    private suspend fun requireSourceImage(batch: Batch) {
        if (batch.noteIds.isNotEmpty()) {
            check(fileStore.exists(batch.imagePath).getOrThrow()) { "The committed source image is missing" }
        }
    }

    private suspend fun finishBatchDeletions(batch: Batch) {
        val deleted = batch.originalNoteIds - batch.noteIds.toSet()
        deleted.forEach { deleteOwnedBatchNote(it, batch.sourceId) }
        if (
            batch.noteIds.isEmpty() && fileStore.exists(batch.imagePath).getOrThrow() &&
            visibleNotes().none { it.imagePath == batch.imagePath }
        ) {
            deleteIfExists(batch.imagePath)
        }
    }

    private suspend fun finishLegacyDeletion(note: Note) {
        if (fileStore.exists(notePath(note.id)).getOrThrow()) {
            check(readNote(notePath(note.id), note.id) == note) {
                "A deletion record conflicts with the current note"
            }
            fileStore.delete(notePath(note.id)).getOrThrow()
        }
        if (visibleNotes().none { it.imagePath == note.imagePath }) {
            deleteIfExists(note.imagePath)
        }
        deleteIfExists(deletionPath(note.id))
    }

    private suspend fun deleteOwnedBatchNote(id: String, sourceId: String) {
        if (fileStore.exists(notePath(id)).getOrThrow()) {
            val note = readNote(notePath(id), id)
            check(note.sourceId == sourceId) { "Refusing to delete an unrelated note" }
            fileStore.delete(notePath(id)).getOrThrow()
        }
    }

    private suspend fun deleteIfExists(path: String) {
        if (fileStore.exists(path).getOrThrow()) fileStore.delete(path).getOrThrow()
    }

    private suspend fun isRetiredBatchId(id: String): Boolean =
        batches().any { id in it.originalNoteIds && id !in it.noteIds }

    private suspend fun batches(): List<Batch> =
        recordIds(BATCHES_DIR).map { readBatch(batchPath(it), it) }

    private suspend fun recordIds(directory: String): List<String> =
        fileStore.list(directory).getOrThrow()
            .filter { it.endsWith(".json") }
            .map { filename ->
                filename.removeSuffix(".json").also { validateId(it) }
            }
            .sorted()

    private suspend fun readNote(path: String, id: String): Note =
        json.decodeFromString<Note>(fileStore.read(path).getOrThrow().decodeToString()).also {
            validateNote(it)
            require(it.id == id) { "A note record does not match its filename" }
        }

    private suspend fun readBatch(path: String, sourceId: String): Batch =
        json.decodeFromString<Batch>(fileStore.read(path).getOrThrow().decodeToString()).also {
            validateBatch(it)
            require(it.sourceId == sourceId) { "A batch record does not match its filename" }
        }

    private suspend fun writeNote(note: Note) {
        fileStore.write(notePath(note.id), json.encodeToString(note).encodeToByteArray()).getOrThrow()
    }

    private suspend fun writeBatch(path: String, batch: Batch) {
        fileStore.write(path, json.encodeToString(batch).encodeToByteArray()).getOrThrow()
    }

    private fun validateBatch(batch: Batch) {
        validateId(batch.sourceId)
        validateExtension(batch.imageExtension)
        require(batch.originalNoteIds.isNotEmpty()) { "A batch must identify its original paragraphs" }
        require(batch.originalNoteIds.distinct().size == batch.originalNoteIds.size) {
            "Duplicate paragraph IDs"
        }
        batch.originalNoteIds.forEach { id ->
            validateId(id)
            val index = id.removePrefix("${batch.sourceId}-").toIntOrNull()
            require(index != null && index >= 0 && id == "${batch.sourceId}-$index") {
                "Invalid source-owned paragraph ID"
            }
        }
        require(batch.noteIds.distinct().size == batch.noteIds.size) { "Duplicate active paragraph IDs" }
        require(batch.noteIds.all { it in batch.originalNoteIds }) { "Unowned batch member" }
    }

    private fun validateBatchNote(note: Note, batch: Batch) {
        require(
            note.sourceId == batch.sourceId && note.imagePath == batch.imagePath &&
                note.paragraphIndex != null && note.id == "${batch.sourceId}-${note.paragraphIndex}",
        ) { "A paragraph record does not match its batch" }
    }

    private fun validateNote(note: Note) {
        validateId(note.id)
        note.sourceId?.let { validateId(it) }
        val paragraphIndex = note.paragraphIndex
        require(paragraphIndex == null || paragraphIndex >= 0) { "Invalid paragraph index" }
        val name = note.imagePath.removePrefix("$IMAGES_DIR/")
        require(note.imagePath == "$IMAGES_DIR/$name" && '/' !in name && '\\' !in name) {
            "Images must be relative files inside the images directory"
        }
        val dot = name.lastIndexOf('.')
        require(dot > 0) { "An image extension is required" }
        validateId(name.substring(0, dot))
        validateExtension(name.substring(dot + 1))
    }

    private fun validateId(id: String) {
        require(SAFE_ID.matches(id)) { "Invalid storage ID" }
    }

    private fun validateExtension(extension: String) {
        require(extension in IMAGE_EXTENSIONS) { "Unsupported image extension" }
    }

    private fun notePath(id: String) = "$NOTES_DIR/$id.json"
    private fun batchPath(sourceId: String) = "$BATCHES_DIR/$sourceId.json"
    private fun stagingPath(sourceId: String) = "$STAGING_DIR/$sourceId.json"
    private fun deletionPath(id: String) = "$DELETIONS_DIR/$id.json"
}
