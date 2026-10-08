package com.qs.tallerxr.projects

import com.qs.tallerxr.data.AssemblyRef
import com.qs.tallerxr.data.InstalledPackageSummary
import com.qs.tallerxr.data.Producer
import com.qs.tallerxr.data.ProjectRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTreeTest {
    private fun entry(path: String, name: String) = SourceEntry(
        path = path.split('/').filter(String::isNotEmpty),
        name = name,
        documentUri = "content://test/$path/$name",
        format = requireNotNull(ModelFormat.ofFileName(name)),
        sizeBytes = 1_000,
        lastModified = 1_700_000_000_000,
    )

    private fun describe(entry: SourceEntry) = BrowserModel(
        key = entry.key,
        title = entry.title,
        detail = entry.format.label,
        format = entry.format,
        state = ModelState.PENDING,
        origin = ModelOrigin.FolderFile(entry),
    )

    @Test
    fun `a folder without subfolders lists its files directly in the project`() {
        val tree = ProjectTree.fromEntries(
            "Nave 3",
            listOf(entry("", "P10.ifc"), entry("", "P2.glb"), entry("", "p1.IFC")),
            ::describe,
        )
        assertTrue(tree.folders.isEmpty())
        // Natural order: P2 comes before P10, whatever the letter case.
        assertEquals(listOf("p1", "P2", "P10"), tree.models.map(BrowserModel::title))
        assertEquals(3, tree.modelCount)
    }

    @Test
    fun `subfolders classify the models level by level down to the files`() {
        val tree = ProjectTree.fromEntries(
            "Nave 3",
            listOf(
                entry("Lote 2/Plano 200", "200.1.ifc"),
                entry("Lote 2/Plano 200", "200.2.ifc"),
                entry("Lote 2/Plano 210", "210.1.glb"),
                entry("Lote 10", "suelta.glb"),
                entry("", "general.glb"),
            ),
            ::describe,
        )
        assertEquals(listOf("Lote 2", "Lote 10"), tree.folders.map(BrowserFolder::name))
        assertEquals(listOf("general"), tree.models.map(BrowserModel::title))
        val lot = tree.resolve(listOf("Lote 2"))
        assertEquals(listOf("Plano 200", "Plano 210"), lot.folders.map(BrowserFolder::name))
        assertTrue(lot.models.isEmpty())
        assertEquals(3, lot.modelCount)
        assertEquals(
            listOf("200.1", "200.2"),
            tree.resolve(listOf("Lote 2", "Plano 200")).models.map(BrowserModel::title),
        )
        assertEquals(5, tree.modelCount)
    }

    @Test
    fun `one linked folder shows its content directly, several become the first level`() {
        fun folder(id: String, label: String) = LinkedFolder(id, "content://tree/$id", label, "2026-09-19T00:00:00Z")
        val hall = entry("Plano 1", "a.ifc").copy(folderId = "f1")
        val yard = entry("", "b.glb").copy(folderId = "f2")
        val twin = entry("", "c.glb").copy(folderId = "f3")

        val single = ProjectTree.fromLinkedFolders("P", listOf(folder("f1", "Halle")), listOf(hall), ::describe)
        assertEquals(listOf("Plano 1"), single.folders.map(BrowserFolder::name))

        val several = ProjectTree.fromLinkedFolders(
            "P",
            listOf(folder("f1", "Halle"), folder("f2", "Hof"), folder("f3", "Hof")),
            listOf(hall, yard, twin),
            ::describe,
        )
        // Two linked folders may carry the same name; they still are two places.
        assertEquals(listOf("Halle", "Hof", "Hof (2)"), several.folders.map(BrowserFolder::name))
        assertEquals(listOf("a"), several.resolve(listOf("Halle", "Plano 1")).models.map(BrowserModel::title))
        assertEquals(3, several.modelCount)

        val empty = ProjectTree.fromLinkedFolders("P", emptyList(), emptyList(), ::describe)
        assertEquals(0, empty.modelCount)
    }

    @Test
    fun `the same file name in two linked folders is two different models`() {
        assertNotEquals(entry("A", "x.glb").copy(folderId = "f1").key, entry("A", "x.glb").copy(folderId = "f2").key)
    }

    @Test
    fun `a path that no longer exists falls back to the deepest folder that does`() {
        val tree = ProjectTree.fromEntries("P", listOf(entry("A/B", "x.glb")), ::describe)
        assertEquals(listOf("A", "B"), tree.resolve(listOf("A", "B")).path)
        assertEquals(listOf("A"), tree.resolve(listOf("A", "borrada", "C")).path)
        assertEquals(emptyList<String>(), tree.resolve(listOf("otra")).path)
    }

    @Test
    fun `search looks below the current folder and matches folder names too`() {
        val tree = ProjectTree.fromEntries(
            "P",
            listOf(entry("Lote 2/Pilares", "S1.ifc"), entry("Lote 2/Vigas", "pilar-raro.glb"), entry("Lote 3", "V9.glb")),
            ::describe,
        )
        val found = tree.search("pilar").map { (model, path) -> model.title to path.joinToString("/") }
        assertEquals(setOf("S1" to "Lote 2/Pilares", "pilar-raro" to "Lote 2/Vigas"), found.toSet())
        assertTrue(tree.search("  ").isEmpty())
        assertTrue(tree.resolve(listOf("Lote 3")).search("pilar").isEmpty())
    }

    @Test
    fun `only glb and ifc files are models and their identity follows the path`() {
        assertEquals(ModelFormat.GLB, ModelFormat.ofFileName("viga.GLB"))
        assertEquals(ModelFormat.IFC, ModelFormat.ofFileName("plano.200.ifc"))
        assertNull(ModelFormat.ofFileName("plano.pdf"))
        assertNull(ModelFormat.ofFileName("sin-extension"))
        assertEquals(entry("A", "x.glb").key, entry("A", "x.glb").key)
        assertNotEquals(entry("A", "x.glb").key, entry("B", "x.glb").key)
        assertEquals(24, entry("A", "x.glb").key.length)
    }

    @Test
    fun `a qsproject is browsed by Zeichnung with the newest revision first`() {
        fun summary(position: String, revision: Int) = InstalledPackageSummary(
            packageId = "$position-r$revision",
            createdAt = "2026-08-01T00:00:00Z",
            producer = Producer("test", "1"),
            project = ProjectRef("project-a", "Alpha"),
            assembly = AssemblyRef(position, position, revision),
            partCount = 4,
            archiveBytes = 1_024,
        )
        val tree = ProjectTree.fromInstalled(
            "Alpha",
            listOf(summary("100001.10", 1), summary("100001.2", 1), summary("100001.2", 3), summary("100002.1", 1)),
        ) { revision ->
            BrowserModel(
                key = revision.packageId,
                title = "${revision.assembly.positionNumber}/R${revision.assembly.revision}",
                detail = "",
                format = ModelFormat.QSXR,
                state = ModelState.READY,
                origin = ModelOrigin.InstalledRevision(revision),
            )
        }
        assertEquals(listOf("Zeichnung 100001", "Zeichnung 100002"), tree.folders.map(BrowserFolder::name))

        // The demo project holds one model per Zeichnung: they are listed without a folder each.
        val flat = ProjectTree.fromInstalled("Demo", listOf(summary("IPE270.1", 1), summary("HEB140.1", 1))) { revision ->
            BrowserModel(revision.packageId, revision.assembly.positionNumber, "", ModelFormat.QSXR, ModelState.READY, ModelOrigin.InstalledRevision(revision))
        }
        assertTrue(flat.folders.isEmpty())
        assertEquals(listOf("HEB140.1", "IPE270.1"), flat.models.map(BrowserModel::title))
        assertEquals(
            listOf("100001.2/R3", "100001.2/R1", "100001.10/R1"),
            tree.folders.first().models.map(BrowserModel::title),
        )
    }
}
