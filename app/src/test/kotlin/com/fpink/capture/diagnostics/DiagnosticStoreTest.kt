package com.fpink.capture.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticStoreTest {
    private val time = 1_800_000_000_000L

    @Test
    fun `startup removes expired future invalid and leftover artifacts`() = runTest {
        withFiles { logs, exports ->
            logs.mkdirs()
            exports.mkdirs()
            val boundary = event(time - DiagnosticStore.RETENTION_MILLIS)
            File(logs, "events.tsv").writeText(
                event(boundary.timestamp - 1).encode() + boundary.encode() +
                    event(time).encode() + event(time + 1).encode() +
                    "1800000000000\tSTARTUP\tINTERNAL\tfalse\tSECRET_CANARY\n",
            )
            File(logs, "fatal.tsv").writeText(
                event(boundary.timestamp - 1).copy(operation = DiagnosticOperation.FATAL, fatal = true).encode(),
            )
            File(logs, "orphan.tmp").writeText("SECRET_CANARY")
            File(exports, "diagnostics.txt").writeText("SECRET_CANARY")
            File(exports, "old-export.zip").writeText("SECRET_CANARY")
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                assertEquals(listOf(boundary, event(time)), store.snapshot())
                assertTrue(exports.listFiles().orEmpty().isEmpty())
                assertEquals(setOf("events.tsv"), logs.list()!!.toSet())
                assertTrue(allText(logs, exports).none { it.contains("SECRET_CANARY") })
            }
        }
    }

    @Test
    fun `write and export expire every retained artifact after seven days`() = runTest {
        withFiles { logs, exports ->
            var now = time
            DiagnosticStore(logs, exports, { now }, StandardTestDispatcher(testScheduler)).use { store ->
                store.report(DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)
                val initial = store.export()
                assertTrue(initial.readText().contains(event(time).encode()))
                withHandler(store) { handler ->
                    handler.uncaughtException(Thread.currentThread(), IllegalStateException("SECRET_CANARY"))
                }
                now += DiagnosticStore.RETENTION_MILLIS + 1
                store.report(DiagnosticOperation.CLEANUP, DiagnosticCategory.STORAGE)
                val retained = store.snapshot()
                assertEquals(listOf(event(now).copy(
                    operation = DiagnosticOperation.CLEANUP,
                    category = DiagnosticCategory.STORAGE,
                )), retained)
                assertFalse(initial.exists())
                assertFalse(File(logs, "fatal.tsv").exists())
                val exported = store.export().readText()
                assertFalse(exported.contains(time.toString()))
                assertTrue(exported.contains(retained.single().encode()))
            }
        }
    }

    @Test
    fun `precise total includes full export reserved fatal and unknown crash temp files`() = runTest {
        withFiles { logs, exports ->
            logs.mkdirs()
            val line = event(time).copy(
                operation = DiagnosticOperation.CANCEL_PERSISTENCE,
                category = DiagnosticCategory.UNSUPPORTED_DEVICE,
            ).encode()
            val count = (DiagnosticStore.MAX_LIVE_BYTES / line.length).toInt()
            File(logs, "events.tsv").writeText(line.repeat(count))
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                store.snapshot()
                store.report(DiagnosticOperation.STORAGE_SAVE, DiagnosticCategory.STORAGE)
                store.snapshot()
                withHandler(store) { handler ->
                    handler.uncaughtException(Thread.currentThread(), OutOfMemoryError("SECRET_CANARY"))
                }
                val snapshot = store.snapshot()
                val exported = store.export()
                assertEquals(snapshot, exported.readLines().mapNotNull(::decodeDiagnostic))
                assertTrue(File(logs, "events.tsv").length() <= DiagnosticStore.MAX_LIVE_BYTES)
                assertTrue(File(logs, "fatal.tsv").length() in 1..DiagnosticStore.MAX_FATAL_BYTES)
                assertTrue(exported.length() <= DiagnosticStore.MAX_EXPORT_BYTES)
                assertTrue(bytes(logs, exports) <= DiagnosticStore.MAX_TOTAL_BYTES)
                val initialBytes = bytes(logs, exports)
                assertEquals(
                    File(logs, "events.tsv").length() + File(logs, "fatal.tsv").length() + exported.length(),
                    initialBytes,
                )
                assertTrue(initialBytes > 4_000_000)
                RandomAccessFile(File(logs, "crash-backup.tmp"), "rw").use {
                    it.setLength(DiagnosticStore.MAX_TOTAL_BYTES)
                }
                RandomAccessFile(File(exports, "old-share.txt"), "rw").use {
                    it.setLength(DiagnosticStore.MAX_TOTAL_BYTES)
                }
                store.export()
                assertEquals(initialBytes, bytes(logs, exports))
            }
        }
    }

    @Test
    fun `oversized live fatal and export files are discarded without reading their payload`() = runTest {
        withFiles { logs, exports ->
            logs.mkdirs()
            exports.mkdirs()
            listOf(
                File(logs, "events.tsv") to DiagnosticStore.MAX_LIVE_BYTES,
                File(logs, "fatal.tsv") to DiagnosticStore.MAX_FATAL_BYTES,
                File(exports, "diagnostics.txt") to DiagnosticStore.MAX_EXPORT_BYTES,
            ).forEach { (file, limit) ->
                RandomAccessFile(file, "rw").use { it.setLength(limit + 1) }
            }
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                assertTrue(store.snapshot().isEmpty())
                assertEquals(0L, bytes(logs, exports))
                store.report(DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)
                assertEquals(1, store.snapshot().size)
                assertTrue(bytes(logs, exports) <= DiagnosticStore.MAX_TOTAL_BYTES)
            }
        }
    }

    @Test
    fun `startup preserves safe bounded logs while removing full stale shares and crash artifacts`() = runTest {
        withFiles { logs, exports ->
            logs.mkdirs()
            exports.mkdirs()
            val line = event(time).encode()
            val count = (DiagnosticStore.MAX_LIVE_BYTES / line.length).toInt()
            File(logs, "events.tsv").writeText(line.repeat(count))
            val fatal = event(time).copy(operation = DiagnosticOperation.FATAL, fatal = true)
            File(logs, "fatal.tsv").writeText(fatal.encode())
            RandomAccessFile(File(exports, "diagnostics.txt"), "rw").use {
                it.setLength(DiagnosticStore.MAX_EXPORT_BYTES)
            }
            RandomAccessFile(File(logs, "interrupted-crash.tmp"), "rw").use {
                it.setLength(DiagnosticStore.MAX_TOTAL_BYTES)
            }
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                val retained = store.snapshot()
                assertEquals(count + 1, retained.size)
                assertEquals(fatal, retained.last())
                assertEquals(
                    File(logs, "events.tsv").length() + File(logs, "fatal.tsv").length(),
                    bytes(logs, exports),
                )
                assertTrue(bytes(logs, exports) <= DiagnosticStore.MAX_LIVE_BYTES + DiagnosticStore.MAX_FATAL_BYTES)
                assertEquals(5_242_880L, DiagnosticStore.MAX_TOTAL_BYTES)
                assertTrue(
                    DiagnosticStore.MAX_LIVE_BYTES + DiagnosticStore.MAX_FATAL_BYTES +
                        DiagnosticStore.MAX_EXPORT_BYTES <= DiagnosticStore.MAX_TOTAL_BYTES,
                )
                assertTrue(exports.listFiles().orEmpty().isEmpty())
                assertFalse(File(logs, "interrupted-crash.tmp").exists())
                assertEquals(retained, store.export().readLines().mapNotNull(::decodeDiagnostic))
                assertTrue(bytes(logs, exports) <= DiagnosticStore.MAX_TOTAL_BYTES)
            }
        }
    }

    @Test
    fun `huge bursts use bounded queue without moving IO to reporting caller`() = runTest {
        withFiles { logs, exports ->
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                repeat(100_000) {
                    store.report(DiagnosticOperation.RECOGNITION, DiagnosticCategory.INTERNAL)
                }
                assertFalse(logs.exists())
                assertFalse(exports.exists())
                val events = store.snapshot()
                assertEquals(DiagnosticStore.QUEUE_CAPACITY, events.size)
                assertTrue(bytes(logs, exports) <= DiagnosticStore.MAX_TOTAL_BYTES)
                assertTrue(store.writeFailed.value)
            }
        }
    }

    @Test
    fun `clear invalidates a full old queue removes every artifact and continues reporting`() = runTest {
        withFiles { logs, exports ->
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                store.report(DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)
                store.export()
                withHandler(store) { handler ->
                    handler.uncaughtException(Thread.currentThread(), RuntimeException("SECRET_CANARY"))
                }
                repeat(DiagnosticStore.QUEUE_CAPACITY * 2) {
                    store.report(DiagnosticOperation.RECOGNITION, DiagnosticCategory.INTERNAL)
                }
                val clearing = async(start = CoroutineStart.UNDISPATCHED) { store.clear() }
                runCurrent()
                clearing.await()
                assertEquals(0L, bytes(logs, exports))
                assertTrue(logs.listFiles().orEmpty().isEmpty())
                assertTrue(exports.listFiles().orEmpty().isEmpty())
                assertTrue(store.snapshot().isEmpty())
                store.report(DiagnosticOperation.SETTINGS_SAVE, DiagnosticCategory.STORAGE)
                val retained = store.snapshot()
                assertEquals(1, retained.size)
                assertEquals(DiagnosticOperation.SETTINGS_SAVE, retained.single().operation)
                assertFalse(retained.single().fatal)
            }
        }
    }

    @Test
    fun `export admitted after clear cannot recreate old artifacts or mark a disk failure`() = runTest {
        withFiles { logs, exports ->
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                store.report(DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)
                store.snapshot()
                val admission = delayNextCommand(store)
                val oldExport = async(start = CoroutineStart.UNDISPATCHED) { store.export() }
                admission.paused.await()
                store.clear()
                assertEquals(0L, bytes(logs, exports))
                admission.resume.complete(Unit)
                runCurrent()
                assertTrue(oldExport.isCancelled)
                try {
                    oldExport.await()
                } catch (_: CancellationException) {
                    // A superseded command is cancellation, not unavailable storage.
                }
                assertFalse(store.writeFailed.value)
                assertTrue(exports.listFiles().orEmpty().isEmpty())
                assertEquals(0L, bytes(logs, exports))
                store.report(DiagnosticOperation.SETTINGS_SAVE, DiagnosticCategory.STORAGE)
                val current = store.snapshot()
                assertEquals(DiagnosticOperation.SETTINGS_SAVE, current.single().operation)
                assertEquals(current, store.export().readLines().mapNotNull(::decodeDiagnostic))
            }
        }
    }

    @Test
    fun `an older clear admitted late cannot erase records after a newer clear`() = runTest {
        withFiles { logs, exports ->
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                store.report(DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)
                store.snapshot()
                val admission = delayNextCommand(store)
                val olderClear = async(start = CoroutineStart.UNDISPATCHED) { store.clear() }
                admission.paused.await()
                store.clear()
                store.report(DiagnosticOperation.SETTINGS_SAVE, DiagnosticCategory.STORAGE)
                val freshFile = store.export()
                val fresh = freshFile.readText()
                admission.resume.complete(Unit)
                runCurrent()
                assertTrue(olderClear.isCancelled)
                try {
                    olderClear.await()
                } catch (_: CancellationException) {
                    // Only the most recent clear generation may delete files.
                }
                assertFalse(store.writeFailed.value)
                assertEquals(fresh, freshFile.readText())
                assertEquals(DiagnosticOperation.SETTINGS_SAVE, store.snapshot().single().operation)
            }
        }
    }

    @Test
    fun `each export revokes its predecessor path and clear cannot resurrect old URI paths`() = runTest {
        withFiles { logs, exports ->
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                store.report(DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)
                val first = store.export()
                assertTrue(first.exists())
                val second = store.export()
                assertNotEquals(first.absolutePath, second.absolutePath)
                assertFalse(first.exists())
                assertTrue(second.exists())
                assertEquals(listOf(second), exports.listFiles().orEmpty().toList())
                assertTrue(bytes(logs, exports) <= DiagnosticStore.MAX_TOTAL_BYTES)
                store.clear()
                assertFalse(first.exists())
                assertFalse(second.exists())
                store.report(DiagnosticOperation.SETTINGS_SAVE, DiagnosticCategory.STORAGE)
                val third = store.export()
                assertNotEquals(first.absolutePath, third.absolutePath)
                assertNotEquals(second.absolutePath, third.absolutePath)
                assertFalse(first.exists())
                assertFalse(second.exists())
                assertTrue(third.exists())
                assertEquals(listOf(third), exports.listFiles().orEmpty().toList())
                assertEquals(DiagnosticOperation.SETTINGS_SAVE, third.readLines()
                    .mapNotNull(::decodeDiagnostic).single().operation)
                assertTrue(bytes(logs, exports) <= DiagnosticStore.MAX_TOTAL_BYTES)
            }
        }
    }

    @Test
    fun `fatal is synchronous before startup worker and always delegates original throwable`() = runTest {
        withFiles { logs, exports ->
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                val error = RuntimeException("SECRET_CANARY")
                val thread = Thread.currentThread()
                var delegated = false
                withHandler(store, { actualThread, actualError ->
                    assertSame(thread, actualThread)
                    assertSame(error, actualError)
                    assertTrue(File(logs, "fatal.tsv").isFile)
                    delegated = true
                }) { handler ->
                    handler.uncaughtException(thread, error)
                }
                assertTrue(delegated)
                val fatal = store.snapshot().single()
                assertEquals(DiagnosticOperation.FATAL, fatal.operation)
                assertTrue(fatal.fatal)
                assertTrue(allText(logs, exports).none { it.contains("SECRET_CANARY") })
            }
        }
    }

    @Test
    fun `fatal delegates even when storage and clock fail`() = runTest {
        withFiles { logs, exports ->
            logs.writeText("not a directory")
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                var count = 0
                withHandler(store, { _, _ -> count++ }) { handler ->
                    handler.uncaughtException(Thread.currentThread(), AssertionError("SECRET_CANARY"))
                }
                assertEquals(1, count)
                assertTrue(store.writeFailed.value)
            }
            logs.delete()
            DiagnosticStore(logs, exports, { throw IllegalStateException("SECRET_CANARY") },
                StandardTestDispatcher(testScheduler),
            ).use { store ->
                var count = 0
                withHandler(store, { _, _ -> count++ }) { handler ->
                    handler.uncaughtException(Thread.currentThread(), RuntimeException())
                }
                assertEquals(1, count)
                assertTrue(store.writeFailed.value)
                assertFalse(exports.exists())
            }
        }
    }

    @Test
    fun `recorder failure cannot swallow or replace a failing prior fatal handler`() = runTest {
        withFiles { logs, exports ->
            logs.writeText("not a directory")
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                val originalError = OutOfMemoryError("SECRET_CANARY")
                val priorFailure = AssertionError("prior handler failure")
                val thread = Thread.currentThread()
                var delegated = 0
                withHandler(store, { actualThread, actualError ->
                    assertSame(thread, actualThread)
                    assertSame(originalError, actualError)
                    delegated++
                    throw priorFailure
                }) { handler ->
                    val thrown = assertThrows(AssertionError::class.java) {
                        handler.uncaughtException(thread, originalError)
                    }
                    assertSame(priorFailure, thrown)
                }
                assertEquals(1, delegated)
                assertTrue(store.writeFailed.value)
                assertFalse(exports.exists())
                assertEquals("not a directory", logs.readText())
            }
        }
    }

    @Test
    fun `fatal never waits for writer lock`() = runTest {
        withFiles { logs, exports ->
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                store.snapshot()
                val lock = DiagnosticStore::class.java.getDeclaredField("writerLock").let {
                    it.isAccessible = true
                    it.get(store) as ReentrantLock
                }
                val acquired = CountDownLatch(1)
                val release = CountDownLatch(1)
                val holder = Thread {
                    lock.lock()
                    try {
                        acquired.countDown()
                        release.await()
                    } finally {
                        lock.unlock()
                    }
                }
                holder.start()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    assertTrue(acquired.await(2, TimeUnit.SECONDS))
                    var delegated = false
                    withHandler(store, { _, _ -> delegated = true }) { handler ->
                        executor.submit {
                            handler.uncaughtException(Thread.currentThread(), RuntimeException())
                        }.get(2, TimeUnit.SECONDS)
                    }
                    assertTrue(delegated)
                    assertTrue(File(logs, "fatal.tsv").isFile)
                } finally {
                    release.countDown()
                    holder.join(2_000)
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `failure state is boolean only and normal writes recover after storage repair`() = runTest {
        withFiles { logs, exports ->
            logs.writeText("SECRET_CANARY")
            DiagnosticStore(logs, exports, { time }, StandardTestDispatcher(testScheduler)).use { store ->
                store.report(DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)
                runCurrent()
                assertTrue(store.writeFailed.value)
                logs.delete()
                store.report(DiagnosticOperation.STORAGE_SAVE, DiagnosticCategory.STORAGE)
                assertEquals(1, store.snapshot().size)
                assertTrue(store.writeFailed.value)
                store.clear()
                assertFalse(store.writeFailed.value)
            }
        }
    }

    private fun event(timestamp: Long): DiagnosticEvent =
        DiagnosticEvent(timestamp, DiagnosticOperation.STARTUP, DiagnosticCategory.INTERNAL)

    private suspend fun withFiles(block: suspend (File, File) -> Unit) {
        val root = File("build\\diagnostic-tests", UUID.randomUUID().toString())
        check(root.mkdirs())
        try {
            block(File(root, "logs"), File(root, "diagnostics-share"))
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun bytes(vararg roots: File): Long =
        roots.sumOf { root -> root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    private fun allText(vararg roots: File): List<String> =
        roots.flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.readText() }.toList() }

    private class CommandAdmission(
        val paused: CompletableDeferred<Unit> = CompletableDeferred(),
        val resume: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun delayNextCommand(store: DiagnosticStore): CommandAdmission {
        val admission = CommandAdmission()
        val first = AtomicBoolean(true)
        val field = DiagnosticStore::class.java.getDeclaredField("queue").apply { isAccessible = true }
        val queue = field.get(store) as Channel<Any>
        field.set(store, object : Channel<Any> by queue {
            override suspend fun send(element: Any) {
                if (first.compareAndSet(true, false)) {
                    admission.paused.complete(Unit)
                    admission.resume.await()
                }
                queue.send(element)
            }
        })
        return admission
    }

    private fun withHandler(
        store: DiagnosticStore,
        previous: (Thread, Throwable) -> Unit = { _, _ -> },
        test: (Thread.UncaughtExceptionHandler) -> Unit,
    ) {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previous)
        try {
            store.installCrashHandler()
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            store.installCrashHandler()
            assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
            test(handler)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
