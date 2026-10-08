package com.qs.tallerxr.quality

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class QualityReportLayoutTest {
    // The Android compile bootclasspath omits AWT. The desktop JVM still supplies it
    // at test runtime; reflection keeps desktop APIs out of the APK and its dependencies.
    private val fontType = Class.forName("java.awt.Font")
    private val contextType = Class.forName("java.awt.font.FontRenderContext")
    private val context = contextType.getConstructor(Class.forName("java.awt.geom.AffineTransform"),
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).newInstance(null, true, true)
    private val regular = font("Regular", 0)
    private val bold = font("Bold", 1)
    private val fonts = mutableMapOf<Pair<Float, Boolean>, Any>()
    private fun font(style: String, fallback: Int): Any {
        val file = File("/usr/share/fonts/noto/NotoSans-$style.ttf")
        return if (file.isFile) fontType.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java).invoke(null, 0, file)
            else fontType.getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance("SansSerif", fallback, 10)
    }
    private fun engine() = QualityReportLayout({ text, size, isBold ->
        val font = fonts.getOrPut(size to isBold) {
            checkNotNull(fontType.getMethod("deriveFont", Float::class.javaPrimitiveType).invoke(if (isBold) bold else regular, size))
        }
        val bounds = fontType.getMethod("getStringBounds", String::class.java, contextType).invoke(font, text, context)
        (bounds.javaClass.getMethod("getWidth").invoke(bounds) as Number).toFloat()
    }, "QS Taller XR 0.32.0")
    private val portrait = ReportImageSize(1080, 2340)
    private val wideLogo = ReportImageSize(600, 180)
    private fun layout(report: IssuedQualityReport = sampleReport(), sizes: Map<String, ReportImageSize> =
        report.record.photos.associate { it.id to portrait }, logo: ReportImageSize? = null) = engine().build(report, sizes, logo)

    private fun text(layout: QualityReportPages) = layout.pages.flatMap { it.drawings }.filter { it.kind == "text" }.joinToString("\n") { it.text }
    private fun compact(text: String) = text.filterNot(Char::isWhitespace)
    private fun photos(layout: QualityReportPages) = layout.pages.flatMap { it.drawings }.filter { it.kind == "photo" }

    /** Inside the sheet; content between header and footer never runs into the footer. */
    private fun fits(layout: QualityReportPages) {
        layout.pages.forEachIndexed { index, page ->
            page.drawings.forEach { drawing ->
                val label = "Page ${index + 1}: ${drawing.kind} ${drawing.text} at (${drawing.x}, ${drawing.y}) ${drawing.width}×${drawing.height}"
                assertTrue(label, drawing.x >= 0 && drawing.y >= 0)
                assertTrue(label, drawing.x + drawing.width <= layout.width - 33f || drawing.kind == "rect" && drawing.y < 5f)
                assertTrue(label, drawing.y + drawing.height <= layout.height - 12f)
                if (drawing.y in 30f..805f) assertTrue(label, drawing.y + drawing.height <= 797f)
            }
        }
    }

    @Test fun aPositionWithTwoPhotosAndItsHistoryFitsOnOnePage() {
        val report = sampleReport()
        val result = layout(report, logo = wideLogo)
        // Saved geometry feeds the offline visual preview, not a second mockup layout.
        File("build/reports/quality-layout-preview.json").apply { parentFile?.mkdirs() }
            .writeText(previewJson.encodeToString(result))
        fits(result)
        assertEquals("Ein Bericht mit zwei Fotos gehört auf eine Seite", 1, result.pages.size)
        val text = text(result)
        listOf("Position IPE270.1", "Stahlbau · Musterauftrag", "A-2026-0148", "Lena Hoffmann", "Musterkunde GmbH",
            "Anna Keller", "Tobias Wagner", "Max Berger", "Nachkontrolle erforderlich", "Termin: 24.09.2026",
            "Bohrung nacharbeiten.", "135,0 × 270,0 × 200,0 mm", "900270.501", "Hauptbauteil", "IPE270", "S355",
            "FOTODOKUMENTATION", "PRÜFVERLAUF", "Foto 2 · Verankert", "Seite 1 / 1", "QS-MUSTER-0148 · Stand 02",
            "AR-IPE270.1-Ansicht-B.jpg", "BUNDLED-IPE270-200", "IfcBeam", "QS Taller XR 0.32.0").forEach {
            assertTrue("Missing $it", compact(text).contains(compact(it)))
        }
        assertTrue(compact(text).contains(report.record.piece.fingerprint))
        // Left out on request: photo hashes, the project ID, a person or entry column.
        listOf("a".repeat(64), "Projekt-ID", "AR-Foto 1").forEach { assertFalse("Still there: $it", text.contains(it)) }
        assertFalse(result.pages.flatMap { it.drawings }.any { it.text == "Person" || it.text == "Eintrag" })
        // The logo stands top left on the first page.
        val logo = result.pages.first().drawings.single { it.kind == "logo" }
        assertEquals(34f, logo.x, 0.01f)
        assertEquals(40f / 180f * 600f, logo.width, 0.5f)
        assertEquals(listOf("photo-1", "photo-2"), photos(result).map { it.imageId })
        assertEquals(photos(result).first().height, photos(result).last().height)
    }

    @Test fun oneSnapshotIsOnePage() {
        val report = sampleReport()
        val events = report.record.events.filter { it.id != "photo-2" }
        val result = layout(report.copy(record = report.record.copy(events = events)))
        fits(result)
        assertEquals(1, result.pages.size)
        assertEquals(1, photos(result).size)
    }

    @Test fun maximumOrderFieldsAndNotesContinueWithoutTruncatingTheirContents() {
        val value = "Projektbeschreibung ".repeat(12).take(200)
        val notes = "Auftragshinweis mit Umlauten äöü. ".repeat(150).take(4000) + " ENDE-AUFTRAG"
        val order = sampleReport().order.copy(projectName = value, client = value, site = value,
            inspectionLocation = value, notes = notes)
        val result = layout(sampleReport().copy(order = order))
        fits(result)
        val text = compact(text(result))
        assertTrue(text.contains(compact(value)))
        assertTrue(text.contains("ENDE-AUFTRAG"))
        assertTrue(result.pages.size > 1)
        // Page number and report number once per page.
        result.pages.forEach { page ->
            assertEquals(1, page.drawings.count { it.text.startsWith("Seite ") })
            assertEquals(1, page.drawings.count { it.text.contains("QS-MUSTER-0148 · Stand 02") })
        }
        // Continuation pages repeat the position, so a loose sheet stays identifiable.
        assertTrue(result.pages.drop(1).all { page -> page.drawings.any { it.text.contains("Position IPE270.1") } })
    }

    @Test fun largeAttributesAndCommentsRetainAllContentOverSeveralPages() {
        val report = sampleReport()
        val comment = "Feststellung prüfen. ".repeat(190) + "ENDE-KOMMENTAR"
        val detail = "Attributwert ".repeat(450) + "ENDE-ATTRIBUT"
        // A huge attribute of the main part and a second part: the main part's attributes stay complete.
        val main = report.record.piece.parts.single().let { it.copy(attributes = it.attributes + ("Beschreibung" to detail)) }
        val changed = report.copy(record = report.record.copy(
            piece = report.record.piece.copy(parts = listOf(main,
                QualityPart("Nebenbauteil", "guid-2", "Blech", "100 × 100 × 10 mm", mapOf("PROFILE" to "BL10*100")))),
            events = report.record.events + QualityEvent("long", "2026-09-20T09:00:00Z", "Europe/Berlin", "Bernd", QualityStatus.REWORK, comment),
        ))
        val result = layout(changed)
        fits(result)
        val text = compact(text(result))
        assertTrue(text.contains("ENDE-ATTRIBUT"))
        assertTrue(text.contains("ENDE-KOMMENTAR"))
        assertTrue(text.contains("Nebenbauteil"))
        assertTrue(result.pages.size > 1)
    }

    @Test fun unusualLongPhotoNamesStayCompleteInTheHistory() {
        val report = sampleReport()
        val events = report.record.events.map { event ->
            event.photo?.let { event.copy(photo = it.copy(displayName = "Foto ".repeat(800) + "ENDE-FOTONAME")) } ?: event
        }
        val result = layout(report.copy(record = report.record.copy(events = events)))
        fits(result)
        assertEquals(listOf("photo-1", "photo-2"), photos(result).map { it.imageId })
        assertTrue(compact(text(result)).contains("ENDE-FOTONAME"))
    }

    @Test fun landscapeAndPortraitPhotosKeepTheirProportions() {
        val sizes = mapOf("photo-1" to ReportImageSize(2340, 1080), "photo-2" to portrait)
        val result = layout(sizes = sizes)
        fits(result)
        val (landscape, upright) = photos(result)
        assertEquals(2340f / 1080f, landscape.width / landscape.height, 0.01f)
        assertEquals(1080f / 2340f, upright.width / upright.height, 0.01f)
    }

    @Test fun manyPhotosKeepTheirOrderInRows() {
        val report = sampleReport()
        val photos = (1..17).map { n -> report.record.photos.first().copy(id = "photo-$n") }
        val result = layout(report.copy(record = report.record.copy(events = photos)))
        fits(result)
        assertEquals(photos.map { it.id }, photos(result).map { it.imageId })
        // Five upright photos to a row: 17 photos need 4 rows, about two pages, not nine.
        val rows = result.pages.flatMapIndexed { index, page -> page.drawings.filter { it.kind == "photo" }.map { index to it.y } }.distinct()
        assertEquals(4, rows.size)
        assertTrue("${result.pages.size} Seiten", result.pages.size <= 3)
    }

    @Test fun photosAndCommentsAloneDoNotInventAnInspectionOrResponsiblePerson() {
        val report = sampleReport()
        val result = layout(report.copy(order = OrderDetails("p", "Projekt"), exporter = "",
            record = report.record.copy(events = report.record.photos)))
        fits(result)
        val text = text(result)
        assertTrue(text.contains("Noch nicht geprüft"))
        assertTrue(text.contains("Nicht angegeben"))
        assertTrue(text.contains("noch keine Prüfung dokumentiert"))
        assertFalse(text.contains("Sichtprüfung"))
        assertFalse(text.contains("Nachkontrolle erforderlich"))
        assertFalse(text.contains("Geprüft von"))
    }

    @Test fun aReportWithoutPhotosHasNoPhotoSection() {
        val report = sampleReport()
        val result = layout(report.copy(record = report.record.copy(events = report.record.events.filter { it.photo == null })), emptyMap())
        fits(result)
        assertFalse(text(result).contains("FOTODOKUMENTATION"))
        assertTrue(photos(result).isEmpty())
        assertEquals(1, result.pages.size)
    }

    @Test fun anAssemblyListsItsPartsInATable() {
        val report = sampleReport()
        val parts = (1..14).map { n ->
            QualityPart("900270.${500 + n}", "guid-$n", if (n == 1) "Hauptbauteil" else "Blech", "$n,0 × 10,0 × 200,0 mm",
                mapOf("PROFILE" to "PL10*$n", "MATERIAL" to "S355J2", "PHASE" to "2"))
        }
        val result = layout(report.copy(record = report.record.copy(piece = report.record.piece.copy(parts = parts))))
        fits(result)
        val text = compact(text(result))
        parts.forEach { assertTrue(it.position, text.contains(compact(it.position))) }
        assertTrue(text.contains("PL10*14"))
        assertTrue(text.contains("1Hauptbauteil·13Bleche"))
        // Each part's GUID is not listed; the model hash stands for them.
        assertFalse(text.contains("guid-7"))
    }

    /**
     * Modelled on a real assembly (0.27 export: 56 pages): an HEM700 column, 31 plates in
     * 23 positions, 90 welds exported as IfcFastener, four landscape AR photos and five entries.
     */
    @Test fun aLargeAssemblyWithWeldsStaysAtTwoPages() {
        val report = sampleReport()
        val column = QualityPart("900033.501", "column-guid-1", "Hauptbauteil", "304,0 × 2784,8 × 716,0 mm",
            linkedMapOf("Assembly position number" to "900033.2", "MATERIAL" to "S355J2", "PHASE" to "900033",
                "PROFILE" to "HEM700", "description" to "HEM700", "ifcClass" to "IfcColumn", "name" to "STUETZE"))
        val plates = (0 until 31).map { n ->
            val position = 503 + n % 23
            QualityPart("900033.$position", "plate-guid-$n", "Blech",
                if (n % 2 == 0) "20,0 × 135,0 × 141,5 mm" else "141,5 × 135,0 × 20,0 mm",
                linkedMapOf("MATERIAL" to if (position % 3 == 0) "S355J2+Z25" else "S355J2", "PHASE" to "900033",
                    "PROFILE" to "BL${20 + position % 4 * 5}*${100 + position}", "ifcClass" to "IfcPlate", "name" to "PLATE"))
        }
        val welds = (0 until 90).map { n ->
            QualityPart("", "weld-$n", "Schraubengruppe", "10,8 × 14,2 × 239,0 mm",
                linkedMapOf("PHASE" to "0", "fastenerCategory" to "PHYSICAL_FASTENER", "ifcClass" to "IfcFastener", "name" to "Weld"))
        }
        val photos = (1..4).map { n ->
            QualityEvent("p$n", "2026-10-02T07:27:3${n}Z", "Europe/Berlin", "Prüfer A",
                photo = QualityPhoto("p$n.jpg", "900033.2_2026-10-02_09-27-3$n.jpg", "$n".repeat(64), "Verankert · vorläufige Ausrichtung"))
        }
        val check = QualityEvent("ok", "2026-10-02T07:28:01Z", "Europe/Berlin", "Prüfer A", QualityStatus.OK)
        val real = report.copy(record = report.record.copy(piece = report.record.piece.copy(position = "900033.2",
            parts = listOf(column) + plates + welds), events = photos + check))
        val result = layout(real, photos.associate { it.id to ReportImageSize(1055, 498) })
        File("build/reports/quality-assembly-preview.json").apply { parentFile?.mkdirs() }
            .writeText(previewJson.encodeToString(result))
        fits(result)
        assertTrue("${result.pages.size} Seiten statt 56", result.pages.size <= 2)
        val text = compact(text(result))
        assertFalse(text.contains("Weld"))
        assertFalse(text.contains("Verbindungsmittel"))
        assertTrue(text.contains("1Hauptbauteil·31Bleche"))
        assertTrue(text.contains("32Bauteile"))
        assertTrue(text.contains("HEM700"))
        assertFalse(text.contains("weld-1"))
        assertFalse(text.contains("plate-guid-3"))
        // 23 plate positions plus the column, identical plates counted, not repeated.
        val rows = result.pages.flatMap { it.drawings }.count { it.kind == "text" && it.text.startsWith("900033.5") && it.bold }
        assertEquals(24, rows)
        assertEquals(4, photos(result).size)
        // Landscape photos two to a row or stacked beside the data, never one per row at full width.
        assertTrue(photos(result).all { it.width < 300f })
    }

    @Test fun aCollectionStartsWithAnOverviewAndEveryPositionOnItsOwnPages() {
        val first = sampleReport()
        val second = first.copy(id = "second", number = "QS-MUSTER-0149", edition = 1, record = first.record.copy(
            piece = first.record.piece.copy(key = "second-key", position = "IPE270.2"),
            events = listOf(QualityEvent("ok", "2026-09-21T07:00:00Z", "Europe/Berlin", "Anna Keller", QualityStatus.OK, "Ohne Befund."))))
        val third = first.copy(id = "third", number = "QS-MUSTER-0150", edition = 3, record = first.record.copy(
            piece = first.record.piece.copy(key = "third-key", position = "HEB140.1"), events = emptyList()))
        val collection = QualityReportCollection("2026-09-21T08:00:00Z", "Europe/Berlin", "Anna Keller", listOf(first, second, third))
        val result = engine().build(collection, first.record.photos.associate { it.id to portrait })
        File("build/reports/quality-collection-preview.json").apply { parentFile?.mkdirs() }
            .writeText(previewJson.encodeToString(result))
        fits(result)
        assertEquals("Übersicht", result.pages.first().section)
        // The overview's page column names the page where each position's report begins.
        val overview = result.pages.first().drawings
        val positions = listOf("IPE270.1", "IPE270.2", "HEB140.1")
        val starts = positions.map { position ->
            result.pages.indexOfFirst { page -> page.drawings.any { it.text == "Position $position" && it.size == 15f } }
        }
        assertTrue(starts.all { it >= 0 })
        assertEquals(starts, starts.sorted())
        starts.forEach { start -> assertTrue("Seite ${start + 1}", overview.any { it.text == "${start + 1}" && it.x > 500f }) }
        // Order data stands once on the cover, not again in every position.
        assertEquals(1, result.pages.flatMap { it.drawings }.count { it.text == "A-2026-0148" })
        val cover = text(result.copy(pages = result.pages.take(1)))
        listOf("QS-SAMMELBERICHT", "3 Positionen", "Stahlbau · Musterauftrag", "A-2026-0148", "QS-MUSTER-0149", "Stand 03",
            "Geprüft – in Ordnung", "Offen / erneut prüfen", "Noch nicht geprüft").forEach {
            assertTrue("Missing $it", compact(cover).contains(compact(it)))
        }
        assertTrue(result.pages.last().drawings.any { it.text == "Seite ${result.pages.size} / ${result.pages.size}" })
    }

    @Test fun shortPositionsShareSheetsInACollection() {
        val base = sampleReport()
        val reports = (1..12).map { n ->
            base.copy(id = "r$n", number = "QS-R$n", record = base.record.copy(
                piece = base.record.piece.copy(key = "k$n", position = "B$n"),
                events = listOf(QualityEvent("e$n", "2026-09-21T07:00:00Z", "Europe/Berlin", "Anna Keller", QualityStatus.OK, "Ohne Befund."))))
        }
        val result = engine().build(QualityReportCollection("2026-09-21T08:00:00Z", "Europe/Berlin", "Anna", reports), emptyMap())
        fits(result)
        assertTrue("${result.pages.size} Seiten für 12 kurze Positionen", result.pages.size <= 7)
        reports.forEach { report ->
            assertTrue(report.record.piece.position, result.pages.flatMap { it.drawings }.any { it.text == "Position ${report.record.piece.position}" })
        }
        assertTrue(result.pages.all { page -> page.drawings.count { it.text.startsWith("Seite ") } == 1 })
    }

    @Test fun theLogoHeadsTheOverviewAndEveryContinuationPage() {
        val base = sampleReport()
        val reports = (1..6).map { n ->
            base.copy(id = "r$n", number = "QS-R$n", record = base.record.copy(piece = base.record.piece.copy(key = "k$n", position = "B$n")))
        }
        val result = engine().build(QualityReportCollection("2026-09-21T08:00:00Z", "Europe/Berlin", "Anna", reports),
            base.record.photos.associate { it.id to portrait }, wideLogo)
        fits(result)
        assertTrue(result.pages.size > 1)
        // Large on the cover, small on every following page, never again large inside the collection.
        assertEquals(40f, result.pages.first().drawings.first { it.kind == "logo" }.height, 0.01f)
        result.pages.drop(1).forEach { page ->
            val logos = page.drawings.filter { it.kind == "logo" }
            assertEquals(1, logos.size)
            assertEquals(12f, logos.single().height, 0.01f)
        }
    }

    companion object {
        private val previewJson = Json { encodeDefaults = true; prettyPrint = true }
    }
}

