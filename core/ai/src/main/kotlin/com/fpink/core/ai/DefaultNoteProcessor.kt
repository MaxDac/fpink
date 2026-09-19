package com.fpink.core.ai

import com.fpink.core.model.InkColorOrigin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reconstructs layout paragraphs without changing the transcription's words.
 *
 * Processing is deterministic and Android-free. At most 4,096 regions, 64 vertices per region
 * and one million text characters are accepted. Colour uses at most 131,072 stratified samples
 * per paragraph / 1,048,576 per document, weighted by represented pixel area. Small images are
 * sampled pixel-for-pixel. Union rasterization also has a 16-million-operation document budget.
 * Unusable geometry, exhausted sampling work or insufficient colour evidence defaults only the
 * colour; invalid inputs, cancellation and VM errors are not turned into black notes.
 */
class DefaultNoteProcessor : NoteProcessor {
    override suspend fun process(
        image: PreparedImage,
        recognition: RecognitionDocument,
    ): Result<List<ParagraphDraft>> {
        val context = currentCoroutineContext()
        context.ensureActive()
        require(recognition.regions.size <= 4_096) { "Too many recognized regions" }
        var characters = 0L
        recognition.regions.forEach { region ->
            context.ensureActive()
            characters += region.text.length
            require(characters <= 1_000_000) { "Too much recognized text" }
            require(region.polygon.size <= 64) { "Too many region vertices" }
            require(region.confidence == null ||
                (region.confidence.isFinite() && region.confidence in 0f..1f)) {
                "Region confidence must be finite and between zero and one"
            }
        }

        val paragraphs = reconstructParagraphs(recognition.regions, image.width, image.height)
        if (paragraphs.isEmpty()) return Result.success(emptyList())
        val samplesPerParagraph = minOf(131_072, 1_048_576 / paragraphs.size)
        val geometryBudget = GeometryBudget(16_000_000)
        val drafts = paragraphs.map { paragraph ->
            context.ensureActive()
            val color = estimateInkColor(image, paragraph, samplesPerParagraph, geometryBudget)
            val confidences = paragraph.regions.mapNotNull { it.source.confidence }
            ParagraphDraft(
                text = paragraph.text,
                polygon = paragraph.polygon,
                inkColorHex = color ?: "#000000",
                inkColorName = inkColorName(color ?: "#000000"),
                colorOrigin = if (color == null) InkColorOrigin.DEFAULTED else InkColorOrigin.DETECTED,
                confidence = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            )
        }
        context.ensureActive()
        return Result.success(drafts)
    }
}
