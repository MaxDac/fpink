package com.fpink.capture.data

import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JobCancellationStoreTest {
    private val sourceId = "4d3c1a1f-5449-4113-a22b-6a93d0971885"

    @Test
    fun `secret-free cancellation persists independently of partial or full image cleanup`() = withFiles { root, source ->
        val synced = mutableListOf<File>()
        val directory = File(root, "cancellations")
        val store = JobCancellationStore(directory, { source }, { synced += it.canonicalFile })
        store.invalidate(sourceId)
        File(source, "prepared.png").delete()
        assertTrue(File(source, "selection").isFile)
        assertTrue(JobCancellationStore(directory, { source }, {}).isCancelled(sourceId))
        assertArrayEquals(byteArrayOf(1), File(directory, sourceId).readBytes())
        assertTrue(directory.canonicalFile in synced)
        assertTrue(root.canonicalFile in synced)
        source.deleteRecursively()
        assertTrue(JobCancellationStore(directory, { source }, {}).isCancelled(sourceId))
    }

    @Test
    fun `interrupted cancellation write fails closed after recreation`() = withFiles { root, source ->
        val directory = File(root, "cancellations").apply { mkdirs() }
        File(directory, "$sourceId.pending").writeBytes(byteArrayOf())
        assertTrue(JobCancellationStore(directory, { source }, {}).isCancelled(sourceId))
    }

    @Test
    fun `failed marker creation durably invalidates selection instead`() = withFiles { root, source ->
        val directory = File(root, "cancellations").apply { writeText("not a directory") }
        val synced = mutableListOf<File>()
        JobCancellationStore(directory, { source }, { synced += it.canonicalFile }).invalidate(sourceId)
        assertFalse(File(source, "selection").exists())
        assertTrue(source.canonicalFile in synced)
    }

    @Test
    fun `unreadable cancellation state is not treated as permission to restart`() = withFiles { root, source ->
        val directory = File(root, "cancellations").apply { writeText("not a directory") }
        val store = JobCancellationStore(directory, { source }, {})
        assertThrows(IOException::class.java) { store.isCancelled(sourceId) }
    }

    private fun withFiles(test: (File, File) -> Unit) {
        val root = File(File("build", "job-cancellation-tests"), UUID.randomUUID().toString())
        val source = File(File(root, "imports"), sourceId)
        check(source.mkdirs())
        try {
            File(source, "selection").writeText("provider=AZURE\nkeyFingerprint=fixture-fingerprint")
            File(source, "prepared.png").writeBytes(byteArrayOf(1))
            test(root, source)
        } finally {
            check(root.deleteRecursively())
        }
    }
}
