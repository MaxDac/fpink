package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import com.fpink.core.model.InkColorOrigin
import java.util.ServiceLoader

/**
 * Open identifier so providers outside this module (never part of the public/FOSS build)
 * can be selected without editing this file. [PADDLE] is the only built-in value; any other
 * id is resolved at runtime through [RecognitionProviderRegistry].
 */
@JvmInline
value class RecognitionProviderId(val id: String) {
    companion object {
        val PADDLE = RecognitionProviderId("paddle")
    }
}

/**
 * Provider-specific configuration (endpoint, API key, license data, …) as opaque key/value
 * pairs. This module never reads or writes specific keys; only the provider that owns [provider]
 * interprets [config]. Values may be secrets: callers must never log or persist this map in
 * plain text (see [com.fpink.capture.data.SettingsStore]'s encrypted storage).
 */
data class RecognitionSettings(
    val provider: RecognitionProviderId = RecognitionProviderId.PADDLE,
    val config: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "RecognitionSettings(provider=$provider, config=[redacted, ${config.size} entries])"
}

/** Implemented by a provider that ships outside this module and registers itself via [ServiceLoader]. */
interface RecognitionProviderPlugin {
    val id: RecognitionProviderId
    fun create(context: Any, settings: RecognitionSettings): RecognitionProvider
}

/** Discovers [RecognitionProviderPlugin]s bundled into the current build, if any. */
object RecognitionProviderRegistry {
    fun find(id: RecognitionProviderId): RecognitionProviderPlugin? =
        ServiceLoader.load(RecognitionProviderPlugin::class.java).firstOrNull { it.id == id }
}

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
