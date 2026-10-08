package com.qs.tallerxr.diagnostics

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitDiagnosticsTest {
    @Test
    fun nativeCrashDoesNotGuessFilamentAsTheSource() {
        val label = classifyExitReason(ApplicationExitInfo.REASON_CRASH_NATIVE, 6)

        assertEquals("nativer Absturz (Ursache noch unbekannt)", label)
        assertFalse(label.orEmpty().contains("Filament", ignoreCase = true))
    }

    @Test
    fun anrAndSignalsRemainDistinctFromNativeCrashes() {
        assertEquals(
            "App reagierte nicht (ANR)",
            classifyExitReason(ApplicationExitInfo.REASON_ANR, 0),
        )
        assertEquals(
            "Prozess durch SIGKILL beendet (vermutlich Speicher oder System)",
            classifyExitReason(ApplicationExitInfo.REASON_SIGNALED, 9),
        )
        assertNull(classifyExitReason(ApplicationExitInfo.REASON_USER_REQUESTED, 0))
    }

    @Test
    fun processStateFitsAndroids128ByteLimitAndKeepsTheNewestTransition() {
        val summary = compactProcessState(
            previous = "viewer:open:" + "x".repeat(180),
            current = "viewer:closing:900140.1",
        )

        assertTrue(summary.size <= 128)
        assertTrue(summary.toString(Charsets.UTF_8).contains("viewer:closing:900140.1"))
    }

    @Test
    fun alreadyHandledProcessExitIsNotReportedAgain() {
        val identity = processExitIdentity(
            timestamp = 1_786_451_000_000,
            pid = 31415,
            reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
            status = 6,
        )

        assertFalse(shouldReportProcessExit(identity, handledIdentity = identity))
    }

    @Test
    fun aNewProcessExitRemainsVisibleEvenWhenItIsVeryCloseToThePreviousOne() {
        val handled = processExitIdentity(
            timestamp = 1_786_451_000_000,
            pid = 31415,
            reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
            status = 6,
        )
        val newExit = processExitIdentity(
            timestamp = 1_786_451_000_001,
            pid = 31416,
            reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
            status = 6,
        )

        assertTrue(shouldReportProcessExit(newExit, handledIdentity = handled))
    }

    @Test
    fun anUnmarkedProcessExitIsReported() {
        val identity = processExitIdentity(
            timestamp = 1_786_451_000_000,
            pid = 31415,
            reason = ApplicationExitInfo.REASON_ANR,
            status = 0,
        )

        assertTrue(shouldReportProcessExit(identity, handledIdentity = null))
    }

    @Test
    fun `android reclaiming memory from the app in the background is no fault`() {
        // The two notices the operator saw: low memory, importance 400 (cached), activity stopped.
        assertTrue(isRoutineBackgroundExit(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 0, 400))
        assertTrue(isRoutineBackgroundExit(android.app.ApplicationExitInfo.REASON_SIGNALED, 9, 400))
        // In the foreground the same is worth reporting, and crashes always are.
        assertTrue(!isRoutineBackgroundExit(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 0, 100))
        assertTrue(!isRoutineBackgroundExit(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 11, 400))
        assertTrue(!isRoutineBackgroundExit(android.app.ApplicationExitInfo.REASON_SIGNALED, 11, 400))
    }
}
