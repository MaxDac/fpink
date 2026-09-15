package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal object AzureReadResponseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    }

    fun parseOperation(body: String): AzureReadOperation = decode(body)

    fun validateModel(body: String) {
        val model = decode<AzureReadModel>(body)
        requireResponse(model.modelId == AzureReadProvider.MODEL_ID, "Azure returned a different document model.")
    }

    fun normalize(result: AzureReadResult): RecognitionDocument {
        requireResponse(
            result.modelId == AzureReadProvider.MODEL_ID && result.apiVersion == AzureReadProvider.API_VERSION,
            "Azure returned an unexpected model or API version.",
        )
        requireResponse(result.stringIndexType == "utf16CodeUnit", "Azure returned an unsupported text offset format.")
        requireResponse(result.pages.size == 1, "Azure returned unexpected pages for a single image.")
        val page = result.pages.single()
        requireResponse(
            page.pageNumber == 1 && page.width.isFinite() && page.height.isFinite() &&
                page.width > 0 && page.height > 0 && page.unit in setOf("pixel", "inch"),
            "Azure returned invalid page geometry.",
        )
        val paragraphs = result.paragraphs.orEmpty()
        paragraphs.forEach {
            validateSpans(it.spans, result.content, it.content.isNotBlank())
        }
        val words = page.words.orEmpty()
        words.forEach {
            validateSpans(listOf(it.span), result.content, it.content.isNotBlank())
            validateConfidence(it.confidence)
        }
        val lines = page.lines.orEmpty()
        val normalized = lines.mapNotNull { line ->
            validateSpans(line.spans, result.content, line.content.isNotBlank())
            requireResponse(
                line.spans.joinToString("") { result.content.substring(it.offset, it.offset + it.length) } == line.content,
                "Azure returned line text inconsistent with its offsets.",
            )
            validateConfidence(line.confidence)
            val polygon = normalizePolygon(line.polygon, page)
            if (line.content.isBlank()) return@mapNotNull null
            val matchingParagraphs = paragraphs.withIndex().filter { (_, paragraph) ->
                line.spans.all { span -> coveredBy(span, paragraph.spans) }
            }
            val confidences = words.filter { word -> coveredBy(word.span, line.spans) }
                .mapNotNull { it.confidence }
            line.spans.minOf { it.offset } to TextRegion(
                text = line.content,
                polygon = polygon,
                paragraphId = matchingParagraphs.singleOrNull()?.let { "azure-paragraph-${it.index}" },
                confidence = line.confidence?.toFloat()
                    ?: confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            )
        }.sortedBy { it.first }.map { it.second }
        requireResponse(
            result.content.isBlank() == normalized.isEmpty(),
            "Azure returned text without usable line geometry.",
        )
        return RecognitionDocument(
            regions = normalized,
            provider = RecognitionProviderId.AZURE,
            modelVersion = "${AzureReadProvider.MODEL_ID}/${result.apiVersion}",
        )
    }

    private fun validateSpans(spans: List<AzureReadSpan>, content: String, requireNonempty: Boolean) {
        requireResponse(!requireNonempty || spans.isNotEmpty(), "Azure returned missing text offsets.")
        spans.forEach { span ->
            val end = span.offset.toLong() + span.length
            requireResponse(
                span.offset >= 0 && span.length >= 0 && (!requireNonempty || span.length > 0) &&
                    end <= content.length && isCodePointBoundary(content, span.offset) &&
                    isCodePointBoundary(content, end.toInt()),
                "Azure returned invalid Unicode text offsets.",
            )
        }
        requireResponse(
            spans.zipWithNext().all { (left, right) -> left.offset.toLong() + left.length <= right.offset },
            "Azure returned overlapping or unordered text offsets.",
        )
    }

    private fun isCodePointBoundary(content: String, index: Int): Boolean =
        index == 0 || index == content.length ||
            !(content[index - 1].isHighSurrogate() && content[index].isLowSurrogate())

    private fun coveredBy(span: AzureReadSpan, containers: List<AzureReadSpan>): Boolean {
        var coveredUntil = span.offset.toLong()
        val end = span.offset.toLong() + span.length
        for (container in containers) {
            if (container.offset > coveredUntil) return false
            coveredUntil = maxOf(coveredUntil, container.offset.toLong() + container.length)
            if (coveredUntil >= end) return true
        }
        return false
    }

    private fun validateConfidence(confidence: Double?) {
        requireResponse(
            confidence == null || (confidence.isFinite() && confidence in 0.0..1.0),
            "Azure returned an invalid recognition confidence.",
        )
    }

    private fun normalizePolygon(polygon: List<Double>, page: AzureReadPage): List<ImagePoint> {
        requireResponse(polygon.size >= 6 && polygon.size % 2 == 0, "Azure returned missing or invalid line geometry.")
        val points = polygon.chunked(2).map { (x, y) ->
            requireResponse(
                x.isFinite() && y.isFinite() && x in 0.0..page.width && y in 0.0..page.height,
                "Azure returned line coordinates outside the image.",
            )
            // The service may use pixels or inches; dividing by its page extent maps either to the prepared image.
            ImagePoint((x / page.width).toFloat(), (y / page.height).toFloat())
        }
        val area = points.indices.sumOf { index ->
            val next = points[(index + 1) % points.size]
            points[index].x.toDouble() * next.y - next.x.toDouble() * points[index].y
        }
        requireResponse(abs(area) > 0.0, "Azure returned a zero-area text polygon.")
        return points
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        throw RecognitionError.MalformedResponse("Azure Read returned an invalid response schema.")
    } catch (_: IllegalArgumentException) {
        throw RecognitionError.MalformedResponse("Azure Read returned malformed data.")
    }

    private fun requireResponse(condition: Boolean, message: String) {
        if (!condition) throw RecognitionError.MalformedResponse(message)
    }
}

@Serializable
internal data class AzureReadOperation(
    val status: String,
    val analyzeResult: AzureReadResult? = null,
    val error: AzureReadServiceError? = null,
)

@Serializable
internal data class AzureReadResult(
    val apiVersion: String,
    val modelId: String,
    val stringIndexType: String,
    val content: String,
    val pages: List<AzureReadPage>,
    val paragraphs: List<AzureReadParagraph>? = null,
)

@Serializable
internal data class AzureReadPage(
    val pageNumber: Int,
    val width: Double,
    val height: Double,
    val unit: String,
    val lines: List<AzureReadLine>? = null,
    val words: List<AzureReadWord>? = null,
)

@Serializable
internal data class AzureReadLine(
    val content: String,
    val polygon: List<Double>,
    val spans: List<AzureReadSpan>,
    val confidence: Double? = null,
)

@Serializable
internal data class AzureReadParagraph(
    val content: String,
    val spans: List<AzureReadSpan>,
)

@Serializable
internal data class AzureReadWord(
    val content: String,
    val span: AzureReadSpan,
    val confidence: Double? = null,
)

@Serializable
internal data class AzureReadSpan(val offset: Int, val length: Int)

@Serializable
internal data class AzureReadServiceError(
    val code: String,
    val innererror: AzureReadServiceError? = null,
)

@Serializable
internal data class AzureReadModel(val modelId: String)
