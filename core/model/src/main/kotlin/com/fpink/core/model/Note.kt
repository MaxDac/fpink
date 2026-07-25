package com.fpink.core.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val id: String,
    @Serializable(with = InstantSerializer::class)
    val capturedAt: Instant,
    val imagePath: String,
    val text: String,
    val inkColorHex: String? = null,
    val inkColorName: String? = null,
    val confidence: Float? = null,
    val modelNotes: String? = null,
    val userEdited: Boolean = false,
    val tags: List<String> = emptyList(),
    val links: List<String> = emptyList(),
)
