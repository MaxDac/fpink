package com.fpink.capture.data

import android.content.Context
import com.fpink.core.storage.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidFileStore(private val context: Context) : FileStore {
    private fun resolve(path: String): File = File(context.filesDir, path)

    override suspend fun read(path: String): Result<ByteArray> = runCatching {
        withContext(Dispatchers.IO) { resolve(path).readBytes() }
    }

    override suspend fun write(path: String, data: ByteArray): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val file = resolve(path)
            file.parentFile?.mkdirs()
            file.writeBytes(data)
        }
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val file = resolve(path)
            if (file.exists()) {
                file.delete()
            }
            Unit
        }
    }

    override suspend fun list(directory: String): Result<List<String>> = runCatching {
        withContext(Dispatchers.IO) {
            val dir = resolve(directory)
            dir.listFiles()?.map { it.name } ?: emptyList()
        }
    }

    override suspend fun exists(path: String): Result<Boolean> = runCatching {
        withContext(Dispatchers.IO) { resolve(path).exists() }
    }
}
