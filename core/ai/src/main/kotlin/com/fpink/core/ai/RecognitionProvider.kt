package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import com.fpink.core.model.InkColorOrigin

enum class RecognitionProviderId {
    PADDLE,
    AZURE,
}

data class AzureReadConfig(val endpoint: String = "", val apiKey: String = "") {
    override fun toString(): String = "AzureReadConfig(endpoint=$endpoint, apiKey=[redacted])"
}

data class RecognitionSettings(
    val provider: RecognitionProviderId = RecognitionProviderId.PADDLE,
    val azure: AzureReadConfig = AzureReadConfig(),
)

/** Encoded bytes and ARGB pixels must describe the same orientation-corrected image. */
class PreparedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height) {
            "Image dimensions must match the pixel buffer"
        }
        require(bytes.isNotEmpty()) { "The encoded image must not be empty" }
        require(mimeType in setOf("image/jpeg", "image/png")) { "Expected a prepared JPEG or PNG image" }
    }
}

/** Polygon coordinates are normalized to the prepared image, not a provider's resized input. */
data class TextRegion(
    val text: String,
    val polygon: List<ImagePoint>,
    val paragraphId: String? = null,
    val confidence: Float? = null,
)

data class RecognitionDocument(
    val regions: List<TextRegion>,
    val provider: RecognitionProviderId,
    val modelVersion: String,
)

data class ParagraphDraft(
    val text: String,
    val polygon: List<ImagePoint>,
    val inkColorHex: String,
    val inkColorName: String,
    val colorOrigin: InkColorOrigin,
    val confidence: Float? = null,
)

interface RecognitionProvider {
    suspend fun recognize(image: PreparedImage): Result<RecognitionDocument>
}

interface NoteProcessor {
    suspend fun process(image: PreparedImage, recognition: RecognitionDocument): Result<List<ParagraphDraft>>
}
