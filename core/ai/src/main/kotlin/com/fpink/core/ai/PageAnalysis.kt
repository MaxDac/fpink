package com.fpink.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PageAnalysis(
    val text: String,
    @SerialName("ink_color_hex") val inkColorHex: String? = null,
    @SerialName("ink_color_name") val inkColorName: String? = null,
    val confidence: Float? = null,
    val notes: String? = null,
)
