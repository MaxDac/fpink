package com.fpink.capture.diagnostics

import com.fpink.core.ai.RecognitionError
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** The complete persisted schema: never add free-form strings, identifiers, or stack traces. */
data class DiagnosticEvent(
    val timestamp: Long,
    val operation: DiagnosticOperation,
    val category: DiagnosticCategory,
    val fatal: Boolean = false,
) {
    init {
        require(timestamp >= 0)
    }

    internal fun encode(): String = "$timestamp\t${operation.name}\t${category.name}\t$fatal\n"
}

enum class DiagnosticOperation {
    STARTUP, SETTINGS_LOAD, SETTINGS_SAVE, SETTINGS_PROBE, RECOGNITION,
    STORAGE_LOAD, STORAGE_SAVE, STORAGE_DELETE, IMPORT, CAMERA, EXPORT,
    CLEANUP, CANCEL_PERSISTENCE, SHARE, FATAL,
}

enum class DiagnosticCategory {
    CONFIGURATION, AUTHENTICATION, NETWORK, RATE_LIMITED, INVALID_RESPONSE,
    MODEL_UNAVAILABLE, UNSUPPORTED_DEVICE, STORAGE, PERMISSION, INTERNAL,
}

interface DiagnosticReporter {
    fun report(operation: DiagnosticOperation, category: DiagnosticCategory)
}

object NoOpDiagnosticReporter : DiagnosticReporter {
    override fun report(operation: DiagnosticOperation, category: DiagnosticCategory) = Unit
}

/**
 * Only the exception's type is inspected, never its message, cause, stack, or toString().
 * Callers must rethrow cancellation before classifying/reporting it.
 */
fun classifyDiagnostic(error: Throwable): DiagnosticCategory = when (error) {
    is RecognitionError.Configuration -> DiagnosticCategory.CONFIGURATION
    is RecognitionError.Authentication -> DiagnosticCategory.AUTHENTICATION
    is RecognitionError.Network -> DiagnosticCategory.NETWORK
    is RecognitionError.RateLimited -> DiagnosticCategory.RATE_LIMITED
    is RecognitionError.MalformedResponse -> DiagnosticCategory.INVALID_RESPONSE
    is RecognitionError.ModelUnavailable -> DiagnosticCategory.MODEL_UNAVAILABLE
    is RecognitionError.UnsupportedDevice -> DiagnosticCategory.UNSUPPORTED_DEVICE
    is SecurityException -> DiagnosticCategory.PERMISSION
    is SocketException, is UnknownHostException, is java.net.SocketTimeoutException,
    is TimeoutException -> DiagnosticCategory.NETWORK
    is IOException -> DiagnosticCategory.STORAGE
    else -> DiagnosticCategory.INTERNAL
}

internal fun decodeDiagnostic(line: String): DiagnosticEvent? {
    // Canonical re-encoding also rejects extra fields, whitespace, and arbitrary payloads.
    val fields = line.split('\t')
    if (fields.size != 4) return null
    return try {
        val timestamp = fields[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val fatal = when (fields[3]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        DiagnosticEvent(
            timestamp,
            DiagnosticOperation.valueOf(fields[1]),
            DiagnosticCategory.valueOf(fields[2]),
            fatal,
        ).takeIf { it.encode() == "$line\n" }
    } catch (_: IllegalArgumentException) {
        null
    }
}
