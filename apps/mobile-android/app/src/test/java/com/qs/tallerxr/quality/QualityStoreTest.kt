package com.qs.tallerxr.quality

import com.qs.tallerxr.data.AssemblyCheckRecord
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QualityStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = File(temporary.root, "quality")
    private fun store() = QualityStore(root())

    @Test fun inspectionChangesRetainAuthorsCommentsAndTimestampsAcrossRestart() = runBlocking {
        val store = store()
        store.register(piece())
        store.addCheck(piece().key, QualityStatus.DEFECT, "  Anna Müller  ", "Bohrung versetzt")
        store.addCheck(piece().key, QualityStatus.REWORK, "Bernd", "Bohrung korrigieren")
        store.addCheck(piece().key, QualityStatus.OK, "Clara", "Nacharbeit geprüft")
        store.saveInspector("Daniel")
        val restored = store().load()
        val record = restored.record(piece().key)!!
        assertEquals(QualityStatus.OK, record.status)
        assertEquals(listOf("Anna Müller", "Bernd", "Clara"), record.events.map { it.inspector })
        assertEquals(listOf("Bohrung versetzt", "Bohrung korrigieren", "Nacharbeit geprüft"), record.events.map { it.comment })
        record.events.forEach { assertNotNull(Instant.parse(it.at)); assertTrue(it.zoneId.isNotBlank()) }
        assertEquals("Daniel", restored.inspector)
        assertEquals(3, record.events.map { it.id }.distinct().size)
    }

    @Test fun reopenAndCommentsDoNotEraseOrSilentlyApprovePreviousFindings() = runBlocking {
        val store = store()
        store.register(piece())
        store.addCheck(piece().key, QualityStatus.DEFECT, "Anna", "Mangel")
        var doc = store.addCheck(piece().key, null, "Bernd", "Rücksprache erforderlich")
        assertEquals(QualityStatus.DEFECT, doc.record(piece().key)!!.status)
        doc = store.addCheck(piece().key, QualityStatus.OPEN, "Bernd", "Prüfung wieder geöffnet")
        assertEquals(QualityStatus.OPEN, doc.record(piece().key)!!.status)
        assertEquals(3, doc.record(piece().key)!!.events.size)
    }

    @Test fun newGeometryOrRevisionStartsOpenAndLeavesTheOldRecordAccessible() = runBlocking {
        val store = store()
        val old = piece()
        store.register(old)
        store.addCheck(old.key, QualityStatus.OK, "Anna", "Passt")
        store.register(old.copy(projectName = "Umbenannt"))
        assertEquals(QualityStatus.OK, store.load().record(old.key)!!.status)
        val changed = piece("different geometry")
        store.register(changed)
        val nextRevision = piece(revision = 2)
        val doc = store.register(nextRevision)
        assertEquals(3, doc.records.size)
        assertEquals(QualityStatus.OPEN, doc.record(changed.key)!!.status)
        assertEquals(QualityStatus.OPEN, doc.record(nextRevision.key)!!.status)
        assertEquals("Werkstatt", doc.record(old.key)!!.piece.projectName)
        assertEquals("Passt", doc.record(old.key)!!.lastCheck!!.comment)
    }

    @Test fun photoIsPrivatelyCopiedAttributedAndDoesNotChangeInspectionStatus() = runBlocking {
        val store = store()
        store.register(piece())
        store.addCheck(piece().key, QualityStatus.REWORK, "Anna", "Nacharbeit")
        val original = temporary.newFile("gallery.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val at = "2026-09-19T18:11:42Z"
        val document = store.addPhoto(piece().key, "Bernd", at, "AR-Foto.jpg", "Vorläufig") { original.inputStream() }
        original.delete()
        val record = document.record(piece().key)!!
        assertEquals(QualityStatus.REWORK, record.status)
        assertEquals("Anna", record.lastCheck!!.inspector)
        val evidence = record.photos.single()
        assertEquals(at, evidence.at)
        assertEquals("Bernd", evidence.inspector)
        val photo = evidence.photo!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.photoFile(photo).readBytes())
        assertEquals(qualityDigest(byteArrayOf(1, 2, 3, 4)), photo.sha256)
        assertEquals(photo, store().load().record(piece().key)!!.photos.single().photo)
    }

    @Test fun legacyMarksAreMigratedOnceWithoutInventingAnInspectorOrAPassingResult() = runBlocking {
        val previous = AssemblyCheckRecord("p:g:1", "p", "g", "P01", 1, "2026-09-01T10:00:00Z", "Kratzer")
        val store = store()
        store.load(listOf(previous))
        val doc = store.load(listOf(previous))
        assertEquals(1, doc.records.size)
        val legacy = doc.records.single()
        assertEquals(QualityStatus.LEGACY, legacy.status)
        assertEquals("Nicht erfasst (Altbestand)", legacy.lastCheck!!.inspector)
        assertEquals(previous.checkedAt, legacy.lastCheck!!.at)
        assertEquals("Kratzer", legacy.lastCheck!!.comment)
        assertEquals("", legacy.piece.fingerprint)
        val opened = store.register(piece())
        assertEquals(QualityStatus.OPEN, opened.record(piece().key)!!.status)
    }

    @Test fun corruptHistoryCannotBeReplacedByASaveOrLoad() = runBlocking {
        val dir = root().apply { mkdirs() }
        val file = File(dir, "quality.json").apply { writeText("{broken QS data") }
        val store = store()
        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue(runCatching { store.saveInspector("Anna") }.isFailure)
        assertTrue(runCatching { store.register(piece()) }.isFailure)
        assertEquals("{broken QS data", file.readText())
    }

    @Test fun unknownFutureSchemaIsPreserved() = runBlocking {
        val file = File(root().apply { mkdirs() }, "quality.json")
        val data = """{"schemaVersion":99,"records":[]}"""
        file.writeText(data)
        assertTrue(runCatching { store().saveInspector("Anna") }.isFailure)
        assertEquals(data, file.readText())
    }

    @Test fun simultaneousSavesKeepAllEvents() = runBlocking {
        val store = store()
        store.register(piece())
        (1..30).map { index -> async { store.addCheck(piece().key, null, "Prüfer $index", "Kommentar $index") } }.awaitAll()
        assertEquals(30, store.load().record(piece().key)!!.events.size)
    }

    @Test fun failedPhotoCommitRetainsOldHistoryAndRemovesUnreferencedNewImage() = runBlocking {
        val store = store()
        store.register(piece())
        store.addCheck(piece().key, QualityStatus.OK, "Anna", "Geprüft")
        val previous = File(root(), "quality-v2.json").readBytes()
        File(root(), "quality-v2.json.tmp").mkdir()
        val result = runCatching {
            store.addPhoto(piece().key, "Anna", Instant.now().toString(), "foto.jpg", "Verankert") {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }
        }
        assertTrue(result.isFailure)
        assertArrayEquals(previous, File(root(), "quality-v2.json").readBytes())
        assertEquals(0, File(root(), "photos").listFiles()!!.size)
    }

    @Test fun unreadablePhotoSourceDoesNotCreateAnEventOrLeaveAnEmptyFile() = runBlocking {
        val store = store()
        store.register(piece())
        assertTrue(runCatching {
            store.addPhoto(piece().key, "Anna", Instant.now().toString(), "foto.jpg", "Verankert") { error("Lesefehler") }
        }.isFailure)
        assertTrue(store.load().record(piece().key)!!.events.isEmpty())
        assertTrue(File(root(), "photos").listFiles()!!.isEmpty())
    }

    @Test fun invalidEntriesAndPhotoPathsAreRejected() = runBlocking {
        val store = store()
        store.register(piece())
        assertTrue(runCatching { store.addCheck(piece().key, QualityStatus.OK, " ", "") }.isFailure)
        assertTrue(runCatching { store.addCheck(piece().key, null, "Anna", " ") }.isFailure)
        assertTrue(runCatching { store.addCheck("missing", QualityStatus.OK, "Anna", "") }.isFailure)
        assertTrue(runCatching { store.addCheck(piece().key, QualityStatus.LEGACY, "Anna", "") }.isFailure)
        assertTrue(runCatching { store.addCheck(piece().key, null, "Anna", "x".repeat(4001)) }.isFailure)
        assertTrue(runCatching { store.photoFile(QualityPhoto("../../quality.json", "", "", "")) }.isFailure)
        assertTrue(store.load().record(piece().key)!!.events.isEmpty())
    }

    @Test fun unicodeAndNestedAttributeLabelsSurviveReload() = runBlocking {
        val store = store()
        val model = piece().copy(parts = listOf(QualityPart("Träger 01", "guid", "Träger", "100,0 × 20,0 × 30,0 mm",
            mapOf("Material · Güte" to "S355J2+N", "Bemerkung" to "Öffnung prüfen\nZweite Zeile"))))
        store.register(model)
        store.addCheck(model.key, QualityStatus.RECHECK, "Jörg Groß", "Äußere Kante prüfen ✓")
        val restored = store().load().record(model.key)!!
        assertEquals(model, restored.piece)
        assertEquals("Äußere Kante prüfen ✓", restored.lastCheck!!.comment)
    }
}

internal fun piece(content: String = "geometry-and-attributes", revision: Int = 1): QualityPiece {
    val hash = qualityDigest(content.toByteArray())
    val revisionKey = "p:g:$revision"
    return QualityPiece(qualityPieceKey(revisionKey, hash), revisionKey, "source-file", "p", "Werkstatt", "P01", "g", revision,
        hash, "Stahlbau › Träger.glb", "2026-09-19T12:00:00Z")
}
