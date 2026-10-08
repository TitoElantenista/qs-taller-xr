package com.qs.tallerxr.ar

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ArPhotoNamingTest {
    @Test
    fun formatsDrawingAndPositionForTheArLabel() {
        assertEquals("181001.01", arPhotoPositionLabel(" 181001/01 "))
        assertEquals("181001.1", arPhotoPositionLabel("181001.1"))
        assertEquals("P1001", arPhotoPositionLabel(" P1001 "))
        assertEquals("BAUTEIL", arPhotoPositionLabel("   "))
    }

    @Test
    fun combinesSanitizedPositionAndTimestamp() {
        assertEquals(
            "BT_42-A_2026-08-10_14-05-09.jpg",
            arPhotoDisplayName(" BT 42-A ", LocalDateTime.of(2026, 8, 10, 14, 5, 9)),
        )
    }

    @Test
    fun usesFallbackForAnEmptyPosition() {
        assertEquals(
            "BAUTEIL_2026-08-10_14-05-09.jpg",
            arPhotoDisplayName("***", LocalDateTime.of(2026, 8, 10, 14, 5, 9)),
        )
    }
}
