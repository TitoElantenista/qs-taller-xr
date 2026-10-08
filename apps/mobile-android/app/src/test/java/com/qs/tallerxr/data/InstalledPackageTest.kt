package com.qs.tallerxr.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledPackageTest {
    @Test
    fun installedPackageResolvesItsFilesAndRevisionKey() {
        val registrationHash = "a".repeat(64)
        val installed = InstalledPackage(
            root = File("."),
            manifest = PackageManifest(
                schemaVersion = "2.0",
                packageId = "package-identity-r2",
                createdAt = "2026-08-11T00:00:00Z",
                producer = Producer("test", "1"),
                project = ProjectRef("project", "Project"),
                assembly = AssemblyRef("assembly", "900140.1", 2),
                coordinateSystem = CoordinateSystem(
                    "RIGHT_HANDED",
                    "Y",
                    "m",
                    "COLUMN_MAJOR",
                    "COLUMN_VECTOR",
                    "PARENT_FROM_CHILD",
                ),
                files = PackageFiles(
                    "assembly.glb",
                    "registration_model.bin",
                    "parts.json",
                    "thumbnail.png",
                    "checksums.json",
                ),
            ),
            parts = PartDocument("1.0", "assembly", emptyList()),
            checksums = ChecksumDocument(
                schemaVersion = "1.0",
                algorithm = "SHA-256",
                files = listOf(
                    ChecksumEntry("assembly.glb", "b".repeat(64), 100),
                    ChecksumEntry("registration_model.bin", registrationHash, 200),
                ),
            ),
        )

        assertEquals(File(".", "assembly.glb"), installed.modelFile)
        assertEquals(File(".", "registration_model.bin"), installed.registrationModelFile)
        assertEquals("project:assembly:2", installed.revisionKey())
    }
}
