package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class RegionBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width get() = right - left
    val height get() = bottom - top
    fun union(other: RegionBounds) = RegionBounds(
        min(left, other.left), min(top, other.top),
        max(right, other.right), max(bottom, other.bottom),
    )
}

internal data class ProcessingRegion(
    val source: TextRegion,
    val text: String,
    val index: Int,
    val bounds: RegionBounds?,
) {
    val hint get() = source.paragraphId?.takeIf { it.isNotBlank() }
}

internal data class ProcessingParagraph(val regions: List<ProcessingRegion>) {
    val text get() = regions.joinToString(" ") { it.text }
    val bounds = regions.mapNotNull { it.bounds }.reduceOrNull(RegionBounds::union)
    val lineHeight = regions.mapNotNull { it.bounds?.height }.sorted().let {
        if (it.isEmpty()) 1.0 else it[(it.size - 1) / 2]
    }
    val polygon: List<ImagePoint>
        get() {
            // Preserve an actual region polygon when possible; multi-line notes use an envelope.
            if (regions.size == 1 && regions.single().bounds != null) return regions.single().source.polygon
            val points = regions.filter { it.bounds != null }.flatMap { it.source.polygon }
            if (points.isEmpty()) return emptyList()
            val left = points.minOf { it.x }
            val right = points.maxOf { it.x }
            val top = points.minOf { it.y }
            val bottom = points.maxOf { it.y }
            return listOf(ImagePoint(left, top), ImagePoint(right, top),
                ImagePoint(right, bottom), ImagePoint(left, bottom))
        }
}

private class LayoutLine(region: ProcessingRegion) {
    val regions = mutableListOf(region)
    var bounds = checkNotNull(region.bounds)
    val hint get() = regions.firstNotNullOfOrNull { it.hint }
    val text get() = regions.joinToString(" ") { it.text }

    fun add(region: ProcessingRegion) {
        regions += region
        regions.sortWith(compareBy<ProcessingRegion> { it.bounds!!.left }.thenBy { it.index })
        bounds = bounds.union(checkNotNull(region.bounds))
    }
}

internal suspend fun reconstructParagraphs(
    sources: List<TextRegion>,
    imageWidth: Int,
    imageHeight: Int,
): List<ProcessingParagraph> {
    val context = currentCoroutineContext()
    val regions = sources.mapIndexedNotNull { index, source ->
        context.ensureActive()
        val text = source.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isEmpty()) null else ProcessingRegion(
            source, text, index, usableBounds(source.polygon, imageWidth, imageHeight),
        )
    }
    val lines = mutableListOf<LayoutLine>()
    regions.filter { it.bounds != null }
        .sortedWith(compareBy<ProcessingRegion> { it.bounds!!.top }.thenBy { it.bounds!!.left }.thenBy { it.index })
        .forEach { region ->
            context.ensureActive()
            val box = checkNotNull(region.bounds)
            // Adjacent word regions can form a line, but a wide gutter is not a word space.
            val sameLine = lines.asReversed().take(64).firstOrNull { line ->
                val height = min(box.height, line.bounds.height)
                val overlap = min(box.bottom, line.bounds.bottom) - max(box.top, line.bounds.top)
                val gap = max(box.left - line.bounds.right, line.bounds.left - box.right)
                overlap >= height * 0.55 && gap <= height * 1.5 &&
                    (line.hint == null || region.hint == null || line.hint == region.hint)
            }
            if (sameLine == null) lines += LayoutLine(region) else sameLine.add(region)
        }

    // Track each column independently. A line can extend one track, never bridge two tracks.
    val columns = mutableListOf<MutableList<LayoutLine>>()
    lines.sortedWith(compareBy<LayoutLine> { it.bounds.top }.thenBy { it.bounds.left }).forEach { line ->
        context.ensureActive()
        val column = columns.filter { column ->
            val previous = column.last().bounds
            val current = line.bounds
            val height = min(previous.height, current.height)
            val overlap = min(previous.right, current.right) - max(previous.left, current.left)
            current.top >= previous.bottom - height * 0.3 &&
                (overlap >= min(previous.width, current.width) * 0.25 ||
                    abs(previous.left - current.left) <= height * 0.6)
        }.minByOrNull { column ->
            val previous = column.last().bounds
            (line.bounds.top - previous.bottom).coerceAtLeast(0.0) +
                abs(line.bounds.left - previous.left) * 0.4
        }
        if (column == null) columns += mutableListOf(line) else column += line
    }

    val result = mutableListOf<ProcessingParagraph>()
    columns.sortedWith(compareBy<MutableList<LayoutLine>> { it.first().bounds.left }
        .thenBy { it.first().bounds.top }).forEach { column ->
        context.ensureActive()
        val heights = column.map { it.bounds.height }.sorted()
        val height = heights[(heights.size - 1) / 2]
        val gaps = column.zipWithNext { first, second ->
            (second.bounds.top - first.bounds.bottom).coerceAtLeast(0.0)
        }.sorted()
        val typicalGap = if (gaps.isEmpty()) height * 0.5 else gaps[(gaps.size - 1) / 3]
        val left = column.minOf { it.bounds.left }
        val right = column.map { it.bounds.right }.sorted().let { it[(it.size * 3 / 4).coerceAtMost(it.lastIndex)] }
        var paragraph = mutableListOf<ProcessingRegion>()
        var hint: String? = null
        column.forEachIndexed { index, line ->
            context.ensureActive()
            val previous = column.getOrNull(index - 1)
            val differentHint = hint != null && line.hint != null && hint != line.hint
            val sameHint = hint != null && line.hint == hint
            val boundary = previous != null && (
                differentHint ||
                    (!sameHint && startsParagraph(previous, line, height, typicalGap, left, right, column.size)) ||
                    (sameHint && line.bounds.top - previous.bounds.bottom > height * 8)
                )
            if (boundary) {
                result += ProcessingParagraph(paragraph)
                paragraph = mutableListOf()
                hint = null
            }
            paragraph.addAll(line.regions)
            if (hint == null) hint = line.hint
        }
        if (paragraph.isNotEmpty()) result += ProcessingParagraph(paragraph)
    }

    // No geometry means no invented spatial relationship. Contiguous matching hints are useful
    // even here; without them, keep each nonempty region intact instead of discarding its text.
    var unlocated = mutableListOf<ProcessingRegion>()
    regions.filter { it.bounds == null }.forEach { region ->
        context.ensureActive()
        if (unlocated.isNotEmpty() && (region.hint == null || region.hint != unlocated.last().hint ||
                region.index != unlocated.last().index + 1)) {
            result += ProcessingParagraph(unlocated)
            unlocated = mutableListOf()
        }
        unlocated += region
    }
    if (unlocated.isNotEmpty()) result += ProcessingParagraph(unlocated)
    return result
}

