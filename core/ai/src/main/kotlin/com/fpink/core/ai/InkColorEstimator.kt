package com.fpink.core.ai

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared, local colour-family names for detected ink and a manually entered #RRGGBB colour. */
fun inkColorName(hex: String): String {
    require(hex.matches(Regex("#[0-9a-fA-F]{6}"))) { "Expected #RRGGBB" }
    val rgb = hex.substring(1).toInt(16)
    val lab = toLab(rgb)
    val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
    if (chroma < 0.035) return when {
        lab.l < 0.38 -> "Black"
        lab.l > 0.92 -> "White"
        else -> "Gray"
    }
    val hue = (atan2(lab.b, lab.a) * 180 / Math.PI + 360) % 360
    return when {
        hue < 45 || hue >= 350 -> if (lab.l > 0.7) "Pink" else "Red"
        hue < 85 -> if (lab.l < 0.65) "Brown" else "Orange"
        hue < 115 -> "Yellow"
        hue < 165 -> "Green"
        hue < 205 -> "Teal"
        hue < 250 -> "Blue"
        hue < 290 -> "Blue"
        hue < 330 -> "Purple"
        else -> "Pink"
    }
}

internal class GeometryBudget(var remaining: Int)

private data class Lab(val l: Double, val a: Double, val b: Double)

