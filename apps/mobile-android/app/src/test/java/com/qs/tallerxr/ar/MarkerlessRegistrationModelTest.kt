package com.qs.tallerxr.ar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerlessRegistrationModelTest {
    @Test
    fun decodesMetricPointsNormalsAndEdges() {
        val payload = modelPayload()

        val model = MarkerlessRegistrationModelReader.decode(payload)

        assertEquals(1, model.pointCount)
        assertEquals(1, model.edgeCount)
        assertEquals(3f, model.points[2], 0.0001f)
        assertEquals(1f, model.normals[2], 0.0001f)
        assertEquals(0.01f, model.sampleSpacingM, 0.0001f)
        assertTrue(model.diameterM > 3.7f)
    }

    @Test
    fun rejectsPayloadWhoseLengthDoesNotMatchHeader() {
        val corrupt = modelPayload().copyOf(modelPayload().size - 1)

        val error = runCatching { MarkerlessRegistrationModelReader.decode(corrupt) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Größe"))
    }

    private fun modelPayload(): ByteArray {
        val payload = ByteBuffer.allocate(48 + 48).order(ByteOrder.LITTLE_ENDIAN)
        payload.put("QSRG".encodeToByteArray())
        payload.putShort(1.toShort())
        payload.putShort(0.toShort())
        payload.putInt(1)
        payload.putInt(1)
        listOf(0f, 0f, 0f, 1f, 2f, 3f, 0.01f).forEach(payload::putFloat)
        payload.putInt(3)
        listOf(
            1f, 2f, 3f, 0f, 0f, 1f,
            0f, 0f, 0f, 1f, 0f, 0f,
        ).forEach(payload::putFloat)
        return payload.array()
    }
}