private fun startsParagraph(
    previous: LayoutLine,
    current: LayoutLine,
    height: Double,
    typicalGap: Double,
    left: Double,
    right: Double,
    lineCount: Int,
): Boolean {
    val gap = current.bounds.top - previous.bounds.bottom
    val spacingThreshold = if (lineCount == 2) height * 1.4 else max(height * 0.65, typicalGap * 1.75)
    if (gap > spacingThreshold) return true
    val indented = current.bounds.left - previous.bounds.left > height &&
        previous.bounds.left - left < height * 0.5
    val hangingList = previous.text.matches(Regex("""^\s*(?:[-*•]|\d+[.)])\s+.*"""))
    if (indented && !hangingList) return true
    val shortTerminalLine = previous.bounds.right < right - (right - left) * 0.3 &&
        previous.text.lastOrNull() in listOf('.', '!', '?', '…') &&
        current.bounds.left <= left + height * 0.4 &&
        current.bounds.right >= right - (right - left) * 0.15
    return shortTerminalLine
}

private fun usableBounds(points: List<ImagePoint>, width: Int, height: Int): RegionBounds? {
    if (points.size < 3) return null
    var area = 0.0
    for (i in points.indices) {
        val next = points[(i + 1) % points.size]
        area += points[i].x.toDouble() * next.y - next.x.toDouble() * points[i].y
    }
    if (abs(area) < 1e-12) return null
    // Reject crossing edges rather than interpreting a provider's broken polygon as ink.
    for (i in points.indices) for (j in i + 1 until points.size) {
        if (j == i + 1 || (i == 0 && j == points.lastIndex)) continue
        val a = points[i]
        val b = points[(i + 1) % points.size]
        val c = points[j]
        val d = points[(j + 1) % points.size]
        fun side(p: ImagePoint, q: ImagePoint, r: ImagePoint) =
            (q.x - p.x).toDouble() * (r.y - p.y) - (q.y - p.y).toDouble() * (r.x - p.x)
        if (side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0) return null
    }
    return RegionBounds(points.minOf { it.x }.toDouble() * width, points.minOf { it.y }.toDouble() * height,
        points.maxOf { it.x }.toDouble() * width, points.maxOf { it.y }.toDouble() * height)
        .takeIf { it.width > 0 && it.height > 0 }
}
