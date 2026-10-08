package com.qs.tallerxr.quality

import com.qs.tallerxr.projects.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QualitySourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun model(modified: Long = 1, state: ModelState = ModelState.READY): BrowserModel {
        val entry = SourceEntry("folder", emptyList(), "P01.glb", "content://source/P01", ModelFormat.GLB, 1000, modified)
        return BrowserModel("source-file", "P01", "", ModelFormat.GLB, state, ModelOrigin.FolderFile(entry))
    }

    @Test fun changedFileMetadataIsNotDisplayedAsThePreviouslyInspectedModel() = runBlocking {
        val store = QualityStore(File(temporary.root, "quality"))
        val original = model()
        store.register(piece(), qualitySourceVersion(original))
        val doc = store.addCheck(piece().key, QualityStatus.OK, "Anna", "")
        assertTrue(qualitySourceMatches(doc, "p", original))
        assertFalse(qualitySourceMatches(doc, "p", model(modified = 2)))
        assertFalse(qualitySourceMatches(doc, "other-project", original))
        assertEquals(QualityStatus.OK, doc.record(piece().key)!!.status)
    }

    @Test fun staleAndFailedModelPreparationCannotShowASilentApproval() = runBlocking {
        val store = QualityStore(File(temporary.root, "quality"))
        val doc = store.register(piece(), qualitySourceVersion(model()))
        assertFalse(qualitySourceMatches(doc, "p", model(state = ModelState.STALE)))
        assertFalse(qualitySourceMatches(doc, "p", model(state = ModelState.ERROR)))
    }

    @Test fun confirmedIdenticalGeometryRetainsTheInspectionAfterAFileTimestampChange() = runBlocking {
        val store = QualityStore(File(temporary.root, "quality"))
        store.register(piece(), qualitySourceVersion(model()))
        store.addCheck(piece().key, QualityStatus.OK, "Anna", "Original")
        val refreshed = store.register(piece(), qualitySourceVersion(model(modified = 2)))
        assertTrue(qualitySourceMatches(refreshed, "p", model(modified = 2)))
        assertEquals(1, refreshed.records.size)
        assertEquals(QualityStatus.OK, refreshed.record(piece().key)!!.status)
    }

    @Test fun newGeometryBecomesTheCurrentBindingWithoutOverwritingEarlierHistory() = runBlocking {
        val store = QualityStore(File(temporary.root, "quality"))
        store.register(piece(), qualitySourceVersion(model()))
        store.addCheck(piece().key, QualityStatus.OK, "Anna", "Original")
        val changed = piece("new-geometry")
        val doc = store.register(changed, qualitySourceVersion(model(modified = 2)))
        assertEquals(changed.key, doc.bindings[qualityBindingKey("p", "source-file")]!!.pieceKey)
        assertEquals(QualityStatus.OPEN, doc.record(changed.key)!!.status)
        assertEquals("Original", doc.record(piece().key)!!.lastCheck!!.comment)
    }
}
