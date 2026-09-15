package com.fpink.capture.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

internal const val CANCELLED_JOB_MESSAGE =
    "This job was canceled and will not be processed again. Choose the image again to start a new job."

/** Cancellation tombstones are outside image folders so partial image cleanup cannot erase them. */
internal class JobCancellationStore(
    private val directory: File,
    private val sourceDirectory: (String) -> File,
    private val syncDirectory: (File) -> Unit,
) {
    fun isCancelled(sourceId: String): Boolean {
        sourceDirectory(sourceId)
        val attributes = try {
            Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return false
        }
        if (!attributes.isDirectory) throw IOException("Cancellation storage is not a directory.")
        return exists(File(directory, sourceId)) || exists(File(directory, "$sourceId.pending"))
    }

    fun invalidate(sourceId: String) {
        val source = sourceDirectory(sourceId)
        try {
            Files.createDirectories(directory.toPath())
            val pending = File(directory, "$sourceId.pending")
            FileOutputStream(pending).use {
                it.write(1)
                it.fd.sync()
            }
            Files.move(
                pending.toPath(), File(directory, sourceId).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
            syncDirectory(checkNotNull(directory.parentFile))
        } catch (markerFailure: Exception) {
            // A full filesystem can still allow unlinking the selection. Its absence also
            // prevents restoration, without relying on enough space to create a tombstone.
            try {
                Files.deleteIfExists(File(source, "selection").toPath())
                if (exists(source)) syncDirectory(source)
                else if (exists(checkNotNull(source.parentFile))) syncDirectory(source.parentFile!!)
            } catch (invalidationFailure: Exception) {
                throw IOException("Could not persist cancellation.", invalidationFailure).apply {
                    addSuppressed(markerFailure)
                }
            }
        }
    }

    private fun exists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        true
    } catch (_: NoSuchFileException) {
        false
    }
}
