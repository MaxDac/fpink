package com.fpink.recognition.runtime

import kotlin.math.exp

/** Greedy (best-path) CTC decoding over a `[steps, classes]` row-major score matrix. */
object CtcDecoder {
    data class Decoded(val text: String, val confidence: Float)

    /**
     * Collapses repeats and drops [blank]. [label] maps a non-blank class index to its text.
     * When [scoresAreProbabilities] is false each row is softmax-normalised first so that the
     * confidence is always the mean probability of the emitted tokens.
     */
    fun greedy(
        scores: FloatArray,
        steps: Int,
        classes: Int,
        scoresAreProbabilities: Boolean,
        blank: Int = 0,
        label: (Int) -> String,
    ): Decoded {
        check(steps >= 1 && classes >= 2 && scores.size.toLong() == steps.toLong() * classes) {
            "Recognizer output has an unexpected shape"
        }
        val text = StringBuilder()
        var confidence = 0.0
        var emitted = 0
        var previous = blank
        for (step in 0 until steps) {
            val base = step * classes
            var token = 0
            var best = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val value = scores[base + c]
                check(value.isFinite()) { "Recognizer produced non-finite scores" }
                if (value > best) {
                    best = value
                    token = c
                }
            }
            val probability = if (scoresAreProbabilities) {
                check(best >= 0f && best <= 1.001f) { "Recognizer produced invalid probabilities" }
                best
            } else {
                var sum = 0.0
                for (c in 0 until classes) sum += exp((scores[base + c] - best).toDouble())
                (1.0 / sum).toFloat()
            }
            if (token != blank && token != previous) {
                text.append(label(token))
                confidence += probability
                emitted++
            }
            previous = token
        }
        return Decoded(text.toString(), if (emitted == 0) 0f else (confidence / emitted).toFloat().coerceIn(0f, 1f))
    }
}
