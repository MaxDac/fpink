package com.fpink.capture.diagnostics

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class DiagnosticDraft(val body: String, val url: String, val omittedCount: Int)

/**
 * A reviewable, sanitized draft, not a network operation. Routing depends only on the
 * explicit distribution boolean; no user-controlled repository, URL, title, or metadata.
 */
object DiagnosticDraftBuilder {
    const val MAX_URL_LENGTH = 2_000
    private const val TITLE = "FPInk diagnostic report"
    private const val INTRO =
        "Please describe the problem before submitting.\n\n" +
            "Sanitized local diagnostics (timestamps, fixed operation/category, fatal only; no stacks):\n"

    fun build(events: List<DiagnosticEvent>, full: Boolean): DiagnosticDraft {
        val repository = if (full) "fpink-private" else "fpink"
        val prefix = "https://github.com/MaxDac/$repository/issues/new?title=${encode(TITLE)}&body="
        val chronological = events.sortedBy { it.timestamp }
        var selected = emptyList<DiagnosticEvent>()
        var result = draft(prefix, selected, events.size)
        // Select whole newest events, but display them in chronological order. Once the
        // next newest event cannot fit, older events are omitted too (a contiguous suffix).
        for (index in chronological.indices.reversed()) {
            val candidate = listOf(chronological[index]) + selected
            val next = draft(prefix, candidate, chronological.size - candidate.size)
            if (next.url.length > MAX_URL_LENGTH) break
            selected = candidate
            result = next
        }
        check(result.url.length <= MAX_URL_LENGTH && result.url.all { it.code < 128 })
        return result
    }

    private fun draft(prefix: String, events: List<DiagnosticEvent>, omitted: Int): DiagnosticDraft {
        val body = buildString {
            append(INTRO)
            append("```text\n")
            events.forEach { append(it.encode()) }
            append("```\n")
            if (events.isEmpty()) append("No diagnostic events included.\n")
            if (omitted > 0) append("$omitted older event(s) omitted to fit the report URL.\n")
            append("Export the local diagnostics file separately for the complete retained log.\n")
        }
        return DiagnosticDraft(body, prefix + encode(body), omitted)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