/** Fictional inspection data, never installed or written to the app's real quality database. */
private fun sampleReport(): IssuedQualityReport {
    val part = QualityPart("900270.501", "4aa700cd-f44f-54f6-b710-4083d32a28dc", "Hauptbauteil", "135,0 × 270,0 × 200,0 mm",
        linkedMapOf("profile" to "IPE270", "material" to "S355", "lengthMm" to "200", "webThicknessMm" to "6.6",
            "flangeThicknessMm" to "10.2", "rootRadiusMm" to "15", "name" to "IPE270 × 200 mm", "ifcClass" to "IfcBeam",
            "sourceGlobalId" to "BUNDLED-IPE270-200"))
    val record = QualityRecord(piece().copy(position = "IPE270.1", parts = listOf(part)), "2026-09-19T12:00:00Z", listOf(
        QualityEvent("check-1", "2026-09-19T12:00:00Z", "Europe/Berlin", "Anna Keller", QualityStatus.DEFECT,
            "Bohrung nacharbeiten.", action = QualityAction("Bohrung nacharbeiten und erneut prüfen.", "Tobias Wagner", "2026-09-24")),
        QualityEvent("photo-1", "2026-09-19T12:02:00Z", "Europe/Berlin", "Anna Keller",
            photo = QualityPhoto("a.jpg", "AR-IPE270.1-Ansicht-A.jpg", "a".repeat(64), "Verankert · eine Ansicht")),
        QualityEvent("photo-2", "2026-09-19T12:03:00Z", "Europe/Berlin", "Anna Keller",
            photo = QualityPhoto("b.jpg", "AR-IPE270.1-Ansicht-B.jpg", "b".repeat(64), "Verankert · eine Ansicht")),
        QualityEvent("check-2", "2026-09-20T08:00:00Z", "Europe/Berlin", "Max Berger", QualityStatus.RECHECK,
            "Nacharbeit durchgeführt. Abschließende Kontrolle steht noch aus.",
            action = QualityAction("Nacharbeit visuell kontrollieren und Ergebnis dokumentieren.", "Tobias Wagner", "2026-09-24")),
    ))
    return IssuedQualityReport("sample", "QS-MUSTER-0148", 2, "2026-09-20T08:20:00Z", "Europe/Berlin", "Anna Keller",
        OrderDetails("p", "Stahlbau · Musterauftrag", "A-2026-0148", "P-2026-082", "Musterkunde GmbH", "Lena Hoffmann", "Max Berger",
            "Werkstatt Berlin", "Prüfplatz 02 · Halle A", "Muster mit fiktiven Auftrags-, Personen- und Prüfdaten. Fotos aus dem bereitgestellten Testvideo."),
        record, "sample.pdf")
}
