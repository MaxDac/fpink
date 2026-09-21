package com.fpink.core.model

import kotlin.math.pow
import kotlinx.serialization.Serializable

/**
 * The Zettelkasten method's fixed note-lifecycle stages: a quick, unprocessed
 * capture (`FLEETING`), a note tied to an external source (`LITERATURE`), and a
 * fully atomic, permanently filed note (`PERMANENT`). These three categories are
 * fixed by design; the app does not let users define additional ones.
 */
@Serializable
enum class ZettelkastenCategory {
    FLEETING,
    LITERATURE,
    PERMANENT,
}

/** A capture with no configured or matching colour starts life as a fleeting note. */
val DEFAULT_ZETTELKASTEN_CATEGORY = ZettelkastenCategory.FLEETING

/**
 * Perceptual colour distance below which two colours are considered "the same" for automatic
 * Zettelkasten matching. Expressed as squared OKLab distance so no square root is needed per
 * comparison. This intentionally tolerates ordinary photo/scan colour drift (lighting, sensor,
 * JPEG compression) while still separating visually distinct swatches such as the app's standard
 * ink palette.
 */
internal const val ZETTELKASTEN_COLOR_TOLERANCE = 0.02

/**
 * Matches [inkColorHex] against the colours configured for each category and returns the closest
 * category within [ZETTELKASTEN_COLOR_TOLERANCE]. Ties are broken by the smallest distance, then
 * by [ZettelkastenCategory] declaration order for full determinism. Returns
 * [DEFAULT_ZETTELKASTEN_CATEGORY] when there is no configured colour, no detected colour, or no
 * match within tolerance — an unmatched capture is not silently miscategorized.
 */
fun matchZettelkastenCategory(
    inkColorHex: String?,
    categoryColors: Map<ZettelkastenCategory, List<String>>,
): ZettelkastenCategory {
    val hex = inkColorHex?.takeIf { HEX_COLOR.matches(it) } ?: return DEFAULT_ZETTELKASTEN_CATEGORY
    val target = toOkLab(hex)
    var best: ZettelkastenCategory? = null
    var bestDistance = Double.MAX_VALUE
    for (category in ZettelkastenCategory.entries) {
        val colors = categoryColors[category].orEmpty()
        for (candidate in colors) {
            if (!HEX_COLOR.matches(candidate)) continue
            val distance = squaredDistance(target, toOkLab(candidate))
            if (distance < bestDistance) {
                bestDistance = distance
                best = category
            }
        }
    }
    return if (best != null && bestDistance <= ZETTELKASTEN_COLOR_TOLERANCE) best else DEFAULT_ZETTELKASTEN_CATEGORY
}

private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

private data class OkLab(val l: Double, val a: Double, val b: Double)

private val LINEAR_RGB = DoubleArray(256) {
    val s = it / 255.0
    if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

private fun toOkLab(hex: String): OkLab {
    val rgb = hex.substring(1).toInt(16)
    val r = LINEAR_RGB[(rgb ushr 16) and 255]
    val g = LINEAR_RGB[(rgb ushr 8) and 255]
    val b = LINEAR_RGB[rgb and 255]
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return OkLab(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

private fun squaredDistance(first: OkLab, second: OkLab): Double =
    (first.l - second.l).pow(2) + (first.a - second.a).pow(2) + (first.b - second.b).pow(2)
