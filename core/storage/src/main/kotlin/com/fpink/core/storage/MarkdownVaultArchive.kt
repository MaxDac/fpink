package com.fpink.core.storage

import com.fpink.core.model.MarkdownVaultExport
import com.fpink.core.model.Note
import com.fpink.core.model.VaultOptions
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Streams a Markdown vault (see [MarkdownVaultExport]) as a zip archive, one image in memory at a time. */
object MarkdownVaultArchive {
    data class Summary(val notes: Int, val attachments: Int)

    /**
     * Writes [notes] to [output] and finishes the zip, leaving [output] open for the caller to close.
     * [readImage] returns a note's source image bytes, or null when it has none.
     */
    suspend fun write(
        notes: List<Note>,
        options: VaultOptions,
        output: OutputStream,
        readImage: suspend (Note) -> ByteArray?,
    ): Summary {
        val zip = ZipOutputStream(output)
        val embedded = mutableSetOf<String>()
        notes.distinctBy { it.imagePath }.forEach { note ->
            coroutineContext.ensureActive()
            val bytes = readImage(note) ?: return@forEach
            zip.putNextEntry(ZipEntry(MarkdownVaultExport.attachmentPath(note.imagePath)))
            zip.write(bytes)
            zip.closeEntry()
            embedded += note.imagePath
        }
        MarkdownVaultExport.build(notes, options, embedded).forEach { document ->
            coroutineContext.ensureActive()
            zip.putNextEntry(ZipEntry(document.path))
            zip.write(document.content.encodeToByteArray())
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return Summary(notes.size, embedded.size)
    }
}
