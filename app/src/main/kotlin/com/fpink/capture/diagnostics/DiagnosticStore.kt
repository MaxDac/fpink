package com.fpink.capture.diagnostics

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Default-on local, best-effort diagnostics. Give this store exclusive ownership of a
 * dedicated directory under Context.noBackupFilesDir and cacheDir/diagnostics-share.
 * One store per pair of directories/process; multi-process writers are not supported.
 *
 * Normal work uses one bounded queue and an IO dispatcher (drop newest when saturated).
 * Clear invalidates all older queued events before waiting for disk IO. There are no
 * temporary/backup files: interrupted writes can lose events, never recover raw errors.
 * Each export gets a new unguessable path; old URI paths are deleted and never reused.
 * Already-open descriptors and copies made by a share recipient cannot be revoked.
 *
 * Fatal recording uses a separate bounded slot and never waits for the normal writer.
 * It may be dropped if another fatal/clear owns its slot, retention maintenance cannot
 * run immediately, storage fails, or the process is killed. Native crashes, ANRs, power
 * loss, and hard kills are not intercepted. The prior Java handler is always called.
 */
class DiagnosticStore(
    private val logsDirectory: File,
    private val exportDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiagnosticReporter, Closeable {
    companion object {
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_TOTAL_BYTES = 5_242_880L
        const val MAX_LIVE_BYTES = 2_097_152L
        const val MAX_FATAL_BYTES = 256L
        const val MAX_EXPORT_BYTES = MAX_LIVE_BYTES + MAX_FATAL_BYTES + 128
        const val QUEUE_CAPACITY = 256
        private const val EXPORT_HEADER = "FPInk sanitized diagnostics: timestamp, operation, category, fatal; no stacks.\n"
    }

    init {
        require(exportDirectory.name == "diagnostics-share")
        val logs = logsDirectory.toPath().toAbsolutePath().normalize()
        val exports = exportDirectory.toPath().toAbsolutePath().normalize()
        require(!logs.startsWith(exports) && !exports.startsWith(logs))
        check(MAX_LIVE_BYTES + MAX_FATAL_BYTES + MAX_EXPORT_BYTES <= MAX_TOTAL_BYTES)
    }

    private val journal = File(logsDirectory, "events.tsv")
    private val fatalFile = File(logsDirectory, "fatal.tsv")
    private val writerLock = ReentrantLock()
    private val fatalLock = ReentrantLock()
    private val epoch = AtomicLong()
    private val queue = Channel<Work>(QUEUE_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = ArrayDeque<DiagnosticEvent>()
    private var liveBytes = 0L
    private var initialized = false
    private var currentExport: File? = null
    private var exportOldestTimestamp: Long? = null
    @Volatile private var maintenanceUntil = Long.MIN_VALUE
    @Volatile private var closed = false
    private var installedHandler: Thread.UncaughtExceptionHandler? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val mutableWriteFailed = MutableStateFlow(false)

    /**
     * Some recording/IO failed or the queue overflowed since startup/last successful
     * clear. Sticky so a later successful write cannot conceal omissions; no details.
     */
    val writeFailed: StateFlow<Boolean> = mutableWriteFailed.asStateFlow()

    private sealed interface Work {
        data class Event(val epoch: Long, val event: DiagnosticEvent) : Work
        class Command(val epoch: Long, val run: (Long) -> Unit) : Work
    }

    init {
        scope.launch {
            attempt { writerLock.withLock { maintain(now()) } }
            for (work in queue) {
                when (work) {
                    is Work.Event -> attempt {
                        writerLock.withLock {
                            if (work.epoch == epoch.get()) append(work.event, now())
                        }
                    }
                    is Work.Command -> work.run(work.epoch)
                }
            }
        }
    }

    override fun report(operation: DiagnosticOperation, category: DiagnosticCategory) {
        if (closed) return
        // Capture the generation before any clock work: clear can invalidate this call too.
        val generation = epoch.get()
        try {
            if (queue.trySend(Work.Event(generation, DiagnosticEvent(now(), operation, category))).isFailure) {
                mutableWriteFailed.value = true
            }
        } catch (_: Exception) {
            mutableWriteFailed.value = true
        }
    }

    /**
     * A queue barrier: includes all accepted report calls preceding this request.
     * Snapshot/export/clear requests superseded by a later clear are cancelled.
     */
    suspend fun snapshot(): List<DiagnosticEvent> = submit {
        val time = now()
        maintain(time)
        retained(time)
    }

    /** One complete sanitized copy, never a partial URL-sized subset. */
    suspend fun export(): File = submit {
        val time = now()
        maintain(time)
        val snapshot = retained(time)
        val text = EXPORT_HEADER + snapshot.joinToString("") { it.encode() }
        val bytes = text.toByteArray(Charsets.US_ASCII)
        check(bytes.size.toLong() <= MAX_EXPORT_BYTES)
        // Remove the previous artifact before allocating its replacement, both for the
        // disk reserve and so an old URI grant cannot read any future export.
        currentExport?.let(::remove)
        currentExport = null
        exportOldestTimestamp = null
        val artifact = File(exportDirectory, "${UUID.randomUUID()}.txt")
        if (!artifact.createNewFile()) throw IOException("Diagnostics export unavailable")
        currentExport = artifact
        FileOutputStream(artifact, false).use { it.write(bytes) }
        exportOldestTimestamp = snapshot.firstOrNull()?.timestamp ?: time
        updateDeadline(time)
        artifact
    }

    /** Removes every artifact, emits no event, and leaves subsequent reporting enabled. */
    suspend fun clear() {
        val generation = epoch.incrementAndGet()
        submit(generation) {
            fatalLock.withLock {
                maintenanceUntil = Long.MIN_VALUE
                clearDirectory(logsDirectory)
                clearDirectory(exportDirectory)
                events.clear()
                liveBytes = 0
                currentExport = null
                exportOldestTimestamp = null
                initialized = false
                mutableWriteFailed.value = false
            }
        }
    }

    @Synchronized
    fun installCrashHandler() {
        check(!closed)
        if (Thread.getDefaultUncaughtExceptionHandler() === installedHandler && installedHandler != null) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, error ->
            try {
                recordFatal(classifyDiagnostic(error))
            } catch (_: Throwable) {
                // Even OOM/IO/clock/recorder failures must not bypass Android's handler.
                mutableWriteFailed.value = true
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
        previousHandler = previous
        installedHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    @Synchronized
    override fun close() {
        closed = true
        queue.close()
        scope.cancel()
        if (installedHandler != null && Thread.getDefaultUncaughtExceptionHandler() === installedHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun recordFatal(category: DiagnosticCategory) {
        if (closed) return
        val generation = epoch.get()
        val time = now()
        // Startup/age cleanup is synchronous if available, but never wait on the writer.
        if (writerLock.tryLock()) {
            try {
                if (fatalLock.tryLock()) {
                    try {
                        maintain(time)
                    } finally {
                        fatalLock.unlock()
                    }
                }
            } finally {
                writerLock.unlock()
            }
        }
        // Never create a new artifact unless retention/budget maintenance is current.
        if (time > maintenanceUntil || !fatalLock.tryLock()) {
            mutableWriteFailed.value = true
            return
        }
        try {
            if (generation != epoch.get() || closed) return
            val bytes = DiagnosticEvent(time, DiagnosticOperation.FATAL, category, true)
                .encode().toByteArray(Charsets.US_ASCII)
            check(bytes.size.toLong() <= MAX_FATAL_BYTES)
            FileOutputStream(fatalFile, false).use {
                it.write(bytes)
                it.fd.sync()
            }
            maintenanceUntil = minOf(maintenanceUntil, expiresAt(time))
        } finally {
            fatalLock.unlock()
        }
    }

    private suspend fun <T> submit(generation: Long = epoch.get(), action: () -> T): T {
        check(!closed)
        val result = CompletableDeferred<T>()
        queue.send(Work.Command(generation) { admittedGeneration ->
            try {
                val value = writerLock.withLock {
                    if (admittedGeneration != epoch.get()) {
                        throw CancellationException("Diagnostics operation superseded by clear")
                    }
                    action()
                }
                result.complete(value)
            } catch (cancelled: CancellationException) {
                result.completeExceptionally(cancelled)
            } catch (error: Throwable) {
                initialized = false
                mutableWriteFailed.value = true
                result.completeExceptionally(error)
            }
        })
        return result.await()
    }

    private inline fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            initialized = false
            mutableWriteFailed.value = true
        }
    }

    private fun now(): Long = clock().coerceAtLeast(0)

    private fun append(event: DiagnosticEvent, time: Long) {
        maintain(time)
        if (!isRetained(event, time)) return
        val encoded = event.encode().toByteArray(Charsets.US_ASCII)
        var evicted = false
        while (liveBytes + encoded.size > MAX_LIVE_BYTES && events.isNotEmpty()) {
            liveBytes -= events.removeFirst().encode().length
            evicted = true
        }
        if (evicted) rewriteJournal()
        FileOutputStream(journal, true).use { it.write(encoded) }
        events.addLast(event)
        liveBytes += encoded.size
        updateDeadline(time)
    }

    private fun maintain(time: Long) {
        ensureDirectory(logsDirectory)
        ensureDirectory(exportDirectory)
        removeUnexpected(logsDirectory, setOf(journal.name, fatalFile.name))
        removeUnexpected(exportDirectory, setOfNotNull(currentExport?.name))
        if (!initialized) {
            events.clear()
            liveBytes = 0
            readEvents(journal, MAX_LIVE_BYTES, time).forEach {
                events.addLast(it)
                liveBytes += it.encode().length
            }
            rewriteJournal()
            // Old or interrupted share copies are never trusted on process startup.
            currentExport?.let(::remove)
            currentExport = null
            exportOldestTimestamp = null
            initialized = true
        } else {
            val keep = events.filter { isRetained(it, time) }
            if (keep.size != events.size || journal.length() > MAX_LIVE_BYTES) {
                events.clear()
                events.addAll(keep)
                rewriteJournal()
            }
        }
        fatalLock.withLock {
            val fatal = readEvents(fatalFile, MAX_FATAL_BYTES, time)
                .singleOrNull()?.takeIf { it.fatal && it.operation == DiagnosticOperation.FATAL }
            if (fatal == null) remove(fatalFile)
            else if (fatalFile.readText(Charsets.US_ASCII) != fatal.encode()) remove(fatalFile)
        }
        val oldest = exportOldestTimestamp
        val artifact = currentExport
        if (artifact != null && (!artifact.exists() || oldest == null || oldest < cutoff(time) ||
                oldest > time || artifact.length() > MAX_EXPORT_BYTES)) {
            remove(artifact)
            currentExport = null
            exportOldestTimestamp = null
        }
        updateDeadline(time)
    }

    private fun retained(time: Long): List<DiagnosticEvent> = fatalLock.withLock {
        (events.toList() + readEvents(fatalFile, MAX_FATAL_BYTES, time))
            .sortedBy { it.timestamp }
    }

    private fun updateDeadline(time: Long) {
        val oldest = minOf(
            events.minOfOrNull { it.timestamp } ?: time,
            fatalLock.withLock {
                readEvents(fatalFile, MAX_FATAL_BYTES, time).minOfOrNull { it.timestamp } ?: time
            },
            exportOldestTimestamp ?: time,
        )
        maintenanceUntil = expiresAt(oldest)
    }

    private fun rewriteJournal() {
        val text = events.joinToString("") { it.encode() }
        val bytes = text.toByteArray(Charsets.US_ASCII)
        check(bytes.size.toLong() <= MAX_LIVE_BYTES)
        FileOutputStream(journal, false).use { it.write(bytes) }
        liveBytes = bytes.size.toLong()
    }

    private fun readEvents(file: File, limit: Long, time: Long): List<DiagnosticEvent> {
        if (!file.exists()) return emptyList()
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > limit) {
            remove(file)
            return emptyList()
        }
        return file.readText(Charsets.US_ASCII).lineSequence()
            .mapNotNull(::decodeDiagnostic)
            .filter { isRetained(it, time) }
            .toList()
    }

    private fun cutoff(time: Long): Long = (time - RETENTION_MILLIS).coerceAtLeast(0)
    private fun isRetained(event: DiagnosticEvent, time: Long): Boolean =
        event.timestamp in cutoff(time)..time

    private fun expiresAt(time: Long): Long =
        if (time > Long.MAX_VALUE - RETENTION_MILLIS) Long.MAX_VALUE else time + RETENTION_MILLIS

    private fun ensureDirectory(directory: File) {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdirs())) {
            throw IOException("Diagnostics storage unavailable")
        }
    }

    private fun removeUnexpected(directory: File, allowed: Set<String>) {
        val children = directory.listFiles() ?: throw IOException("Diagnostics storage unavailable")
        children.filter { it.name !in allowed }.forEach(::remove)
        children.filter { it.name in allowed && (Files.isSymbolicLink(it.toPath()) || !it.isFile) }
            .forEach(::remove)
    }

    private fun clearDirectory(directory: File) {
        if (Files.isSymbolicLink(directory.toPath())) {
            remove(directory)
        } else if (directory.exists()) {
            if (!directory.isDirectory) throw IOException("Diagnostics storage unavailable")
            val children = directory.listFiles() ?: throw IOException("Diagnostics storage unavailable")
            children.forEach(::remove)
        }
    }

    private fun remove(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        // walkFileTree does not follow symlinks, including links inside unknown artifacts.
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
