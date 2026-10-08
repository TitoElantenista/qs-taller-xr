package com.qs.tallerxr.ui

import com.qs.tallerxr.ar.AlignmentPattern
import com.qs.tallerxr.ar.CorrespondencePick
import com.qs.tallerxr.ar.CorrespondenceQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlignmentPickingTest {

    @Test
    fun `a tap outside the tolerance selects nothing`() {
        val candidates = listOf(AlignmentCandidate(0, 100f, 100f, 2f))
        assertNull(selectAlignmentCandidate(candidates, 400f, 400f, 60f))
    }

    @Test
    fun `the nearest landmark on screen wins`() {
        val candidates = listOf(
            AlignmentCandidate(0, 100f, 100f, 2f),
            AlignmentCandidate(1, 140f, 100f, 2f),
        )
        assertEquals(1, selectAlignmentCandidate(candidates, 138f, 100f, 60f)?.index)
    }

    @Test
    fun `equally close landmarks resolve to the one in front`() {
        // Both corners project within the same distance bucket, as an edge seen through the part
        // does. The operator means the surface facing the camera.
        val candidates = listOf(
            AlignmentCandidate(0, 100f, 100f, 3.4f),
            AlignmentCandidate(1, 104f, 101f, 1.6f),
        )
        assertEquals(1, selectAlignmentCandidate(candidates, 100f, 100f, 60f)?.index)
    }

    @Test
    fun `removing a pick keeps the remaining labels contiguous`() {
        val pattern = AlignmentPattern()
            .withPoint(floatArrayOf(0f, 0f, 0f))
            .withPoint(floatArrayOf(1f, 0f, 0f))
            .withPoint(floatArrayOf(0f, 1f, 0f))
        val remaining = pattern.withoutPoint(2).picks
        assertEquals(listOf(1, 2), remaining.map(CorrespondencePick::label))
        assertEquals(1f, remaining[1].modelPoint[1], 0f)
    }

    @Test
    fun `quality headlines never promise a measurement`() {
        CorrespondenceQuality.entries.forEach { quality ->
            val headline = alignmentQualityHeadline(quality)
            assertEquals(true, headline.startsWith("Punktausrichtung"))
        }
        assertEquals("keine Abweichungen", alignmentResidualSummary(null))
    }
}
