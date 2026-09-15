package com.fpink.core.storage

/**
 * Abstract file storage interface. Paths are relative (e.g. "notes/abc.json").
 * Implemented by AndroidFileStore in :app using context.filesDir.
 *
 * [write] must atomically replace a file and flush its contents before returning success.
 * A failed write may leave either the old or the new complete file, never a partial record.
 * Implementations must propagate I/O failures, including failed directory listing/deletion.
 * Listing an absent directory succeeds with an empty list. Write staging files are internal
 * to the implementation and must not appear in listings.
 */
interface FileStore {
    suspend fun read(path: String): Result<ByteArray>
    suspend fun write(path: String, data: ByteArray): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun list(directory: String): Result<List<String>>  // returns filenames (not full paths)
    suspend fun exists(path: String): Result<Boolean>
}
