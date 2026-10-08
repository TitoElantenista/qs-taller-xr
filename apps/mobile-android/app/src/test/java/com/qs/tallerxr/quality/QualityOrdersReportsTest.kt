package com.qs.tallerxr.quality

import com.qs.tallerxr.data.JsonCodec
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QualityOrdersReportsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = File(temporary.root, "quality")
    private fun store() = QualityStore(root())
    private val action = QualityAction("Bohrung nacharbeiten", "Tobias Wagner", "2026-09-24")

    @Test fun upgradePreservesPreviousDatabaseAndStartsAnIndependentV2Document() = runBlocking {
        val old = QualityDocument(schemaVersion = 1, inspector = "Anna", legacyImported = true,
            records = listOf(QualityRecord(piece(), "2026-09-19T12:00:00Z", listOf(
                QualityEvent("check", "2026-09-19T12:00:00Z", "Europe/Berlin", "Anna", QualityStatus.DEFECT, "Kratzer"),
                QualityEvent("photo", "2026-09-19T12:01:00Z", "Europe/Berlin", "Bernd",
                    photo = QualityPhoto("a.jpg", "Beleg.jpg", "digest", "Verankert")),
            ))), bindings = mapOf("p:source-file" to QualityBinding(piece().key, "version")))
        val oldFile = File(root().apply { mkdirs() }, "quality.json")
        val bytes = JsonCodec.value.encodeToString(old).toByteArray()
        oldFile.writeBytes(bytes)
        val migrated = store().load()
        assertEquals(2, migrated.schemaVersion)
        assertEquals(old.records, migrated.records)
        assertEquals(old.bindings, migrated.bindings)
        assertEquals("Anna", migrated.inspector)
        store().saveOrder(OrderDetails("p", "Neuer Projektname"))
        assertArrayEquals(bytes, oldFile.readBytes())
        // A downgrade may still edit v1. Those edits must not overwrite the newer v2 audit.
        oldFile.writeText(JsonCodec.value.encodeToString(old.copy(inspector = "Alter Client")))
        val restored = store().load()
        assertEquals("Anna", restored.inspector)
        assertEquals("Neuer Projektname", restored.orders["p"]!!.projectName)
        assertEquals(old.records, restored.records)
    }

    @Test fun corruptV2IsNotReplacedByTheOlderBackup() = runBlocking {
        root().mkdirs()
        File(root(), "quality.json").writeText(JsonCodec.value.encodeToString(QualityDocument(schemaVersion = 1)))
        val current = File(root(), "quality-v2.json").apply { writeText("{broken") }
        assertTrue(runCatching { store().load() }.isFailure)
        assertTrue(runCatching { store().saveOrder(OrderDetails("p", "Projekt")) }.isFailure)
        assertEquals("{broken", current.readText())
    }

    @Test fun orderEditsSurviveRestartWithoutChangingChecksBindingsOrGeometryIdentity() = runBlocking {
        val store = store()
        store.register(piece(), "v1")
        val before = store.addCheck(piece().key, QualityStatus.OK, "Anna", "Geprüft")
        store.saveOrder(OrderDetails("p", "  Halle Nord  ", " A-25 ", " P-45 ", " Kunde ", " Lena ",
            " Max ", " Berlin ", " Werkstatt ", " Bemerkung "))
        val after = store().load()
        val order = after.orders["p"]!!
        assertEquals("Halle Nord", order.projectName)
        assertEquals("A-25", order.orderNumber)
        assertEquals("Bemerkung", order.notes)
        assertNotNull(Instant.parse(order.updatedAt))
        assertEquals(before.records, after.records)
        assertEquals(before.bindings, after.bindings)
        assertEquals("Anna", after.record(piece().key)!!.lastCheck!!.inspector)
        assertEquals("Werkstatt", after.record(piece().key)!!.piece.projectName)
    }

    @Test fun invalidOrderCannotReplacePreviouslySavedValues() = runBlocking {
        val store = store()
        store.saveOrder(OrderDetails("p", "Projekt"))
        listOf(OrderDetails("p", " "), OrderDetails("", "Projekt"), OrderDetails("p", "x".repeat(201)),
            OrderDetails("p", "Projekt", notes = "x".repeat(4001))).forEach { order ->
            assertTrue(runCatching { store.saveOrder(order) }.isFailure)
        }
        assertEquals("Projekt", store().load().orders["p"]!!.projectName)
    }

    @Test fun actionsPersistAcrossCommentsPhotosAndStatusChangesUntilExplicitlyClosed() = runBlocking {
        val store = store()
        store.register(piece())
        store.addCheck(piece().key, QualityStatus.DEFECT, "Anna", "Bohrung versetzt", action)
        store.addCheck(piece().key, null, "Bernd", "Rücksprache erfolgt")
        store.addPhoto(piece().key, "Anna", Instant.now().toString(), "foto.jpg", "Verankert") {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
        store.addCheck(piece().key, QualityStatus.REWORK, "Clara", "Bearbeitung beginnt")
        assertEquals(action, store().load().record(piece().key)!!.currentAction)
        store.addCheck(piece().key, QualityStatus.OK, "Daniel", "Nacharbeit geprüft")
        assertNull(store().load().record(piece().key)!!.currentAction)
        store.addCheck(piece().key, QualityStatus.RECHECK, "Anna", "Weitere Prüfung")
        val restored = store().load().record(piece().key)!!
        assertNull(restored.currentAction)
        assertEquals(action, restored.events.first().action)
    }

    @Test fun anActionCanBeChangedOrClearedWithoutChangingInspectionStatus() = runBlocking {
        val store = store()
        store.register(piece())
        store.addCheck(piece().key, QualityStatus.REWORK, "Anna", "", action)
        val updated = action.copy(assignee = "Bernd", dueDate = "2026-09-25")
        store.addCheck(piece().key, null, "Anna", "", updated)
        assertEquals(updated, store().load().record(piece().key)!!.currentAction)
        store.addCheck(piece().key, null, "Anna", "", QualityAction())
        val record = store().load().record(piece().key)!!
        assertNull(record.currentAction)
        assertEquals(QualityStatus.REWORK, record.status)
        assertEquals(listOf(action, updated, QualityAction()), record.events.map { it.action })
    }

    @Test fun reopeningClearsPreviousActionWithoutErasingItFromHistory() = runBlocking {
        val store = store()
        store.register(piece())
        store.addCheck(piece().key, QualityStatus.REWORK, "Anna", "", action)
        val document = store.addCheck(piece().key, QualityStatus.OPEN, "Bernd", "Erneut prüfen")
        assertNull(document.record(piece().key)!!.currentAction)
        assertEquals(action, document.record(piece().key)!!.events.first().action)
    }

    @Test fun datesAreStrictAndOptional() {
        assertEquals("", parseQualityActionDate(" "))
        assertEquals("2028-02-29", parseQualityActionDate("29.02.2028"))
        assertEquals("24.09.2026", qualityActionDate("2026-09-24"))
        listOf("31.02.2026", "29.02.2026", "24/09/2026", "2026-09-24", "32.01.2026").forEach {
            assertTrue(it, runCatching { parseQualityActionDate(it) }.isFailure)
        }
        assertTrue(runCatching { action.copy(dueDate = "2026-02-31").normalized() }.isFailure)
        assertTrue(runCatching { action.copy(description = "x".repeat(2001)).normalized() }.isFailure)
    }

    @Test fun issuedReportsKeepExactBytesMetadataAndHistoryWhenCurrentDataChange() = runBlocking {
        val store = store()
        store.register(piece())
        store.saveOrder(OrderDetails("p", "Halle Nord", "A-25", projectResponsible = "Lena"))
        store.addCheck(piece().key, QualityStatus.REWORK, "Anna", "Bohrung versetzt", action)
        val first = store.issueReport(piece().key, "Fallback") { report, output ->
            assertEquals("Halle Nord", report.order.projectName)
            assertEquals(QualityStatus.REWORK, report.record.status)
            output.write("%PDF-1.4\nfirst immutable bytes\n%%EOF".toByteArray())
        }.second
        val firstBytes = store.verifyReportFile(first).readBytes()
        store.saveOrder(OrderDetails("p", "Halle Süd", "A-26", projectResponsible = "Max"))
        store.addCheck(piece().key, QualityStatus.OK, "Bernd", "Nacharbeit geprüft")
        val second = store.issueReport(piece().key, "Fallback") { _, output -> output.write("%PDF-1.4\nsecond\n%%EOF".toByteArray()) }.second
        val restored = store().load()
        assertEquals(listOf(first, second), restored.reports)
        assertEquals(1, first.edition)
        assertEquals(2, second.edition)
        assertEquals(first.number, second.number)
        assertNotEquals(first.pdfFileName, second.pdfFileName)
        assertEquals("Anna", first.exporter)
        assertEquals("Bernd", second.exporter)
        assertEquals("Halle Nord", first.order.projectName)
        assertEquals("Halle Süd", second.order.projectName)
        assertEquals(1, first.record.events.size)
        assertEquals(2, second.record.events.size)
        assertEquals(action, first.record.currentAction)
        assertNull(second.record.currentAction)
        assertEquals(qualityDigest(firstBytes), first.sha256)
        assertArrayEquals(firstBytes, store().verifyReportFile(restored.reports.first()).readBytes())
        assertEquals("${first.number}-Stand-01.pdf", first.suggestedFileName)
    }

    @Test fun concurrentIssuesHaveUniqueSequentialEditionsPerModel() = runBlocking {
        val store = store()
        store.register(piece())
        val reports = (1..6).map { async {
            store.issueReport(piece().key, "Projekt") { _, out -> out.write("PDF".toByteArray()) }.second
        } }.awaitAll()
        assertEquals((1..6).toList(), reports.map { it.edition }.sorted())
        assertEquals(6, reports.map { it.id }.distinct().size)
        store.register(piece(revision = 2))
        val next = store.issueReport(piece(revision = 2).key, "Projekt") { _, out -> out.write("PDF".toByteArray()) }.second
        assertEquals(1, next.edition)
        assertNotEquals(reports.first().number, next.number)
    }

    @Test fun failedRenderOrCatalogCommitDoesNotPublishAPartialReport() = runBlocking {
        val store = store()
        store.register(piece())
        val before = File(root(), "quality-v2.json").readBytes()
        assertTrue(runCatching {
            store.issueReport(piece().key, "Projekt") { _, out -> out.write("partial".toByteArray()); error("render failed") }
        }.isFailure)
        assertTrue(File(root(), "reports").listFiles()!!.isEmpty())
        File(root(), "quality-v2.json.tmp").mkdir()
        assertTrue(runCatching { store.issueReport(piece().key, "Projekt") { _, out -> out.write("PDF".toByteArray()) } }.isFailure)
        assertTrue(File(root(), "reports").listFiles()!!.isEmpty())
        assertArrayEquals(before, File(root(), "quality-v2.json").readBytes())
        assertTrue(store.load().reports.isEmpty())
    }

    @Test fun missingOrChangedArchivedPdfIsDetectedInsteadOfRecreatedWithNewData() = runBlocking {
        val store = store()
        store.register(piece())
        val report = store.issueReport(piece().key, "Projekt") { _, out -> out.write("PDF".toByteArray()) }.second
        store.reportFile(report).writeText("changed")
        assertTrue(runCatching { store.verifyReportFile(report) }.isFailure)
        store.reportFile(report).delete()
        assertTrue(runCatching { store.verifyReportFile(report) }.isFailure)
        assertTrue(runCatching { store.reportFile(report.copy(pdfFileName = "../../data.pdf")) }.isFailure)
        assertEquals(report, store.load().reports.single())
    }
}
