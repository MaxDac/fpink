package com.fpink.capture.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right - left >= MIN_SIZE && bottom - top >= MIN_SIZE)
    }

    fun move(deltaX: Float, deltaY: Float): CropRect {
        val x = deltaX.coerceIn(-left, 1f - right)
        val y = deltaY.coerceIn(-top, 1f - bottom)
        return copy(left = left + x, right = right + x, top = top + y, bottom = bottom + y)
    }

    fun resize(edge: CropEdge, deltaX: Float, deltaY: Float): CropRect {
        val nextLeft = if (edge.left) (left + deltaX).coerceIn(0f, right - MIN_SIZE) else left
        val nextTop = if (edge.top) (top + deltaY).coerceIn(0f, bottom - MIN_SIZE) else top
        val nextRight = if (edge.right) (right + deltaX).coerceIn(left + MIN_SIZE, 1f) else right
        val nextBottom = if (edge.bottom) (bottom + deltaY).coerceIn(top + MIN_SIZE, 1f) else bottom
        return CropRect(nextLeft, nextTop, nextRight, nextBottom)
    }

    fun pixels(width: Int, height: Int): PixelCropRect {
        require(width > 0 && height > 0)
        val pixelLeft = floor(left * width).toInt().coerceIn(0, width - 1)
        val pixelTop = floor(top * height).toInt().coerceIn(0, height - 1)
        val pixelRight = ceil(right * width).toInt().coerceIn(pixelLeft + 1, width)
        val pixelBottom = ceil(bottom * height).toInt().coerceIn(pixelTop + 1, height)
        return PixelCropRect(pixelLeft, pixelTop, pixelRight, pixelBottom)
    }

    companion object {
        const val MIN_SIZE = 0.05f
        val Full = CropRect(0f, 0f, 1f, 1f)

        fun restored(left: Float?, top: Float?, right: Float?, bottom: Float?): CropRect =
            runCatching {
                CropRect(
                    left ?: Full.left,
                    top ?: Full.top,
                    right ?: Full.right,
                    bottom ?: Full.bottom,
                )
            }.getOrDefault(Full)
    }
}

data class PixelCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width = right - left
    val height = bottom - top
}

enum class CropEdge(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
) {
    TopLeft(left = true, top = true),
    Top(top = true),
    TopRight(top = true, right = true),
    Right(right = true),
    BottomRight(right = true, bottom = true),
    Bottom(bottom = true),
    BottomLeft(left = true, bottom = true),
    Left(left = true),
}

fun CropRect.closestEdge(x: Float, y: Float, threshold: Float): CropEdge? {
    val nearLeft = kotlin.math.abs(x - left) <= threshold
    val nearRight = kotlin.math.abs(x - right) <= threshold
    val nearTop = kotlin.math.abs(y - top) <= threshold
    val nearBottom = kotlin.math.abs(y - bottom) <= threshold
    return when {
        nearLeft && nearTop -> CropEdge.TopLeft
        nearRight && nearTop -> CropEdge.TopRight
        nearRight && nearBottom -> CropEdge.BottomRight
        nearLeft && nearBottom -> CropEdge.BottomLeft
        nearTop && x in left..right -> CropEdge.Top
        nearRight && y in top..bottom -> CropEdge.Right
        nearBottom && x in left..right -> CropEdge.Bottom
        nearLeft && y in top..bottom -> CropEdge.Left
        else -> null
    }
}

fun CropRect.contains(x: Float, y: Float): Boolean =
    x in min(left, right)..max(left, right) && y in min(top, bottom)..max(top, bottom)
