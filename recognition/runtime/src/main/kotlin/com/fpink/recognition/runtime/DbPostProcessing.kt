package com.fpink.recognition.runtime

import com.fpink.core.ai.DetectedLine
import com.fpink.core.ai.PixelPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Thresholds of the PP-OCR `DBPostProcess` step, taken from a model's pinned `inference.yml`. */
data class DbParameters(
    val threshold: Float,
    val boxThreshold: Float,
    val unclipRatio: Float,
    val maxCandidates: Int,
    val maxLines: Int = 512,
) {
    init {
        require(threshold in 0f..1f && boxThreshold in 0f..1f && unclipRatio > 0f)
        require(maxCandidates > 0 && maxLines > 0)
    }
}

/**
 * Differentiable-binarization post-processing: turns a detector probability map into rotated
 * text-line rectangles. Kotlin port of the previously reviewed native geometry code:
 * 8-connected components, convex hull, minimum-area rectangle,
 * mean-score filter and DB unclip expansion of the rectangle.
 */
object DbPostProcessing {
    const val MAX_MAP_PIXELS = 1024 * 1024

    /** Detector input size: long side scaled to [longSide], both sides rounded to multiples of 32. */
    fun inputSize(width: Int, height: Int, longSide: Int = 960): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid image dimensions" }
        val scale = longSide.toFloat() / max(width, height)
        return max(32, (width * scale / 32).roundToInt() * 32) to max(32, (height * scale / 32).roundToInt() * 32)
    }

    /**
     * Returns detected lines in probability-map pixel coordinates, sorted top-to-bottom then
     * left-to-right. Throws [IllegalArgumentException] for pages with more candidates or lines
     * than the configured limits and [IllegalStateException] for malformed maps.
     */
    fun boxes(map: FloatArray, width: Int, height: Int, parameters: DbParameters): List<DetectedLine> {
        check(width >= 1 && height >= 1 && width.toLong() * height <= MAX_MAP_PIXELS && map.size == width * height) {
            "Invalid detector output dimensions"
        }
        for (value in map) check(value.isFinite() && value >= 0f && value <= 1.001f) { "Invalid detector probabilities" }
        val total = width * height
        val visited = BooleanArray(total)
        val pending = IntArray(total)
        val results = mutableListOf<DetectedLine>()
        var candidates = 0
        for (start in 0 until total) {
            if (visited[start] || map[start] <= parameters.threshold) continue
            require(++candidates <= parameters.maxCandidates) { "Image contains too many text candidates" }
            var size = 0
            pending[size++] = start
            visited[start] = true
            val boundary = mutableListOf<Point>()
            var head = 0
            while (head < size) {
                val index = pending[head++]
                val x = index % width
                val y = index / width
                var edge = false
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || xx >= width || yy < 0 || yy >= height) {
                        edge = true
                        continue
                    }
                    val next = yy * width + xx
                    if (map[next] <= parameters.threshold) {
                        edge = true
                        continue
                    }
                    if (!visited[next]) {
                        visited[next] = true
                        pending[size++] = next
                    }
                }
                if (edge) boundary += Point(x.toFloat(), y.toFloat())
            }
            if (size < 6 || boundary.size < 3) continue
            val polygon = hull(boundary)
            if (polygon.size < 3) continue
            val box = minimumRectangle(polygon)
            if (min(distance(box[0], box[1]), distance(box[0], box[3])) < 3f) continue
            val score = meanScore(map, width, height, box)
            if (!score.isFinite() || score < parameters.boxThreshold) continue
            var area = 0.0
            var perimeter = 0.0
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                area += a.x * b.y - a.y * b.x
                perimeter += distance(a, b)
            }
            val expansion = (abs(area) * 0.5 * parameters.unclipRatio / perimeter).toFloat()
            results += DetectedLine(expandRectangle(box, expansion).map { PixelPoint(it.x, it.y) }, score)
            require(results.size <= parameters.maxLines) { "Image contains more than ${parameters.maxLines} text lines" }
        }
        return results.sortedWith(compareBy<DetectedLine> { it.quad[0].y }.thenBy { it.quad[0].x })
    }

    /** Maps a line from probability-map pixels to image pixels, clamping it to the image. */
    fun scale(line: DetectedLine, mapWidth: Int, mapHeight: Int, imageWidth: Int, imageHeight: Int): DetectedLine =
        line.copy(
            quad = line.quad.map {
                PixelPoint(
                    (it.x / mapWidth * imageWidth).coerceIn(0f, imageWidth.toFloat()),
                    (it.y / mapHeight * imageHeight).coerceIn(0f, imageHeight.toFloat()),
                )
            },
        )

    internal data class Point(val x: Float, val y: Float)

    private fun cross(o: Point, a: Point, b: Point): Float = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    private fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

    internal fun hull(input: List<Point>): List<Point> {
        val points = input.distinct().sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
        if (points.size < 3) return points
        val result = ArrayList<Point>(points.size * 2)
        for (p in points) {
            while (result.size >= 2 && cross(result[result.size - 2], result[result.size - 1], p) <= 0) result.removeAt(result.size - 1)
            result += p
        }
        val lower = result.size + 1
        for (i in points.size - 2 downTo 0) {
            val p = points[i]
            while (result.size >= lower && cross(result[result.size - 2], result[result.size - 1], p) <= 0) result.removeAt(result.size - 1)
            result += p
        }
        result.removeAt(result.size - 1)
        return result
    }

    internal fun minimumRectangle(polygon: List<Point>): List<Point> {
        var best = Float.MAX_VALUE
        var result = List(4) { Point(0f, 0f) }
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val length = distance(a, b)
            if (length < 0.001f) continue
            val ux = (b.x - a.x) / length
            val uy = (b.y - a.y) / length
            val vx = -uy
            val vy = ux
            var loU = Float.MAX_VALUE
            var loV = Float.MAX_VALUE
            var hiU = -Float.MAX_VALUE
            var hiV = -Float.MAX_VALUE
            for (p in polygon) {
                val pu = p.x * ux + p.y * uy
                val pv = p.x * vx + p.y * vy
                loU = min(loU, pu); hiU = max(hiU, pu)
                loV = min(loV, pv); hiV = max(hiV, pv)
            }
            val area = (hiU - loU) * (hiV - loV)
            if (area < best) {
                best = area
                fun point(u: Float, v: Float) = Point(u * ux + v * vx, u * uy + v * vy)
                result = listOf(point(loU, loV), point(hiU, loV), point(hiU, hiV), point(loU, hiV))
            }
        }
        val topLeft = result.indices.minBy { result[it].x + result[it].y }
        return List(4) { result[(topLeft + it) % 4] }
    }

    private fun meanScore(map: FloatArray, width: Int, height: Int, box: List<Point>): Float {
        val minX = box.minOf { it.x }
        val maxX = box.maxOf { it.x }
        val minY = box.minOf { it.y }
        val maxY = box.maxOf { it.y }
        var sum = 0.0
        var count = 0
        for (y in max(0, floor(minY).toInt())..min(height - 1, ceil(maxY).toInt())) {
            for (x in max(0, floor(minX).toInt())..min(width - 1, ceil(maxX).toInt())) {
                val p = Point(x.toFloat(), y.toFloat())
                if ((0 until 4).all { cross(box[it], box[(it + 1) % 4], p) >= -0.01f }) {
                    sum += map[y * width + x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun expandRectangle(box: List<Point>, amount: Float): List<Point> {
        val width = distance(box[0], box[1])
        val height = distance(box[0], box[3])
        if (width < 0.001f || height < 0.001f) return box
        val ux = (box[1].x - box[0].x) / width
        val uy = (box[1].y - box[0].y) / width
        val vx = (box[3].x - box[0].x) / height
        val vy = (box[3].y - box[0].y) / height
        return listOf(
            Point(box[0].x - amount * (ux + vx), box[0].y - amount * (uy + vy)),
            Point(box[1].x + amount * (ux - vx), box[1].y + amount * (uy - vy)),
            Point(box[2].x + amount * (ux + vx), box[2].y + amount * (uy + vy)),
            Point(box[3].x + amount * (-ux + vx), box[3].y + amount * (-uy + vy)),
        )
    }
}
