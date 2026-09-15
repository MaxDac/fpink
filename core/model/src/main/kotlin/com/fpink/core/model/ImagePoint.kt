package com.fpink.core.model

import kotlinx.serialization.Serializable

/** Coordinates normalized to the orientation-corrected source image. */
@Serializable
data class ImagePoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {
            "Image coordinates must be finite and between zero and one"
        }
    }
}

@Serializable
enum class InkColorOrigin {
    DETECTED,
    DEFAULTED,
    USER_SELECTED,
}
