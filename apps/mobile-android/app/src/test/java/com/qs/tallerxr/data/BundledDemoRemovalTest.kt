package com.qs.tallerxr.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDemoRemovalTest {
    private val seededId = "767bc082-0a02-5d83-b00d-178dd58447eb"
    private val demo = InstalledPackageSummary(
        packageId = seededId,
        createdAt = "2026-08-11T00:00:00Z",
        producer = Producer("Test", "1"),
        project = ProjectRef("d6655dc6-fc60-5b13-a966-99af0c630ce0", "Demo · Testmodelle"),
        assembly = AssemblyRef("beam", "HEB140.1", 1),
        partCount = 1,
        archiveBytes = 1L,
    )

    @Test fun `the original bundled sample is removable even after a display name change`() {
        assertTrue(isPreviouslyBundledDemo(demo, seededId))
        assertTrue(isPreviouslyBundledDemo(demo.copy(project = demo.project.copy(name = "Umbenannt")), seededId))
    }

    @Test fun `a customer project called Demo is preserved even if a package id matches`() {
        val ownProject = demo.copy(project = ProjectRef("customer-project", "Demo · Testmodelle"))
        assertFalse(isPreviouslyBundledDemo(ownProject, seededId))
    }

    @Test fun `additional packages in the demo project are preserved`() {
        assertFalse(isPreviouslyBundledDemo(demo.copy(packageId = "customer-package"), seededId))
    }
}
