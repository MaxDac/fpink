package com.fpink.capture.diagnostics

import com.fpink.core.ai.RecognitionError
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticPrivacyTest {
    @Test
    fun `public reporter and persisted schema cannot accept arbitrary content`() {
        val report = DiagnosticReporter::class.java.declaredMethods.single()
        assertEquals(listOf(DiagnosticOperation::class.java, DiagnosticCategory::class.java), report.parameterTypes.toList())
        assertEquals(
            setOf(java.lang.Long.TYPE, DiagnosticOperation::class.java, DiagnosticCategory::class.java, java.lang.Boolean.TYPE),
            DiagnosticEvent::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.type }.toSet(),
        )
        val canaries = listOf(
            "SECRET_API_KEY_123", "recognized secret handwriting", "C:\\private\\scan.png",
            "https://private.invalid/recognize?id=secret", "note-user-id",
            "at com.private.Secret.read(Secret.kt:123)",
        )
        canaries.forEach {
            assertNull(decodeDiagnostic("123\tSTARTUP\tINTERNAL\tfalse\t$it"))
            assertNull(decodeDiagnostic("123\t$it\tINTERNAL\tfalse"))
            assertNull(decodeDiagnostic("123\tSTARTUP\t$it\tfalse"))
        }
        assertNull(decodeDiagnostic("-1\tSTARTUP\tINTERNAL\tfalse"))
        assertNull(decodeDiagnostic("001\tSTARTUP\tINTERNAL\tfalse"))
        assertNull(decodeDiagnostic("1\tSTARTUP\tINTERNAL\tTRUE"))
        assertNull(decodeDiagnostic("1\tSTARTUP\tINTERNAL\tfalse\r"))
    }

    @Test
    fun `classifier uses type not message causes stacks or toString`() {
        val poisoned = object : IOException() {
            override val message: String get() = error("message accessed")
            override val cause: Throwable get() = error("cause accessed")
            override fun toString(): String = error("toString accessed")
        }
        assertEquals(DiagnosticCategory.STORAGE, classifyDiagnostic(poisoned))
        val cases = listOf(
            RecognitionError.Configuration("SECRET") to DiagnosticCategory.CONFIGURATION,
            RecognitionError.Authentication("SECRET") to DiagnosticCategory.AUTHENTICATION,
            RecognitionError.Network("SECRET", poisoned) to DiagnosticCategory.NETWORK,
            RecognitionError.RateLimited("SECRET") to DiagnosticCategory.RATE_LIMITED,
            RecognitionError.MalformedResponse("SECRET", poisoned) to DiagnosticCategory.INVALID_RESPONSE,
            RecognitionError.ModelUnavailable("SECRET", poisoned) to DiagnosticCategory.MODEL_UNAVAILABLE,
            RecognitionError.UnsupportedDevice("SECRET") to DiagnosticCategory.UNSUPPORTED_DEVICE,
            SecurityException("SECRET") to DiagnosticCategory.PERMISSION,
            SocketTimeoutException("SECRET") to DiagnosticCategory.NETWORK,
            UnknownHostException("SECRET") to DiagnosticCategory.NETWORK,
            IOException("SECRET") to DiagnosticCategory.STORAGE,
            IllegalStateException("SECRET") to DiagnosticCategory.INTERNAL,
        )
        cases.forEach { (error, category) ->
            error.stackTrace = arrayOf(StackTraceElement("SECRET_CLASS", "SECRET_METHOD", "SECRET_FILE", 123))
            assertEquals(category, classifyDiagnostic(error))
            assertTrue(DiagnosticEvent(1, DiagnosticOperation.RECOGNITION, classifyDiagnostic(error))
                .encode().contains(category.name))
        }
    }
}
