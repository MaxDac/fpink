package com.fpink.capture.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureCompletionNavigationTest {
    @Test fun `only newly created nonempty batches require review`() {
        assertTrue(shouldReviewCapture(count = 1, previouslySaved = false))
        assertTrue(shouldReviewCapture(count = 3, previouslySaved = false))
        assertFalse(shouldReviewCapture(count = 0, previouslySaved = false))
        assertFalse(shouldReviewCapture(count = 2, previouslySaved = true))
        assertFalse(shouldReviewCapture(count = 0, previouslySaved = true))
    }

    @Test fun `completion messages preserve restored empty and cleanup outcomes`() {
        assertEquals("1 note created.", captureCompletionMessage(1, false, false))
        assertEquals("2 notes already saved.", captureCompletionMessage(2, false, true))
        assertEquals(
            "This image was already processed; its notes have been deleted. No notes were recreated.",
            captureCompletionMessage(0, false, true),
        )
        assertEquals(
            "2 notes created. The staged import could not be cleaned up; saved notes are safe.",
            captureCompletionMessage(2, true, false),
        )
    }
}
