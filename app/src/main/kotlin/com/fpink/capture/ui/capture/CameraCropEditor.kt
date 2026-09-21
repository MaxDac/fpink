package com.fpink.capture.ui.capture

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import com.fpink.capture.data.CropEdge
import com.fpink.capture.data.CropRect
import com.fpink.capture.data.closestEdge
import com.fpink.capture.data.contains
import java.io.File

private sealed interface CropDrag {
    data object Move : CropDrag
    data class Resize(val edge: CropEdge) : CropDrag
}

@Composable
internal fun CameraCropEditor(
    file: File,
    crop: CropRect,
    onCropChanged: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageSize = remember(file, file.lastModified()) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        Size(options.outWidth.toFloat(), options.outHeight.toFloat())
    }
    val density = LocalDensity.current
    val handleRadius = with(density) { 7.dp.toPx() }
    val touchRadius = with(density) { 24.dp.toPx() }
    val currentCrop by rememberUpdatedState(crop)
    val currentOnCropChanged by rememberUpdatedState(onCropChanged)
    var drag by remember { mutableStateOf<CropDrag?>(null) }

    Box(
        modifier.semantics {
            contentDescription = "Crop captured photo. Drag inside the rectangle to move it or drag its edges to resize."
        },
    ) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            Modifier.fillMaxSize().pointerInput(imageSize) {
                detectDragGestures(
                    onDragStart = { position ->
                        val image = fittedImageRect(size.toSize(), imageSize)
                        if (!image.contains(position)) {
                            drag = null
                        } else {
                            val x = (position.x - image.left) / image.width
                            val y = (position.y - image.top) / image.height
                            val threshold = maxOf(touchRadius / image.width, touchRadius / image.height)
                            drag = currentCrop.closestEdge(x, y, threshold)?.let(CropDrag::Resize)
                                ?: CropDrag.Move.takeIf { currentCrop.contains(x, y) }
                        }
                    },
                    onDragCancel = { drag = null },
                    onDragEnd = { drag = null },
                ) { change, amount ->
                    val image = fittedImageRect(size.toSize(), imageSize)
                    val deltaX = amount.x / image.width
                    val deltaY = amount.y / image.height
                    val activeCrop = currentCrop
                    val next = when (val current = drag) {
                        CropDrag.Move -> activeCrop.move(deltaX, deltaY)
                        is CropDrag.Resize -> activeCrop.resize(current.edge, deltaX, deltaY)
                        null -> activeCrop
                    }
                    if (next != activeCrop) {
                        change.consume()
                        currentOnCropChanged(next)
                    }
                }
            },
        ) {
            val image = fittedImageRect(size, imageSize)
            val selection = Rect(
                image.left + crop.left * image.width,
                image.top + crop.top * image.height,
                image.left + crop.right * image.width,
                image.top + crop.bottom * image.height,
            )
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(image.left, image.top), Size(image.width, selection.top - image.top))
            drawRect(shade, Offset(image.left, selection.bottom), Size(image.width, image.bottom - selection.bottom))
            drawRect(shade, Offset(image.left, selection.top), Size(selection.left - image.left, selection.height))
            drawRect(shade, Offset(selection.right, selection.top), Size(image.right - selection.right, selection.height))
            drawRect(Color.White, selection.topLeft, selection.size, style = Stroke(width = 2f * density.density))
            listOf(
                selection.topLeft,
                Offset(selection.center.x, selection.top),
                Offset(selection.right, selection.top),
                Offset(selection.right, selection.center.y),
                Offset(selection.right, selection.bottom),
                Offset(selection.center.x, selection.bottom),
                Offset(selection.left, selection.bottom),
                Offset(selection.left, selection.center.y),
            ).forEach { drawCircle(Color.White, handleRadius, it) }
        }
    }
}

private fun fittedImageRect(container: Size, image: Size): Rect {
    if (container.width <= 0f || container.height <= 0f || image.width <= 0f || image.height <= 0f) {
        return Rect(0f, 0f, container.width.coerceAtLeast(1f), container.height.coerceAtLeast(1f))
    }
    val scale = minOf(container.width / image.width, container.height / image.height)
    val width = image.width * scale
    val height = image.height * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}