private val LINEAR_RGB = DoubleArray(256) {
    val s = it / 255.0
    if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

private fun toLab(rgb: Int): Lab {
    val r = LINEAR_RGB[(rgb ushr 16) and 255]
    val g = LINEAR_RGB[(rgb ushr 8) and 255]
    val b = LINEAR_RGB[rgb and 255]
    val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return Lab(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
}

private class PixelGrid(val left: Int, val top: Int, val width: Int, val height: Int, budget: Int) {
    val columns = min(width, max(1, floor(sqrt(budget.toDouble() * width / height)).toInt()))
    val rows = min(height, max(1, budget / columns))
    val size = columns * rows
    val mask = BooleanArray(size)
    val rgb = IntArray(size)
    val lightness = FloatArray(size)
    val a = FloatArray(size)
    val b = FloatArray(size)
    fun x(column: Int) = left + ((column + 0.5) * width / columns).toInt()
    fun y(row: Int) = top + ((row + 0.5) * height / rows).toInt()
    fun weight(column: Int, row: Int): Long {
        val w = (column + 1L) * width / columns - column.toLong() * width / columns
        val h = (row + 1L) * height / rows - row.toLong() * height / rows
        return w * h
    }
}

internal suspend fun estimateInkColor(
    image: PreparedImage,
    paragraph: ProcessingParagraph,
    sampleBudget: Int,
    geometryBudget: GeometryBudget,
): String? {
    val context = currentCoroutineContext()
    val bounds = paragraph.bounds ?: return null
    // A partially malformed paragraph has no safe estimate of its *whole* ink distribution.
    if (paragraph.regions.any { it.bounds == null }) return null
    val left = floor(bounds.left).toInt().coerceIn(0, image.width - 1)
    val top = floor(bounds.top).toInt().coerceIn(0, image.height - 1)
    val right = ceil(bounds.right).toInt().coerceIn(left + 1, image.width)
    val bottom = ceil(bounds.bottom).toInt().coerceIn(top + 1, image.height)
    val grid = PixelGrid(left, top, right - left, bottom - top, sampleBudget)
    if (!rasterizeUnion(image, paragraph, grid, geometryBudget)) return null
    var samples = 0
    for (row in 0 until grid.rows) {
        context.ensureActive()
        for (column in 0 until grid.columns) {
            val index = row * grid.columns + column
            if (!grid.mask[index]) continue
            val pixel = image.pixels[grid.y(row) * image.width + grid.x(column)]
            // Transparent samples do not provide a known paper/background colour.
            if ((pixel ushr 24) < 250) {
                grid.mask[index] = false
                continue
            }
            val lab = toLab(pixel)
            grid.rgb[index] = pixel
            grid.lightness[index] = lab.l.toFloat()
            grid.a[index] = lab.a.toFloat()
            grid.b[index] = lab.b.toFloat()
            samples++
        }
    }
    if (samples < 8) return null
    val foreground = identifyForeground(grid)
    suppressRuling(grid, foreground, paragraph.lineHeight)
    val histogram = HashMap<Int, ColorSupport>()
    for (index in 0 until grid.size) {
        if (index % 1_024 == 0) context.ensureActive()
        if (!foreground[index]) continue
        val rgb = grid.rgb[index]
        val key = (((rgb ushr 19) and 31) shl 10) or
            (((rgb ushr 11) and 31) shl 5) or ((rgb ushr 3) and 31)
        val support = histogram.getOrPut(key) { ColorSupport(key) }
        support.add(rgb, grid.weight(index % grid.columns, index / grid.columns))
    }
    if (histogram.isEmpty()) return null

    // Quantization bounds the histogram only; it does not choose an exact-RGB mode.
    val clusters = mutableListOf<InkCluster>()
    var unclustered = 0L
    for (support in histogram.values.sortedWith(compareByDescending<ColorSupport> { it.weight }.thenBy { it.key })) {
        context.ensureActive()
        val color = toLab(support.rgb())
        val cluster = clusters.filter { it.accepts(color) }.minByOrNull { it.distance(color) }
        if (cluster != null) cluster.support.add(support)
        else if (clusters.size < 32) clusters += InkCluster(color, support)
        else unclustered += support.weight
    }
    val winner = clusters.maxByOrNull { it.support.weight }?.support ?: return null
    // A few isolated pixels or an unrepresented colour population are not a reliable ink sample.
    if (winner.samples < 3 || unclustered >= winner.weight) return null
    return "#%06X".format(java.util.Locale.ROOT, winner.rgb())
}

private suspend fun rasterizeUnion(
    image: PreparedImage,
    paragraph: ProcessingParagraph,
    grid: PixelGrid,
    budget: GeometryBudget,
): Boolean {
    val context = currentCoroutineContext()
    val regions = paragraph.regions.distinctBy { it.source.polygon }
    val crossings = DoubleArray(64)
    for (row in 0 until grid.rows) {
        context.ensureActive()
        val y = grid.y(row) + 0.5
        for (region in regions) {
            if (--budget.remaining < 0) return false
            val bounds = checkNotNull(region.bounds)
            if (y < bounds.top || y >= bounds.bottom) continue
            val points = region.source.polygon
            budget.remaining -= points.size
            if (budget.remaining < 0) return false
            var count = 0
            for (i in points.indices) {
                val p = points[i]
                val q = points[(i + 1) % points.size]
                val py = p.y.toDouble() * image.height
                val qy = q.y.toDouble() * image.height
                if ((py > y) != (qy > y)) {
                    crossings[count++] = p.x.toDouble() * image.width +
                        (y - py) * (q.x - p.x).toDouble() * image.width / (qy - py)
                }
            }
            java.util.Arrays.sort(crossings, 0, count)
            for (crossing in 0 until count - 1 step 2) {
                val start = floor((crossings[crossing] - grid.left) * grid.columns / grid.width).toInt()
                    .coerceIn(0, grid.columns)
                val end = ceil((crossings[crossing + 1] - grid.left) * grid.columns / grid.width).toInt()
                    .coerceIn(start, grid.columns)
                budget.remaining -= end - start
                if (budget.remaining < 0) return false
                for (column in start until end) {
                    if (column % 1_024 == 0) context.ensureActive()
                    val x = grid.x(column) + 0.5
                    if (x >= crossings[crossing] && x < crossings[crossing + 1]) {
                        grid.mask[row * grid.columns + column] = true
                    }
                }
            }
        }
    }
    return true
}

private suspend fun identifyForeground(grid: PixelGrid): BooleanArray {
    val context = currentCoroutineContext()
    val foreground = BooleanArray(grid.size)
    val tileSize = 16
    for (top in 0 until grid.rows step tileSize) for (left in 0 until grid.columns step tileSize) {
        context.ensureActive()
        val indices = mutableListOf<Int>()
        // The surrounding half-tile keeps a thick letter's interior from becoming its own paper.
        for (row in max(0, top - tileSize / 2) until min(top + tileSize * 3 / 2, grid.rows)) {
            for (column in max(0, left - tileSize / 2) until min(left + tileSize * 3 / 2, grid.columns)) {
                val index = row * grid.columns + column
                if (grid.mask[index]) indices += index
            }
        }
        if (indices.size < 4) continue
        indices.sortBy { grid.lightness[it] }
        val paper = indices[(indices.size * 0.9).toInt().coerceAtMost(indices.lastIndex)]
        val paperL = grid.lightness[paper]
        val paperA = grid.a[paper]
        val paperB = grid.b[paper]
        val spread = paperL - grid.lightness[indices[(indices.size * 0.8).toInt()]]
        // The local upper quantile follows tinted paper and gradual lighting changes. A wider
        // bright-background distribution raises the threshold instead of counting a shadow.
        val threshold = max(0.025f, spread * 9f)
        for (row in top until min(top + tileSize, grid.rows)) {
            for (column in left until min(left + tileSize, grid.columns)) {
                val index = row * grid.columns + column
                if (!grid.mask[index]) continue
                val darkness = paperL - grid.lightness[index]
                val da = grid.a[index] - paperA
                val db = grid.b[index] - paperB
                val chromaticDifference = sqrt(da * da + db * db)
                foreground[index] = darkness > threshold ||
                    (chromaticDifference > max(0.045f, threshold) && darkness > -0.025f)
            }
        }
    }
    return foreground
}

private suspend fun suppressRuling(grid: PixelGrid, foreground: BooleanArray, lineHeight: Double) {
    val context = currentCoroutineContext()
    val ruled = BooleanArray(grid.size)
    fun similar(first: Int, second: Int): Boolean {
        if (!foreground[second]) return false
        val dl = grid.lightness[first] - grid.lightness[second]
        val da = grid.a[first] - grid.a[second]
        val db = grid.b[first] - grid.b[second]
        return dl * dl + da * da + db * db < 0.0064f
    }
    for (horizontal in listOf(true, false)) {
        val length = if (horizontal) grid.columns else grid.rows
        val count = if (horizontal) grid.rows else grid.columns
        val scale = if (horizontal) grid.height.toDouble() / grid.rows else grid.width.toDouble() / grid.columns
        val thin = max(1, (lineHeight / scale * 0.18).toInt())
        val minimumLength = max(12, (length * 0.65).toInt())
        fun index(along: Int, across: Int) =
            if (horizontal) across * grid.columns + along else along * grid.columns + across
        for (across in 0 until count) {
            context.ensureActive()
            var along = 0
            while (along < length) {
                val start = along
                val first = index(start, across)
                if (!foreground[first]) {
                    along++
                    continue
                }
                while (along < length && similar(first, index(along, across))) along++
                if (along - start < minimumLength) continue
                for (position in start until along) {
                    var thickness = 1
                    for (direction in listOf(-1, 1)) {
                        var neighbour = across + direction
                        while (neighbour in 0 until count && thickness <= thin &&
                            similar(first, index(position, neighbour))) {
                            thickness++
                            neighbour += direction
                        }
                    }
                    if (thickness <= thin) ruled[index(position, across)] = true
                }
            }
        }
    }
    for (index in foreground.indices) if (ruled[index]) foreground[index] = false
}

private class ColorSupport(val key: Int) {
    var weight = 0L
    var samples = 0
    private var red = 0L
    private var green = 0L
    private var blue = 0L
    fun add(rgb: Int, area: Long) {
        weight += area
        samples++
        red += ((rgb ushr 16) and 255) * area
        green += ((rgb ushr 8) and 255) * area
        blue += (rgb and 255) * area
    }
    fun add(other: ColorSupport) {
        weight += other.weight
        samples += other.samples
        red += other.red
        green += other.green
        blue += other.blue
    }
    fun rgb(): Int = ((red.toDouble() / weight).roundToInt() shl 16) or
        ((green.toDouble() / weight).roundToInt() shl 8) or (blue.toDouble() / weight).roundToInt()
}

private class InkCluster(private val anchor: Lab, val support: ColorSupport) {
    fun distance(color: Lab) = (anchor.l - color.l).pow(2) +
        (anchor.a - color.a).pow(2) + (anchor.b - color.b).pow(2)

    fun accepts(color: Lab): Boolean {
        val firstChroma = sqrt(anchor.a * anchor.a + anchor.b * anchor.b)
        val secondChroma = sqrt(color.a * color.a + color.b * color.b)
        if (firstChroma < 0.035 || secondChroma < 0.035) return firstChroma < 0.035 && secondChroma < 0.035
        if (distance(color) < 0.01) return true
        val hueAgreement = (anchor.a * color.a + anchor.b * color.b) / (firstChroma * secondChroma)
        return hueAgreement > 0.90 && abs(anchor.l - color.l) < 0.38
    }
}
