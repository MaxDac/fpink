package com.fpink.core.storage

import com.fpink.core.model.Note
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NoteRepository(
    private val fileStore: FileStore,
    private val json: Json = Json { prettyPrint = true },
) {
    companion object {
        private const val NOTES_DIR = "notes"
        private const val IMAGES_DIR = "images"
    }

    suspend fun list(): Result<List<Note>> = runCatching {
        val files = fileStore.list(NOTES_DIR).getOrElse { return Result.success(emptyList()) }
        files
            .filter { it.endsWith(".json") }
            .mapNotNull { filename ->
                val path = "$NOTES_DIR/$filename"
                fileStore.read(path).getOrNull()?.let { bytes ->
                    runCatching { json.decodeFromString<Note>(bytes.decodeToString()) }.getOrNull()
                }
            }
            .sortedByDescending { it.capturedAt }
    }

    suspend fun get(id: String): Result<Note> = runCatching {
        val path = "$NOTES_DIR/$id.json"
        val bytes = fileStore.read(path).getOrThrow()
        json.decodeFromString<Note>(bytes.decodeToString())
    }

    suspend fun save(note: Note): Result<Unit> = runCatching {
        val path = "$NOTES_DIR/${note.id}.json"
        val data = json.encodeToString(note).encodeToByteArray()
        fileStore.write(path, data).getOrThrow()
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        fileStore.delete("$NOTES_DIR/$id.json").getOrThrow()
        // Also try to delete the image, but don't fail if it's missing
        fileStore.delete("$IMAGES_DIR/$id.jpg").getOrElse { /* image may not exist */ }
    }
}
