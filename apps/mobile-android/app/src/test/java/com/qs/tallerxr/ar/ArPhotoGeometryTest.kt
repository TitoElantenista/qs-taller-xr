package com.qs.tallerxr.ar

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.*
import org.junit.Test

class ArPhotoGeometryTest {
    @Test fun portraitCaptureUsesSurfacePixelsWithoutWindowBarOffsets() {
        val view = ArPhotoSurfaceGeometry(0, 80, 1080, 2100, 1080, 2100)
        assertEquals(IntRect(0, 0, 1080, 2100), arPhotoSourceCrop(IntRect(0, 80, 1080, 2180), view))
    }

    @Test fun landscapeCaptureIncludesTheCompleteArViewport() {
        val view = ArPhotoSurfaceGeometry(52, 0, 2180, 1080, 2180, 1080)
        assertEquals(IntRect(0, 0, 2180, 1080), arPhotoSourceCrop(IntRect(52, 0, 2232, 1080), view))
    }

    @Test fun viewportInsideLargerSurfaceIsTranslatedAndScaledToItsBuffer() {
        val view = ArPhotoSurfaceGeometry(30, 70, 1000, 600, 2000, 1200)
        assertEquals(IntRect(200, 100, 1800, 1100), arPhotoSourceCrop(IntRect(130, 120, 930, 620), view))
    }

    @Test fun reducedResolutionBuffersAreNotReadUsingFullWindowCoordinates() {
        val view = ArPhotoSurfaceGeometry(40, 60, 2400, 1600, 1200, 800)
        assertEquals(IntRect(0, 0, 1200, 800), arPhotoSourceCrop(IntRect(40, 60, 2440, 1660), view))
        assertEquals(IntRect(100, 100, 1100, 700), arPhotoSourceCrop(IntRect(240, 260, 2240, 1460), view))
    }

    @Test fun fractionalScalingIncludesBoundaryPixelsAndStaysWithinTheBuffer() {
        val view = ArPhotoSurfaceGeometry(0, 0, 101, 201, 51, 101)
        assertEquals(IntRect(0, 0, 51, 101), arPhotoSourceCrop(IntRect(0, 0, 101, 201), view))
        assertEquals(IntRect(0, 0, 51, 101), arPhotoSourceCrop(IntRect(1, 1, 100, 200), view))
        assertEquals(IntRect(10, 10, 41, 91), arPhotoSourceCrop(IntRect(20, 20, 81, 181), view))
    }

    @Test fun stalePortraitBoundsCannotBeSilentlyClampedIntoARotatedSurface() {
        val landscape = ArPhotoSurfaceGeometry(0, 0, 2100, 1080, 2100, 1080)
        assertNull(arPhotoSourceCrop(IntRect(0, 0, 1080, 2100), landscape))
    }

    @Test fun partialOrDifferentSurfaceIsRejectedEvenIfItCoversTheViewportCentre() {
        val surface = ArPhotoSurfaceGeometry(0, 100, 1080, 1800, 1080, 1800)
        assertNull(arPhotoSourceCrop(IntRect(0, 0, 1080, 1920), surface))
        assertNull(arPhotoSourceCrop(IntRect(-1, 100, 1080, 1900), surface))
        assertNull(arPhotoSourceCrop(IntRect(0, 100, 1081, 1900), surface))
    }

    @Test fun unmeasuredViewsAndMissingBuffersCannotProduceAPhoto() {
        val surface = ArPhotoSurfaceGeometry(0, 0, 100, 100, 100, 100)
        assertNull(arPhotoSourceCrop(IntRect(0, 0, 0, 100), surface))
        assertNull(arPhotoSourceCrop(IntRect(0, 0, 100, 100), surface.copy(width = 0)))
        assertNull(arPhotoSourceCrop(IntRect(0, 0, 100, 100), surface.copy(bufferHeight = 0)))
    }

    @Test fun blackOrTransparentCopiesAreRejectedBeforePublishingOrDrawingALabel() {
        assertTrue(isBlankArPhoto(IntArray(1024) { 0xFF000000.toInt() }))
        assertTrue(isBlankArPhoto(IntArray(1024) { 0 }))
        assertTrue(isBlankArPhoto(IntArray(1024) { 0xFF030303.toInt() }))
        assertTrue(isBlankArPhoto(IntArray(1024) { 0x00FFFFFF }))
        assertTrue(isBlankArPhoto(intArrayOf()))
    }

    @Test fun darkImagesWithVisibleContentAndPlainSurfacesRemainValid() {
        val darkScene = IntArray(1024) { 0xFF000000.toInt() }
        darkScene[700] = 0xFF102030.toInt()
        assertFalse(isBlankArPhoto(darkScene))
        assertFalse(isBlankArPhoto(IntArray(1024) { 0xFF101010.toInt() }))
        assertFalse(isBlankArPhoto(IntArray(1024) { 0xFFFFFFFF.toInt() }))
    }
}
