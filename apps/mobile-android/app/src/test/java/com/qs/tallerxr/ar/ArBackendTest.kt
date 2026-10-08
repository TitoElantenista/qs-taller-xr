package com.qs.tallerxr.ar

import org.junit.Assert.*
import org.junit.Test

class ArBackendTest {
    @Test fun `prefer Google when both services are ready`() {
        assertEquals(listOf(ArBackend.GOOGLE, ArBackend.HUAWEI),
            ArAvailability(ArServiceState.READY, ArServiceState.READY).candidates)
    }

    @Test fun `a missing unsupported or outdated Google service can use Huawei`() {
        for (google in ArServiceState.entries.filter { it != ArServiceState.READY }) {
            assertEquals(listOf(ArBackend.HUAWEI), ArAvailability(google, ArServiceState.READY).candidates)
        }
    }

    @Test fun `an installed Google service does not need Huawei`() {
        for (huawei in ArServiceState.entries.filter { it != ArServiceState.READY }) {
            assertEquals(listOf(ArBackend.GOOGLE), ArAvailability(ArServiceState.READY, huawei).candidates)
        }
    }

    @Test fun `no ready service keeps AR unavailable with an explanation`() {
        for (google in ArServiceState.entries.filter { it != ArServiceState.READY }) {
            for (huawei in ArServiceState.entries.filter { it != ArServiceState.READY }) {
                val result = ArAvailability(google, huawei)
                assertTrue(result.candidates.isEmpty())
                assertTrue(result.unavailableMessage().contains("3D-Modus"))
                assertTrue(result.unavailableMessage().contains("Google ARCore:"))
                assertTrue(result.unavailableMessage().contains("Huawei AR Engine:"))
            }
        }
    }
}
