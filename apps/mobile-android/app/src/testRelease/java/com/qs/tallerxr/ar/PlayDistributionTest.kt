package com.qs.tallerxr.ar

import com.qs.tallerxr.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class PlayDistributionTest {
    @Test fun `Play release has no Huawei SDK on its runtime classpath`() {
        assertFalse(BuildConfig.HUAWEI_AR_ENABLED)
        for (type in listOf("com.huawei.hiar.ARSession", "com.huawei.hiar.AREnginesApk")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(type) }
        }
    }

    @Test fun `a Play device without Google keeps AR unavailable and 3D accessible`() {
        val availability = ArAvailability(ArServiceState.MISSING, ArServiceState.DISABLED)
        assertTrue(availability.candidates.isEmpty())
        assertTrue(availability.unavailableMessage().contains("3D-Modus bleibt nutzbar"))
    }
}
