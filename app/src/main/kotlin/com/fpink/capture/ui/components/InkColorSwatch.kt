package com.fpink.capture.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun InkColorSwatch(
    colorHex: String?,
    size: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val color = colorHex?.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrElse { Color.Gray }
    } ?: Color.Gray
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}
