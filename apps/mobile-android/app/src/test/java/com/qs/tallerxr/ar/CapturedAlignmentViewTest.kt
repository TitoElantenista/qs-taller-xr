package com.qs.tallerxr.ar

import org.junit.Assert.*
import org.junit.Test

class CapturedAlignmentViewTest {
    @Test fun `bottom controls never cover the frozen image or create marks in their strip`() {
        val fit = CapturedImageFit(2340, 1080, 2340, 1080, bottomInset = 192)
        assertTrue(fit.top + fit.height <= 888f)
        assertNull(fit.imagePoint(1170f, 950f))
        val touch = fit.viewportPoint(2000f, 1000f)
        assertArrayEquals(floatArrayOf(2000f, 1000f), fit.imagePoint(touch[0], touch[1]), 0.001f)
    }

    @Test fun `letterboxed photo maps touches and excludes black bars`() {
        val fit = CapturedImageFit(1000, 500, 500, 1000)
        assertEquals(375f, fit.top, 0.01f)
        assertNull(fit.imagePoint(250f, 374f))
        assertNull(fit.imagePoint(250f, 625f))
        assertNull(fit.imagePoint(500f, 400f))
        val touch = fit.viewportPoint(731f, 219f)
        assertArrayEquals(floatArrayOf(731f, 219f), fit.imagePoint(touch[0], touch[1]), 0.001f)
    }

    @Test fun `YUV conversion respects padded rows chroma strides and truncated last rows`() {
        // Two interleaved chroma planes, with U/V positions exposed as separate buffer slices.
        val planes = listOf(
            CameraPlane(byteArrayOf(16, 16, 235.toByte(), 235.toByte(), 0, 0,
                235.toByte(), 235.toByte(), 16, 16), 6, 1),
            CameraPlane(byteArrayOf(128.toByte(), 42, 128.toByte()), 4, 2),
            CameraPlane(byteArrayOf(128.toByte(), 99, 128.toByte()), 4, 2),
        )
        val pixels = cameraArgb(4, 2, planes)
        assertEquals(0xff000000.toInt(), pixels[0])
        assertEquals(0xff000000.toInt(), pixels[7])
        assertTrue((pixels[2] and 255) >= 254)
        assertTrue((pixels[4] and 255) >= 254)
        assertEquals((pixels[2] shr 16) and 255, pixels[2] and 255)
    }

    @Test fun `YUV chroma planes are not swapped`() {
        val planes = listOf(CameraPlane(ByteArray(4) { 81 }, 2, 1),
            CameraPlane(byteArrayOf(90), 1, 1), CameraPlane(byteArrayOf(240.toByte()), 1, 1))
        val red = cameraArgb(2, 2, planes)[0]
        assertTrue(((red shr 16) and 255) > 250)
        assertTrue(((red shr 8) and 255) < 5)
        assertTrue((red and 255) < 5)
    }
}
