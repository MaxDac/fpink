package com.fpink.capture.data

/** EXIF values 1–8 include the mirrored orientations, not only rotations. */
internal fun exifTransform(orientation: Int): FloatArray {
    val (a, b, c, d) = when (orientation) {
        2 -> listOf(-1f, 0f, 0f, 1f)
        3 -> listOf(-1f, 0f, 0f, -1f)
        4 -> listOf(1f, 0f, 0f, -1f)
        5 -> listOf(0f, 1f, 1f, 0f)
        6 -> listOf(0f, -1f, 1f, 0f)
        7 -> listOf(0f, -1f, -1f, 0f)
        8 -> listOf(0f, 1f, -1f, 0f)
        else -> listOf(1f, 0f, 0f, 1f)
    }
    return floatArrayOf(a, b, 0f, c, d, 0f, 0f, 0f, 1f)
}
