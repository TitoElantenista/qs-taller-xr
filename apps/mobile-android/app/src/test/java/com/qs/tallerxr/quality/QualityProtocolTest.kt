package com.qs.tallerxr.quality

import org.junit.Assert.*
import org.junit.Test

class QualityProtocolTest {
    @Test fun protocolIncludesCurrentResultEveryPreviousAuthorAndEvidenceMetadata() {
        val record = QualityRecord(piece(), "2026-09-19T12:00:00Z", listOf(
            QualityEvent("1", "2026-09-19T12:00:00Z", "Europe/Berlin", "Anna", QualityStatus.DEFECT, "Bohrung versetzt"),
            QualityEvent("2", "2026-09-19T12:10:00Z", "Europe/Berlin", "Bernd", QualityStatus.OK, "Nacharbeit geprüft"),
            QualityEvent("3", "2026-09-19T12:12:00Z", "Europe/Berlin", "Clara", photo = QualityPhoto("a.jpg", "Beweis.jpg", "hash", "Vorläufig")),
        ))
        val text = qualityProtocolLines(record, "2026-09-19T12:15:00Z", "Daniel").joinToString("\n")
        listOf("Status: Geprüft – in Ordnung", "Zuletzt geprüft / geändert von: Bernd", "Anna", "Bernd", "Clara", "Daniel",
            "Bohrung versetzt", "Nacharbeit geprüft", "Beweis.jpg", "Vorläufig", piece().fingerprint, "14:10:00").forEach {
            assertTrue("Missing $it", text.contains(it))
        }
    }

    @Test fun aPhotoAloneDoesNotClaimAnInspectionTookPlace() {
        val record = QualityRecord(piece(), "2026-09-19T12:00:00Z", listOf(
            QualityEvent("1", "2026-09-19T12:12:00Z", "Europe/Berlin", "Anna", photo = QualityPhoto("a.jpg", "Beweis.jpg", "hash", "Vorläufig")),
        ))
        val text = qualityProtocolLines(record, "2026-09-19T12:15:00Z", "Anna").joinToString("\n")
        assertTrue(text.contains("noch keine Prüfung dokumentiert"))
        assertFalse(text.contains("Zuletzt geprüft / geändert von:"))
        assertEquals(QualityStatus.OPEN, record.status)
    }

    @Test fun longAttributesAndParagraphsWrapWithoutDroppingContentOrExceedingWidth() {
        val text = "Äußere Kante prüfen\n\n" + "a".repeat(64) + "\n" + "Kommentar ".repeat(400).trim()
        val lines = wrapQualityText(text, 24f) { it.length.toFloat() }
        assertTrue(lines.all { it.length <= 24 })
        assertTrue(lines.contains(""))
        assertEquals(text.filterNot { it.isWhitespace() }, lines.joinToString("").filterNot { it.isWhitespace() })
    }

    @Test fun timezoneAtTheTimeOfInspectionIsUsedForThePrintedClockTime() {
        assertTrue(qualityDate("2026-01-19T10:05:06Z", "Europe/Berlin").contains("11:05:06"))
        assertTrue(qualityDate("2026-09-19T10:05:06Z", "Europe/Berlin").contains("12:05:06"))
        assertTrue(qualityDate("2026-09-19T10:05:06Z", "Asia/Tokyo").contains("19:05:06"))
    }
}
