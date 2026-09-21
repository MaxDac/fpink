package com.fpink.capture.data

import com.fpink.core.model.ZettelkastenCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-category colour assignments for the Zettelkasten beta feature, persisted as JSON because
 * DataStore preferences only serialize scalars/strings; `Map<ZettelkastenCategory, List<String>>`
 * is flattened into an ordered list so encoding does not depend on structured-map-key support.
 */
@Serializable
internal data class StoredZettelkastenCategoryColors(
    val category: ZettelkastenCategory,
    val colors: List<String>,
)

private val zettelkastenJson = Json { ignoreUnknownKeys = true }

internal fun encodeZettelkastenCategoryColors(colors: Map<ZettelkastenCategory, List<String>>): String =
    zettelkastenJson.encodeToString(
        colors.filterValues { it.isNotEmpty() }.map { (category, hex) -> StoredZettelkastenCategoryColors(category, hex) },
    )

internal fun decodeZettelkastenCategoryColors(stored: String?): Map<ZettelkastenCategory, List<String>> {
    if (stored.isNullOrBlank()) return emptyMap()
    return try {
        zettelkastenJson.decodeFromString<List<StoredZettelkastenCategoryColors>>(stored)
            .associate { it.category to it.colors }
    } catch (_: Exception) {
        // A corrupt preference must not crash Settings or matching; treat it as unconfigured.
        emptyMap()
    }
}
