package com.fpink.capture.diagnostics

import java.net.URLDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticDraftBuilderTest {
    @Test
    fun `explicit distribution routes to static repositories including empty drafts`() {
        val foss = DiagnosticDraftBuilder.build(emptyList(), full = false)
        val full = DiagnosticDraftBuilder.build(emptyList(), full = true)
        assertTrue(foss.url.startsWith("https://github.com/MaxDac/fpink/issues/new?"))
        assertTrue(full.url.startsWith("https://github.com/MaxDac/fpink-private/issues/new?"))
        listOf(foss, full).forEach {
            assertTrue(it.body.contains("No diagnostic events included."))
            assertEquals(0, it.omittedCount)
            assertTrue(it.url.length <= 2_000)
            assertEquals(it.body, decodeBody(it.url))
        }
    }

    @Test
    fun `complete encoded URL fits cap with chronological whole newest events and honest omissions`() {
        val events = (1L..500).map {
            DiagnosticEvent(it, DiagnosticOperation.CANCEL_PERSISTENCE, DiagnosticCategory.UNSUPPORTED_DEVICE, it % 2 == 0L)
        }
        listOf(false, true).forEach { full ->
            val draft = DiagnosticDraftBuilder.build(events.reversed(), full)
            assertTrue(draft.url.length <= DiagnosticDraftBuilder.MAX_URL_LENGTH)
            assertTrue(draft.url.all { it.code < 128 })
            assertEquals(draft.body, decodeBody(draft.url))
            val selected = draft.body.lineSequence().mapNotNull(::decodeDiagnostic).toList()
            assertTrue(selected.isNotEmpty())
            assertTrue(selected.size < events.size)
            assertEquals(events.takeLast(selected.size), selected)
            assertEquals(events.size - selected.size, draft.omittedCount)
            assertTrue(draft.body.contains("${draft.omittedCount} older event(s) omitted"))
            assertTrue(draft.body.contains("complete retained log"))
            assertFalse(draft.body.contains("SECRET_CANARY"))
            assertTrue(draft.url.contains("title=FPInk+diagnostic+report&body="))
        }
    }

    @Test
    fun `small draft includes every event including fatal without claiming omissions`() {
        val events = listOf(
            DiagnosticEvent(2, DiagnosticOperation.FATAL, DiagnosticCategory.INTERNAL, true),
            DiagnosticEvent(1, DiagnosticOperation.STARTUP, DiagnosticCategory.CONFIGURATION),
        )
        val draft = DiagnosticDraftBuilder.build(events, false)
        assertEquals(events.sortedBy { it.timestamp }, draft.body.lineSequence().mapNotNull(::decodeDiagnostic).toList())
        assertEquals(0, draft.omittedCount)
        assertFalse(draft.body.contains("omitted"))
        assertTrue(draft.url.contains("%09"))
        assertTrue(draft.url.contains("%0A"))
    }

    private fun decodeBody(url: String): String =
        URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
}
