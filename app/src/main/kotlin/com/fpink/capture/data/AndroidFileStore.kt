package com.fpink.capture.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.fpink.core.storage.FileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AndroidFileStore(private val context: Context) : FileStore {
    companion object {
        private const val PENDING_SUFFIX = ".atomic-new"
        private val mutex = Mutex()
    }

    private fun resolve(path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "Storage paths must be relative" }
        require(path.split('/', '\\').none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid storage path"
        }
        require(!path.endsWith(PENDING_SUFFIX)) { "Reserved storage path" }
        val root = context.filesDir.canonicalFile
        return File(root, path).also {
            require(it.canonicalPath.startsWith(root.path + File.separator)) {
                "Storage path escapes private storage"
            }
        }
    }

    override suspend fun read(path: String): Result<ByteArray> = io {
        val file = resolve(path)
        discardPending(file)
        file.readBytes()
    }

    override suspend fun write(path: String, data: ByteArray): Result<Unit> = io {
        val file = resolve(path)
        val directory = requireNotNull(file.parentFile)
        createDirectories(directory)
        val pending = pendingFile(file)
        try {
            FileOutputStream(pending).use { stream ->
                stream.write(data)
                stream.fd.sync()
            }
            // Same-directory atomic rename; unsupported filesystems fail rather than fall
            // back to a non-atomic copy. Directory fsync also makes publication durable.
            Files.move(
                pending.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        } catch (error: Exception) {
            try {
                discardPending(file)
            } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override suspend fun delete(path: String): Result<Unit> = io {
        val file = resolve(path)
        discardPending(file)
        if (Files.deleteIfExists(file.toPath())) syncDirectory(requireNotNull(file.parentFile))
    }

    override suspend fun list(directory: String): Result<List<String>> = io {
        val dir = resolve(directory)
        if (!Files.exists(dir.toPath())) {
            // exists() alone also returns false for inaccessible paths.
            if (Files.notExists(dir.toPath())) return@io emptyList()
            throw IOException("Cannot access the storage directory")
        }
        val entries = dir.listFiles() ?: throw IOException("Cannot list the storage directory")
        entries.filter { it.name.endsWith(PENDING_SUFFIX) }.forEach {
            discardPending(File(dir, it.name.removeSuffix(PENDING_SUFFIX)))
        }
        entries.filterNot { it.name.endsWith(PENDING_SUFFIX) }.map { it.name }
    }

    override suspend fun exists(path: String): Result<Boolean> = io {
        val file = resolve(path)
        // A process killed before rename can leave a private write sidecar. Under the
        // store-wide lock no live writer owns it; recovery removes only this exact sidecar.
        discardPending(file)
        when {
            Files.exists(file.toPath()) -> true
            Files.notExists(file.toPath()) -> false
            else -> throw IOException("Cannot access the storage file")
        }
    }

    private fun pendingFile(file: File) = File(file.parentFile, file.name + PENDING_SUFFIX)

    private fun discardPending(file: File) {
        if (Files.deleteIfExists(pendingFile(file).toPath())) {
            syncDirectory(requireNotNull(file.parentFile))
        }
    }

    private fun createDirectories(directory: File) {
        if (directory.isDirectory) return
        val parent = requireNotNull(directory.parentFile)
        createDirectories(parent)
        if (!directory.mkdir() && !directory.isDirectory) {
            throw IOException("Cannot create the storage directory")
        }
        syncDirectory(parent)
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                throw IOException("Cannot synchronize a non-directory storage path")
            }
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private suspend fun <T> io(block: () -> T): Result<T> = try {
        withContext(Dispatchers.IO) {
            mutex.withLock { Result.success(block()) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
