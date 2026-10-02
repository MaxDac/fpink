package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import com.fpink.core.model.InkColorOrigin
import java.util.ServiceLoader

/**
 * Open identifier so strategies outside this module can be selected without editing this file.
 * [PRINTED] and [CURSIVE] are public offline built-ins; any other id is resolved at runtime
 * through [RecognitionStrategyRegistry].
 */
@JvmInline
value class RecognitionStrategyId(val id: String) {
    companion object {
        val PRINTED = RecognitionStrategyId("printed")
        val CURSIVE = RecognitionStrategyId("cursive")
        val BUILT_IN = listOf(PRINTED, CURSIVE)
        val DEFAULT = CURSIVE
    }
}

/**
 * Strategy-specific configuration (endpoint, API key, license data, …) as opaque key/value
 * pairs. This module never reads or writes specific keys; only the strategy that owns [strategy]
 * interprets [config]. Values may be secrets: callers must never log or persist this map in
 * plain text (see [com.fpink.capture.data.SettingsStore]'s encrypted storage).
 */
data class RecognitionSettings(
    val strategy: RecognitionStrategyId = RecognitionStrategyId.DEFAULT,
    val config: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "RecognitionSettings(strategy=$strategy, config=[redacted, ${config.size} entries])"
}

/**
 * Turns a prepared page into recognised text regions. A strategy may combine several
 * [RecognitionModel]s (for example a [TextDetector] and a [TextLineRecognizer]) or call a
 * remote service; callers only depend on this contract.
 */
interface RecognitionStrategy {
    val id: RecognitionStrategyId
    suspend fun recognize(image: PreparedImage): Result<RecognitionDocument>
}

/** Implemented by a strategy that ships outside this module and registers itself via [ServiceLoader]. */
interface RecognitionStrategyPlugin {
    val id: RecognitionStrategyId
    val label: String get() = id.id
    val requiresNetwork: Boolean get() = true
    fun create(context: Any, settings: RecognitionSettings): RecognitionStrategy
}

/** Discovers [RecognitionStrategyPlugin]s bundled into the current build, if any. */
object RecognitionStrategyRegistry {
    fun all(): List<RecognitionStrategyPlugin> =
        ServiceLoader.load(RecognitionStrategyPlugin::class.java)
            .filter { it.id !in RecognitionStrategyId.BUILT_IN }
            .distinctBy { it.id }

    fun find(id: RecognitionStrategyId): RecognitionStrategyPlugin? = all().firstOrNull { it.id == id }
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

/** Polygon coordinates are normalized to the prepared image, not a model's resized input. */
data class TextRegion(
    val text: String,
    val polygon: List<ImagePoint>,
    val paragraphId: String? = null,
    val confidence: Float? = null,
)

data class RecognitionDocument(
    val regions: List<TextRegion>,
    val strategy: RecognitionStrategyId,
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

interface NoteProcessor {
    suspend fun process(image: PreparedImage, recognition: RecognitionDocument): Result<List<ParagraphDraft>>
}