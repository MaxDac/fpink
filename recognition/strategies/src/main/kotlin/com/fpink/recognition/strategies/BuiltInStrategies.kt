package com.fpink.recognition.strategies

import android.content.Context
import com.fpink.core.ai.RecognitionStrategyId
import com.fpink.recognition.models.KrakenRecognizer
import com.fpink.recognition.models.PpOcrV6MediumRecognizer
import com.fpink.recognition.models.PpOcrV6SmallDetector

/** Printed text: PP-OCRv6 small detector + official PP-OCRv6 medium recognizer. */
class PrintedStrategy(context: Context) : DetectThenRecognizeStrategy(
    id = RecognitionStrategyId.PRINTED,
    detector = PpOcrV6SmallDetector(context),
    recognizer = PpOcrV6MediumRecognizer(context),
)

/** Handwriting: PP-OCRv6 small detector + Kraken's PP-OCRv6 medium recognizer (NFC output). */
class CursiveStrategy(context: Context) : DetectThenRecognizeStrategy(
    id = RecognitionStrategyId.CURSIVE,
    detector = PpOcrV6SmallDetector(context),
    recognizer = KrakenRecognizer(context),
)

/** Offline strategies bundled in every build; plugins come from RecognitionStrategyRegistry. */
object BuiltInStrategies {
    val ids: List<RecognitionStrategyId> = RecognitionStrategyId.BUILT_IN

    fun label(id: RecognitionStrategyId): String? = when (id) {
        RecognitionStrategyId.PRINTED -> "Printed"
        RecognitionStrategyId.CURSIVE -> "Cursive"
        else -> null
    }

    fun create(context: Context, id: RecognitionStrategyId): DetectThenRecognizeStrategy? = when (id) {
        RecognitionStrategyId.PRINTED -> PrintedStrategy(context)
        RecognitionStrategyId.CURSIVE -> CursiveStrategy(context)
        else -> null
    }
}