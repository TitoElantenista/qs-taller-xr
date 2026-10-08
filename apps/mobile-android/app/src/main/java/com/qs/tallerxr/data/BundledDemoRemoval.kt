package com.qs.tallerxr.data

/** Called only after a matching auto-install marker has been found, never based on a display name. */
internal fun isPreviouslyBundledDemo(summary: InstalledPackageSummary, seededPackageId: String): Boolean =
    summary.packageId == seededPackageId &&
        summary.project.projectId == "d6655dc6-fc60-5b13-a966-99af0c630ce0"
