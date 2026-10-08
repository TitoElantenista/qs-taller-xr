package com.qs.tallerxr.quality

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityBatchTest {
    private fun record(project: String, position: String, revision: Int = 1) =
        QualityRecord(piece().copy(key = "$project:$position:$revision", projectId = project, position = position, revision = revision),
            "2026-09-19T12:00:00Z")

    @Test fun selectedPositionsAreReadInProjectAndNaturalPositionOrder() {
        val records = listOf(record("B", "P1"), record("A", "B10"), record("A", "B2", 2), record("A", "B2", 1), record("A", "B1"))
        val ordered = qualityBatchOrder(records) { if (it.piece.projectId == "A") "Halle Nord" else "Halle Süd" }
        assertEquals(listOf("A:B1:1", "A:B2:1", "A:B2:2", "A:B10:1", "B:P1:1"), ordered.map { it.piece.key })
    }

    @Test fun fileNamesAreSafeAndCarryRevisionAndEdition() {
        val report = IssuedQualityReport("id", "QS-1", 3, "2026-09-20T08:20:00Z", "Europe/Berlin", "Anna",
            OrderDetails("p", "Projekt"), record("p", "IPE 270/1 · Süd", 2), "id.pdf")
        assertEquals("QS-Pruefbericht-IPE_270_1_Süd-Rev2-Stand03.pdf", qualityReportFileName(report))
        assertEquals("QS-Sammelbericht-Halle_Nord-2026-10-05.pdf",
            qualityCollectionFileName(listOf("Halle Nord", "Halle Nord"), LocalDate.of(2026, 10, 5)))
        assertEquals("QS-Sammelbericht-2026-10-05.pdf", qualityCollectionFileName(listOf("A", "B"), LocalDate.of(2026, 10, 5)))
    }
}
