package com.qs.tallerxr.quality

import com.qs.tallerxr.projects.NaturalOrder
import java.time.LocalDate

/** Selected positions in reading order: by project, then naturally by position (B2 before B10). */
fun qualityBatchOrder(records: List<QualityRecord>, projectName: (QualityRecord) -> String): List<QualityRecord> =
    records.sortedWith(compareBy(NaturalOrder, projectName).thenBy(NaturalOrder) { it.piece.position }
        .thenBy { it.piece.revision })

/** File name of one position's report when several are saved into a folder. */
fun qualityReportFileName(report: IssuedQualityReport): String =
    "QS-Pruefbericht-${fileSafe(report.record.piece.position)}-Rev${report.record.piece.revision}-" +
        "Stand${report.edition.toString().padStart(2, '0')}.pdf"

/** Suggested name of a collected report; the project only when all positions share one. */
fun qualityCollectionFileName(projectNames: Collection<String>, date: LocalDate): String =
    "QS-Sammelbericht-" + (projectNames.distinct().singleOrNull()?.let { "${fileSafe(it)}-" } ?: "") + "$date.pdf"

private fun fileSafe(value: String): String =
    value.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(70).ifEmpty { "Position" }
