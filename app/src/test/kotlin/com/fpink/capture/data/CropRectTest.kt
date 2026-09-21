package com.fpink.capture.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CropRectTest {
    @Test fun movingPreservesSizeAndStaysInsideImage() {
        val crop = CropRect(0.2f, 0.3f, 0.7f, 0.8f)
        val moved = crop.move(-1f, -0.1f)
        assertEquals(0f, moved.left, 0.00001f)
        assertEquals(0.2f, moved.top, 0.00001f)
        assertEquals(0.5f, moved.right, 0.00001f)
        assertEquals(0.7f, moved.bottom, 0.00001f)
        assertEquals(CropRect(0.5f, 0.5f, 1f, 1f), crop.move(1f, 1f))
    }

    @Test fun resizingEnforcesMinimumRectangleAndImageBounds() {
        val crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(CropRect(0.75f, 0.2f, 0.8f, 0.8f), crop.resize(CropEdge.Left, 1f, 0f))
        assertEquals(CropRect(0.2f, 0f, 0.8f, 0.8f), crop.resize(CropEdge.Top, 0f, -1f))
        assertEquals(CropRect(0.2f, 0.2f, 1f, 1f), crop.resize(CropEdge.BottomRight, 1f, 1f))
    }

    @Test fun pixelBoundsUseFloorAndCeilingWithoutLeavingImage() {
        assertEquals(PixelCropRect(1, 1, 4, 3), CropRect(0.26f, 0.34f, 0.74f, 0.66f).pixels(5, 4))
        assertEquals(PixelCropRect(0, 0, 5, 4), CropRect.Full.pixels(5, 4))
    }

    @Test fun edgeSelectionPrefersCornersAndIgnoresInterior() {
        val crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(CropEdge.TopLeft, crop.closestEdge(0.21f, 0.19f, 0.03f))
        assertEquals(CropEdge.Right, crop.closestEdge(0.79f, 0.5f, 0.03f))
        assertNull(crop.closestEdge(0.5f, 0.5f, 0.03f))
    }

    @Test fun invalidRestoredRectangleFallsBackToFullImage() {
        assertEquals(CropRect.Full, CropRect.restored(0.9f, 0f, 0.1f, 1f))
        assertEquals(CropRect(0.1f, 0.2f, 0.9f, 0.8f), CropRect.restored(0.1f, 0.2f, 0.9f, 0.8f))
    }
}
