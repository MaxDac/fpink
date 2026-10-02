package com.fpink.capture.ui.notes

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Writes an export into a document the user created through the Storage Access Framework. */
class SafVaultExportTarget(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
) : VaultExportTarget {
    override fun open(): OutputStream =
        contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The export destination could not be opened")

    override fun discard() {
        DocumentsContract.deleteDocument(contentResolver, uri)
    }

    companion object {
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

        fun defaultFileName(now: LocalDateTime = LocalDateTime.now()): String = "fpink-export-${FILE_STAMP.format(now)}.zip"
    }
}
