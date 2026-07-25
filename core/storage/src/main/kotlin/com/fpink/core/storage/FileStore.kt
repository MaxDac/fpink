package com.fpink.core.storage

/**
 * Abstract file storage interface. Paths are relative (e.g. "notes/abc.json").
 * Implemented by AndroidFileStore in :app using context.filesDir.
 */
interface FileStore {
    suspend fun read(path: String): Result<ByteArray>
    suspend fun write(path: String, data: ByteArray): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun list(directory: String): Result<List<String>>  // returns filenames (not full paths)
    suspend fun exists(path: String): Result<Boolean>
}
