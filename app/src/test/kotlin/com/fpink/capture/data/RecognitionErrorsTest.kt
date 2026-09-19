package com.fpink.capture.data

import com.fpink.core.ai.RecognitionError
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecognitionErrorsTest {
    @Test
    fun `authentication and unavailable native models cannot be retried blindly`() {
        assertFalse(RecognitionError.Authentication("service details").toJobFailure().retryable)
        assertFalse(RecognitionError.ModelUnavailable("native details").toJobFailure().retryable)
        assertFalse(RecognitionError.UnsupportedDevice("ABI details").toJobFailure().retryable)
        assertFalse(RecognitionError.Configuration("Update settings").toJobFailure().retryable)
    }

    @Test
    fun `transient errors permit same job retry without exposing service internals`() {
        val error = RecognitionError.Network("Do not expose service internals").toJobFailure()
        assertTrue(error.retryable)
        assertFalse(error.message.contains("service internals"))
        assertTrue(RecognitionError.RateLimited("details").toJobFailure().retryable)
    }
}
