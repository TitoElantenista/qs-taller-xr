package com.qs.tallerxr.ui

import com.qs.tallerxr.data.AssemblyRef
import com.qs.tallerxr.data.Bounds
import com.qs.tallerxr.data.ChecksumDocument
import com.qs.tallerxr.data.ChecksumEntry
import com.qs.tallerxr.data.CoordinateSystem
import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.data.PackageFiles
import com.qs.tallerxr.data.PackageManifest
import com.qs.tallerxr.data.Part
import com.qs.tallerxr.data.PartDocument
import com.qs.tallerxr.data.Producer
import com.qs.tallerxr.data.ProjectRef
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RevisionComparisonTest {
    @Test
    fun classifiesAddedModifiedRemovedAndUnchangedParts() {
        val previous = installed(
            revision = 1,
            parts = listOf(
                part("guid-a", "191001.1", "S235"),
                part("guid-b", "191001.2", "S235"),
                part("guid-removed", "191001.3", "S235"),
            ),
        )
        val current = installed(
            revision = 2,
            parts = listOf(
                part("guid-a", "191001.1", "S235"),
                part("guid-b", "191001.2", "S355"),
                part("guid-new", "191001.4", "S235"),
            ),
        )

        val comparison = compareRevisions(current, previous)

        assertEquals(listOf("191001.4"), comparison.added.map(Part::positionNumber))
        assertEquals(listOf("191001.2"), comparison.modified.map(Part::positionNumber))
        assertEquals(listOf("191001.3"), comparison.removed.map(Part::positionNumber))
        assertEquals(1, comparison.unchangedCount)
        assertEquals(PartChange.UNCHANGED, comparison.currentChanges["guid-a"])
    }

    private fun part(guid: String, position: String, material: String) = Part(
        guid = guid,
        positionNumber = position,
        kind = "PLATE",
        gltfNodeName = "part-$guid",
        boundsAssemblyM = Bounds(listOf(0f, 0f, 0f), listOf(1f, 1f, 1f)),
        inspectable = true,
        properties = JsonObject(mapOf("Material" to JsonPrimitive(material))),
    )

    private fun installed(revision: Int, parts: List<Part>): InstalledPackage {
        val project = ProjectRef("00000000-0000-0000-0000-000000000001", "Test")
        val assembly = AssemblyRef("assembly-guid", "191001.1", revision)
        val manifest = PackageManifest(
            schemaVersion = "2.0",
            packageId = "00000000-0000-0000-0000-00000000000$revision",
            createdAt = "2026-07-04T00:00:00Z",
            producer = Producer("test", "1"),
            project = project,
            assembly = assembly,
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
        )
        return InstalledPackage(
            root = File("."),
            manifest = manifest,
            parts = PartDocument("1.0", assembly.guid, parts),
            checksums = ChecksumDocument(
                schemaVersion = "1.0",
                algorithm = "SHA-256",
                files = listOf(
                    ChecksumEntry(
                        path = "registration_model.bin",
                        sha256 = "0".repeat(64),
                        byteLength = 1,
                    )
                ),
            ),
        )
    }
}
