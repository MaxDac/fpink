package com.fpink.core.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object NoteExport {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    fun asJson(notes: List<Note>): String = json.encodeToString(notes.map(ExportedNote::from))

    fun asText(notes: List<Note>): String = notes.joinToString(separator = "\n\n", transform = Note::text)
}

@Serializable
private data class ExportedNote(
    val id: String,
    @Serializable(with = InstantSerializer::class)
    val capturedAt: Instant,
    val text: String,
    val inkColorHex: String?,
    val inkColorName: String?,
    val confidence: Float?,
    val modelNotes: String?,
    val userEdited: Boolean,
    val tags: List<String>,
    val links: List<String>,
    val sourceId: String?,
    val paragraphIndex: Int?,
    val paragraphPolygon: List<ImagePoint>,
    val recognitionProvider: String?,
    val recognitionModelVersion: String?,
    val inkColorOrigin: InkColorOrigin?,
) {
    companion object {
        fun from(note: Note) = ExportedNote(
            id = note.id,
            capturedAt = note.capturedAt,
            text = note.text,
            inkColorHex = note.inkColorHex,
            inkColorName = note.inkColorName,
            confidence = note.confidence,
            modelNotes = note.modelNotes,
            userEdited = note.userEdited,
            tags = note.tags,
            links = note.links,
            sourceId = note.sourceId,
            paragraphIndex = note.paragraphIndex,
            paragraphPolygon = note.paragraphPolygon,
            recognitionProvider = note.recognitionProvider,
            recognitionModelVersion = note.recognitionModelVersion,
            inkColorOrigin = note.inkColorOrigin,
        )
    }
}
