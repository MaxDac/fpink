package com.fpink.recognition.strategies

import com.fpink.core.ai.DetectedLine
import com.fpink.core.ai.LineCrop
import com.fpink.core.ai.PixelPoint
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionStrategyId
import com.fpink.core.ai.RecognizedLine
import com.fpink.core.ai.TextDetector
import com.fpink.core.ai.TextLineRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DetectThenRecognizeStrategyTest {
    private fun line(x: Float, y: Float, w: Float = 40f, h: Float = 10f) = DetectedLine(
        listOf(PixelPoint(x, y), PixelPoint(x + w, y), PixelPoint(x + w, y + h), PixelPoint(x, y + h)),
        0.9f,
    )

    private val image = PreparedImage(byteArrayOf(1), "image/png", 100, 50, IntArray(5000) { -1 })

    private class FakeDetector(
        val lines: List<DetectedLine>,
        val ready: Result<Unit> = Result.success(Unit),
    ) : TextDetector {
        var closed = 0
        override val modelVersion = "det"
        override suspend fun readiness() = ready
        override suspend fun detect(image: PreparedImage) = lines
        override fun close() { closed++ }
    }

    private class FakeRecognizer(val results: ArrayDeque<RecognizedLine?>, val onRecognize: () -> Unit = {}) : TextLineRecognizer {
        val crops = mutableListOf<LineCrop>()
        var closed = 0
        override val modelVersion = "rec"
        override suspend fun readiness() = Result.success(Unit)
        override suspend fun recognize(line: LineCrop): RecognizedLine? {
            onRecognize()
            crops += line
            return results.removeFirst()
        }
        override fun close() { closed++ }
    }

    @Test
    fun `recognizes lines in reading order and drops weak or blank lines`() = runTest {
        val detector = FakeDetector(listOf(line(50f, 30f), line(5f, 31f), line(5f, 5f)))
        val recognizer = FakeRecognizer(
            ArrayDeque(listOf(RecognizedLine("top", 0.9f), RecognizedLine("left", 0.4f), RecognizedLine("right", 0.8f))),
        )
        val strategy = DetectThenRecognizeStrategy(RecognitionStrategyId.CURSIVE, detector, recognizer)

        val document = strategy.recognize(image).getOrThrow()

        assertEquals(listOf("top", "right"), document.regions.map { it.text })
        assertEquals(RecognitionStrategyId.CURSIVE, document.strategy)
        assertEquals("det+rec", document.modelVersion)
        assertEquals(0.05f, document.regions[0].polygon[0].x, 1e-6f)
        assertEquals(0.1f, document.regions[0].polygon[0].y, 1e-6f)
        assertTrue(document.regions.all { region -> region.polygon.all { it.x in 0f..1f && it.y in 0f..1f } })
        assertEquals(3, recognizer.crops.size)
        assertEquals(1, detector.closed)
        assertEquals(1, recognizer.closed)
    }

    @Test
    fun `blank page yields an empty document`() = runTest {
        val strategy = DetectThenRecognizeStrategy(
            RecognitionStrategyId.PRINTED, FakeDetector(emptyList()), FakeRecognizer(ArrayDeque()),
        )
        assertEquals(emptyList<Any>(), strategy.recognize(image).getOrThrow().regions)
    }

    @Test
    fun `readiness failures and oversize pages are reported as recognition errors`() = runTest {
        val unready = DetectThenRecognizeStrategy(
            RecognitionStrategyId.PRINTED,
            FakeDetector(emptyList(), Result.failure(RecognitionError.UnsupportedDevice("nope"))),
            FakeRecognizer(ArrayDeque()),
        )
        assertTrue(unready.recognize(image).exceptionOrNull() is RecognitionError.UnsupportedDevice)

        val tiny = PreparedImage(byteArrayOf(1), "image/png", 8, 8, IntArray(64))
        val strategy = DetectThenRecognizeStrategy(RecognitionStrategyId.PRINTED, FakeDetector(emptyList()), FakeRecognizer(ArrayDeque()))
        assertTrue(strategy.recognize(tiny).exceptionOrNull() is RecognitionError.UnsupportedDevice)
    }

    @Test
    fun `cancellation propagates and still releases models`() = runTest {
        val detector = FakeDetector(listOf(line(5f, 5f), line(5f, 30f)))
        val recognizer = FakeRecognizer(ArrayDeque(listOf(RecognizedLine("a", 1f), null))) {
            throw CancellationException("stop")
        }
        val strategy = DetectThenRecognizeStrategy(RecognitionStrategyId.CURSIVE, detector, recognizer)
        assertThrows<CancellationException> { strategy.recognize(image) }
        assertEquals(1, detector.closed)
        assertEquals(1, recognizer.closed)
    }

    @Test
    fun `built-in labels cover printed and cursive only`() {
        assertEquals("Printed", BuiltInStrategies.label(RecognitionStrategyId.PRINTED))
        assertEquals("Cursive", BuiltInStrategies.label(RecognitionStrategyId.CURSIVE))
        assertEquals(null, BuiltInStrategies.label(RecognitionStrategyId("azure")))
    }
}