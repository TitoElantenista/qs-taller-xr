package com.qs.tallerxr.ui

import com.qs.tallerxr.projects.ModelFormat
import com.qs.tallerxr.projects.SourceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderScanSummaryTest {
    private fun entry(name: String, vararg path: String, megabytes: Long = 2) = SourceEntry(
        folderId = "f", path = path.toList(), name = name, documentUri = "content://x/$name",
        format = ModelFormat.ofFileName(name)!!, sizeBytes = megabytes * 1024 * 1024, lastModified = 0L,
    )

    @Test
    fun countsModelsByFormatFoldersAndDepth() {
        val text = folderScanSummary("Halle Nord", listOf(
            entry("100201.2.ifc", "Los 1", "Stützen"),
            entry("100202.1.ifc", "Los 1", "Stützen"),
            entry("Träger-7.glb", "Los 1", "Träger"),
            entry("Übersicht.ifc"),
        ), truncated = false)
        assertEquals("«Halle Nord» verknüpft: 4 Modelle (1 GLB, 3 IFC) in 3 Ordnern, bis 2 Ebenen tief. " +
            "Die Dateien bleiben, wo sie sind.", text)
    }

    @Test
    fun filesDirectlyInTheFolder() {
        val text = folderScanSummary("Los 2", listOf(entry("A1.ifc")), truncated = false)
        assertEquals("«Los 2» verknüpft: 1 Modell (1 IFC) direkt im Ordner. Die Dateien bleiben, wo sie sind.", text)
    }

    @Test
    fun emptyFolderExplainsWhatIsRead() {
        val text = folderScanSummary("Downloads", emptyList(), truncated = false)
        assertTrue(text, text.startsWith("«Downloads» verknüpft: keine Modelle gefunden."))
        assertTrue(text, ".ifc" in text && ".glb" in text && "Unterordnern" in text)
        assertFalse(text, "bleiben, wo sie sind" in text)
    }

    @Test
    fun warnsAboutIfcFilesThatAreProbablyAWholeBuilding() {
        val text = folderScanSummary("Projekt", listOf(
            entry("Gebäude.ifc", megabytes = 180),
            entry("Halle.ifc", megabytes = 60),
            entry("Riesig.glb", megabytes = 300),
            entry("B1.ifc"),
        ), truncated = false)
        assertTrue(text, "2 IFC-Dateien sind größer als 50 MB («Gebäude.ifc»)" in text)
        assertTrue(text, "je Baugruppe eine Datei" in text)
        val single = folderScanSummary("Projekt", listOf(entry("Gebäude.ifc", megabytes = 51)), truncated = false)
        assertTrue(single, "Eine IFC-Datei ist größer als 50 MB" in single)
    }

    @Test
    fun relinkAndTruncation() {
        val text = folderScanSummary("Los 1", listOf(entry("A.glb", "x")), truncated = true, relinked = true)
        assertTrue(text, text.startsWith("«Los 1» neu verknüpft: 1 Modell (1 GLB) in 1 Ordner, 1 Ebene tief."))
        assertTrue(text, text.endsWith("Der Ordner ist sehr groß und wurde nicht vollständig gelesen."))
    }
}
