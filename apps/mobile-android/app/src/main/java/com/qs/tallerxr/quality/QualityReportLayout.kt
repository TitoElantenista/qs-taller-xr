package com.qs.tallerxr.quality

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/** Drawing instructions in A4 points, shared by the Android renderer and layout regression tests. */
@Serializable
data class ReportDrawing(
    val kind: String,
    val x: Float,
    val y: Float,
    val width: Float = 0f,
    val height: Float = 0f,
    val text: String = "",
    val size: Float = 10f,
    val bold: Boolean = false,
    val color: String = "#183443",
    val radius: Float = 0f,
    val imageId: String = "",
)

@Serializable
data class ReportPage(val section: String, val drawings: MutableList<ReportDrawing> = mutableListOf())

@Serializable
data class QualityReportPages(val pages: List<ReportPage>, val width: Float = 595f, val height: Float = 842f)

data class ReportImageSize(val width: Int, val height: Int) {
    val portrait: Boolean get() = height > width
    val aspect: Float get() = width.toFloat() / height
}

/** Several positions saved as one file: an overview first, then every position's own report. */
data class QualityReportCollection(
    val createdAt: String,
    val zoneId: String,
    val exporter: String,
    val reports: List<IssuedQualityReport>,
)

/**
 * Compact A4 layout (0.28.0).  Sections follow each other on the page instead of each opening a
 * new sheet: header, status with finding and next step, order and part data side by side, AR
 * photos in rows, the history as a table and the model identification in small print.  A position
 * with one or two photos and a short history fits on one page; longer content continues on further
 * pages without losing any text.  All wrapping is measured with the font metrics of the renderer.
 */
class QualityReportLayout(
    private val measure: (String, Float, Boolean) -> Float,
    /** App and version that drew the report, in the footer, e.g. «QS Taller XR 0.30.0». */
    private val generator: String = "",
) {
    private val pages = mutableListOf<ReportPage>()
    private val references = mutableListOf<PageReference>()
    /** Company logo chosen in the settings, if any. */
    private var logo: ReportImageSize? = null
    /** The header being drawn opens its page. */
    private var freshPage = false
    private lateinit var page: ReportPage
    private var running = Running("", "", "")
    private var y = 0f

    /** Header of continuation pages of the report being laid out. */
    private class Running(val section: String, val title: String, val reference: String)

    /** Page number in the overview table, filled in once every position has its pages. */
    private class PageReference(val page: ReportPage, val index: Int, val right: Float, val size: Float, val target: Int)

    /** One line of a block; blocks break between lines, never inside one. */
    private class Row(val height: Float, val draw: (Float) -> Unit)

    fun build(report: IssuedQualityReport, imageSizes: Map<String, ReportImageSize>, logo: ReportImageSize? = null): QualityReportPages {
        reset()
        this.logo = logo
        position(report, imageSizes)
        return finish()
    }

    fun build(collection: QualityReportCollection, imageSizes: Map<String, ReportImageSize>, logo: ReportImageSize? = null): QualityReportPages {
        require(collection.reports.isNotEmpty()) { "Keine Position ausgewählt." }
        reset()
        this.logo = logo
        cover(collection)
        val starts = collection.reports.map { report -> position(report, imageSizes, inCollection = true) }
        references.forEach { reference ->
            val number = "${starts[reference.target] + 1}"
            val width = measure(number, reference.size, false)
            reference.page.drawings[reference.index] = reference.page.drawings[reference.index]
                .copy(text = number, x = reference.right - width, width = width)
        }
        return finish()
    }

    private fun reset() {
        pages.clear()
        references.clear()
    }

    private fun finish(): QualityReportPages {
        pages.forEachIndexed { index, current ->
            page = current
            line(LEFT, FOOTER_LINE, FULL, rule)
            val pagination = "Seite ${index + 1} / ${pages.size}"
            val paginationWidth = measure(pagination, 6.8f, true)
            text(RIGHT - paginationWidth, FOOTER_LINE + 5f, pagination, 6.8f, true)
            var room = FULL - paginationWidth - 14f
            if (generator.isNotEmpty()) {
                val generatorWidth = measure(generator, 5.9f, false)
                text(RIGHT - paginationWidth - 14f - generatorWidth, FOOTER_LINE + 5.6f, generator, 5.9f, color = faint)
                room -= generatorWidth + 12f
            }
            text(LEFT, FOOTER_LINE + 5.6f, fit(DISCLAIMER, room, 5.9f, false), 5.9f, color = faint)
        }
        return QualityReportPages(pages.toList())
    }

    // ── One position ──────────────────────────────────────────────────────────────────────────

    /**
     * One position's report; returns the index of the page it starts on.  Inside a collection a
     * position follows the previous one on the same page when there is room, and leaves the order
     * data and the logo to the overview.
     */
    private fun position(report: IssuedQualityReport, sizes: Map<String, ReportImageSize>, inCollection: Boolean = false): Int {
        val piece = report.record.piece
        running = Running("Position ${piece.position}", "QS-PRÜFBERICHT · Position ${piece.position} · Rev. ${piece.revision}",
            "${report.number} · Stand ${edition(report)}")
        val start = positionHeader(report, inCollection)
        statusCard(report.record)
        val remaining = dataAndPhotos(report, sizes, withOrder = !inCollection)
        if (!inCollection) notes(report.order)
        photos(remaining, remaining.size < report.record.photos.size, sizes)
        history(report)
        model(piece)
        return start
    }

    private fun positionHeader(report: IssuedQualityReport, continuous: Boolean): Int {
        val record = report.record
        val piece = record.piece
        if (continuous && pages.isNotEmpty() && BOTTOM - y >= POSITION_START) {
            // Next position on the same sheet, set off by a strong rule.
            y += 12f
            rect(LEFT, y, FULL, 2.2f, navy)
            y += 12f
            freshPage = false
        } else {
            newPage()
            freshPage = true
        }
        val start = pages.lastIndex
        val parts = piece.parts.count { !isFastener(it) }
        documentHeader("QS-PRÜFBERICHT", "${report.number} · Stand ${edition(report)}",
            "Erstellt ${shortDate(report.createdAt, report.zoneId)}", "von ${report.exporter.ifBlank { "Nicht angegeben" }}",
            "Position ${piece.position}",
            "${report.order.projectName} · Modellrevision ${piece.revision} · ${count(parts, "Bauteil", "Bauteile")} · " +
                count(record.photos.size, "AR-Foto", "AR-Fotos"),
            withLogo = !continuous)
        return start
    }

    /**
     * Title block of a report or overview.  With a logo it stands top left and the kind, number and
     * date top right; without, the kind takes its place.  The title and a grey line follow below.
     */
    private fun documentHeader(kind: String, reference: String, created: String, author: String, title: String,
        subtitle: String, withLogo: Boolean) {
        val shownLogo = logo?.takeIf { withLogo }
        if (shownLogo != null) {
            val top = y
            val (logoWidth, logoHeight) = logoBox(shownLogo, LOGO_HEIGHT, LOGO_MAX_WIDTH)
            page.drawings += ReportDrawing("logo", LEFT, top, logoWidth, logoHeight, imageId = LOGO_ID)
            val authorText = fit(author, 200f, 7.2f, false)
            listOf(Triple(kind, 7.4f, true), Triple(reference, 8.2f, true), Triple(created, 7.2f, false), Triple(authorText, 7.2f, false))
                .foldIndexed(top) { index, rowTop, (value, size, bold) ->
                    text(RIGHT - measure(value, size, bold), rowTop, value, size, bold,
                        when (index) { 0 -> teal; 1 -> navy; else -> muted })
                    rowTop + if (index == 0) 11f else 10.5f
                }
            y = top + max(logoHeight, 44f) + 9f
            wrap(title, FULL, 15f, true).forEach { text(LEFT, y, it, 15f, true); y += 18.5f }
        } else {
            var labelX = LEFT
            // A collection's later pages carry the small logo like any continuation page.
            if (freshPage && logo != null) logo?.let { size ->
                val (width, height) = logoBox(size, 12f, 70f)
                page.drawings += ReportDrawing("logo", LEFT, y - 1f, width, height, imageId = LOGO_ID)
                labelX += width + 8f
            }
            text(labelX, y, kind, 7.4f, true, teal)
            text(RIGHT - measure(reference, 8.2f, true), y - 0.8f, reference, 8.2f, true)
            y += 13f
            val authorText = fit(author, 170f, 7.2f, false)
            val side = max(measure(created, 7.2f, false), measure(authorText, 7.2f, false))
            text(RIGHT - measure(created, 7.2f, false), y + 2f, created, 7.2f, color = muted)
            text(RIGHT - measure(authorText, 7.2f, false), y + 11.5f, authorText, 7.2f, color = muted)
            val top = y
            wrap(title, FULL - side - 18f, 15f, true).forEach { text(LEFT, y, it, 15f, true); y += 18.5f }
            y = max(y, top + 22f)
        }
        wrap(subtitle, FULL, 8.6f, false).forEach { text(LEFT, y, it, 8.6f, color = muted); y += 11.4f }
        y += 4f
        line(LEFT, y, FULL, navy)
        y += 9f
    }

    /** Logo size within a box, keeping its proportions. */
    private fun logoBox(size: ReportImageSize, height: Float, maximumWidth: Float): Pair<Float, Float> {
        val aspect = size.aspect.coerceIn(0.2f, 12f)
        return if (height * aspect <= maximumWidth) height * aspect to height else maximumWidth to maximumWidth / aspect
    }

    private fun statusCard(record: QualityRecord) {
        val (color, background) = statusColors(record.status)
        val x = LEFT + 13f
        val inner = FULL - 22f
        val check = record.lastCheck
        val label = record.status.label
        val meta = check?.let { "Geprüft von ${it.inspector} · ${qualityDate(it.at, it.zoneId)}" } ?: "Noch nicht geprüft"
        val rows = mutableListOf<Row>()
        val metaWidth = measure(meta, 7.4f, false)
        if (measure(label, 11.5f, true) + metaWidth + 18f <= inner) {
            rows += Row(16f) { top ->
                text(x, top, label, 11.5f, true, color)
                text(RIGHT - 9f - metaWidth, top + 3.3f, meta, 7.4f, color = muted)
            }
        } else {
            rows += wrap(label, inner, 11.5f, true).map { line -> Row(15f) { top -> text(x, top, line, 11.5f, true, color) } }
            rows += textRows(meta, inner, 7.4f, color = muted, x = x)
        }
        rows += Row(4f) {}
        val finding = check?.let { listOf(labelRow("FESTSTELLUNG", x, muted)) +
            textRows(it.comment.ifBlank { "Zu diesem Prüfvermerk wurde kein Kommentar erfasst." }, inner, 8.2f, x = x) }
            ?: textRows(if (record.status == QualityStatus.LEGACY) "Älterer Prüfvermerk ohne dokumentierte Modellkennung."
                else "Für diesen Modellstand wurde noch keine Prüfung dokumentiert.", inner, 8.2f, x = x)
        val action = record.currentAction
        val half = (inner - 18f) / 2f
        val narrowFinding = check?.let { listOf(labelRow("FESTSTELLUNG", x, muted)) +
            textRows(it.comment.ifBlank { "Zu diesem Prüfvermerk wurde kein Kommentar erfasst." }, half, 8.2f, x = x) }
        val narrowAction = action?.let { actionRows(it, half, x + half + 18f, color) }
        if (narrowFinding != null && narrowAction != null && max(narrowFinding.size, narrowAction.size) <= 12) {
            // Finding and next step side by side: one block, as high as the longer of the two.
            rows += (0 until max(narrowFinding.size, narrowAction.size)).map { index ->
                val first = narrowFinding.getOrNull(index)
                val second = narrowAction.getOrNull(index)
                Row(max(first?.height ?: 0f, second?.height ?: 0f)) { top -> first?.draw(top); second?.draw(top) }
            }
        } else {
            rows += finding
            if (action != null) {
                rows += Row(4f) {}
                rows += actionRows(action, inner, x, color)
            }
        }
        flow(rows, padTop = 7f, padBottom = 7f, background = { top, height ->
            listOf(rectangle(LEFT, top, FULL, height, background, 4f), rectangle(LEFT, top, 3.2f, height, color, 1.5f))
        })
        y += 10f
    }

    private fun actionRows(action: QualityAction, width: Float, x: Float, color: String): List<Row> = buildList {
        add(labelRow("NÄCHSTER SCHRITT", x, color))
        if (action.description.isNotBlank()) addAll(textRows(action.description, width, 8.2f, true, x = x))
        val details = listOfNotNull(action.assignee.takeIf { it.isNotBlank() }?.let { "Zuständig: $it" },
            action.dueDate.takeIf { it.isNotBlank() }?.let { "Termin: ${qualityActionDate(it)}" }).joinToString(" · ")
        if (details.isNotEmpty()) addAll(textRows(details, width, 7.6f, x = x))
    }

    private fun orderPairs(order: OrderDetails): List<Pair<String, String>> = buildList {
        add("Auftragsnummer" to order.orderNumber.ifBlank { "Nicht angegeben" })
        if (order.projectNumber.isNotBlank()) add("Projekt-Nr. / intern" to order.projectNumber)
        if (order.client.isNotBlank()) add("Auftraggeber" to order.client)
        add("Projektverantwortlich" to order.projectResponsible.ifBlank { "Nicht angegeben" })
        if (order.qualityResponsible.isNotBlank()) add("QS-verantwortlich" to order.qualityResponsible)
        if (order.site.isNotBlank()) add("Baustelle / Standort" to order.site)
        if (order.inspectionLocation.isNotBlank()) add("Prüfort" to order.inspectionLocation)
    }

    private fun partPairs(piece: QualityPiece): List<Pair<String, String>> = buildList {
        add("Position / Baugruppe" to piece.position)
        val structural = piece.parts.filterNot(::isFastener)
        val single = structural.singleOrNull()
        if (single != null) {
            if (single.position.isNotBlank() && single.position != piece.position) add("Bauteilposition" to single.position)
            add("Typ" to single.type)
            val attributes = keyAttributes(single)
            val web = attributes["webThicknessMm"]
            val flange = attributes["flangeThicknessMm"]
            attributes.forEach { (key, value) ->
                when {
                    web != null && flange != null && key == "webThicknessMm" -> add("Steg / Flansch [mm]" to "$web / $flange")
                    web != null && flange != null && key == "flangeThicknessMm" -> Unit
                    else -> add(attributeLabel(key) to value)
                }
            }
            add("Hüllmaße (Modell)" to single.dimensions)
        } else if (structural.isEmpty()) {
            add("Bauteile" to "Keine Angaben")
        } else {
            mainPart(structural)?.let { main ->
                add("Hauptbauteil" to listOfNotNull(main.position.ifBlank { null }, profileOf(main), materialOf(main)).joinToString(" · "))
            }
            add("Bauteile" to structural.groupBy { it.type }.entries.joinToString(" · ") { (type, parts) -> "${parts.size} ${plural(type, parts.size)}" })
        }
        add("Modellrevision" to piece.revision.toString())
    }

    /**
     * Order and part data.  Up to two upright photos stand beside them, where they fill the space a
     * data column leaves anyway; returns the photos that still need a row of their own.
     */
    private fun dataAndPhotos(report: IssuedQualityReport, sizes: Map<String, ReportImageSize>, withOrder: Boolean): List<IndexedValue<QualityEvent>> {
        val photos = report.record.photos.withIndex().toList()
        val orderPairs = orderPairs(report.order)
        val partPairs = partPairs(report.record.piece)
        // Beside the data: two upright photos next to each other, or up to two landscape ones stacked.
        val first = photos.firstOrNull()
        val upright = first != null && aspect(first.value, sizes) < 0.8f
        val side = when {
            first == null -> emptyList()
            upright -> photos.take(2).takeWhile { aspect(it.value, sizes) < 0.8f }
            else -> photos.take(2).takeWhile { aspect(it.value, sizes) >= 1.25f }.ifEmpty { listOf(first) }
        }
        val placements = ArrayList<Pair<IndexedValue<QualityEvent>, FloatArray>>()
        var sideWidth = 0f
        var sideHeight = 15f
        if (upright) {
            side.forEach { photo ->
                val w = PHOTO_HEIGHT * aspect(photo.value, sizes)
                placements += photo to floatArrayOf(sideWidth + (if (placements.isEmpty()) 0f else PHOTO_GAP), 15f, w, PHOTO_HEIGHT)
                sideWidth += w + if (placements.size > 1) PHOTO_GAP else 0f
            }
            sideHeight += PHOTO_HEIGHT + PHOTO_CAPTION
        } else if (side.isNotEmpty()) {
            sideWidth = SIDE_LANDSCAPE_WIDTH
            side.forEach { photo ->
                val h = min(SIDE_LANDSCAPE_WIDTH / aspect(photo.value, sizes), PHOTO_HEIGHT)
                placements += photo to floatArrayOf(0f, sideHeight, h * aspect(photo.value, sizes), h)
                sideHeight += h + PHOTO_CAPTION + PHOTO_GAP
            }
        }
        val dataWidth = FULL - sideWidth - 18f
        val rows = (if (withOrder) listOf(groupRow("Auftrag", LEFT, dataWidth)) + keyValueRows(orderPairs, LEFT, dataWidth, 92f, 7.9f) +
            Row(6f) {} else emptyList()) + groupRow("Bauteil", LEFT, dataWidth) + keyValueRows(partPairs, LEFT, dataWidth, 92f, 7.9f)
        val height = max(rows.sumOf { it.height.toDouble() }.toFloat(), sideHeight)
        if (placements.isEmpty() || dataWidth < 250f || height > USABLE) {
            if (withOrder) columns("Auftrag", orderPairs, "Bauteil", partPairs)
            else partPairs.chunked((partPairs.size + 1) / 2).let { halves ->
                columns("Bauteil", halves[0], "", halves.getOrElse(1) { emptyList() })
            }
            return photos
        }
        ensure(height)
        val top = y
        var cursor = top
        rows.forEach { it.draw(cursor); cursor += it.height }
        val x = RIGHT - sideWidth
        groupRow("Fotodokumentation", x, sideWidth).draw(top)
        placements.forEach { (photo, box) -> photo(photo.value, photo.index, x + box[0], top + box[1], box[3], sizes) }
        y = top + height + 4f
        return photos.drop(placements.size)
    }

    private fun aspect(event: QualityEvent, sizes: Map<String, ReportImageSize>) =
        (sizes[event.id]?.aspect ?: PORTRAIT_ASPECT).coerceIn(0.25f, 4f)

    /** One photo with a hairline frame and a two-line caption under it. */
    private fun photo(event: QualityEvent, index: Int, x: Float, top: Float, height: Float, sizes: Map<String, ReportImageSize>) {
        val width = height * aspect(event, sizes)
        rect(x - 0.6f, top - 0.6f, width + 1.2f, height + 1.2f, rule)
        page.drawings += ReportDrawing("photo", x, top, width, height, imageId = event.id)
        val title = "Foto ${index + 1}"
        val titleWidth = measure(title, 6.6f, true)
        text(x, top + height + 3f, title, 6.6f, true)
        text(x + titleWidth + 4f, top + height + 3f, fit(shortDate(event.at, event.zoneId), width - titleWidth - 4f, 6.4f, false),
            6.4f, color = muted)
    }

    private fun notes(order: OrderDetails) {
        if (order.notes.isBlank()) return
        y += 2f
        flow(listOf(labelRow("BEMERKUNG ZUM AUFTRAG", LEFT, muted)) + textRows(order.notes, FULL, 7.6f), keepTogether = false)
        y += 4f
    }

    /** Photos in rows at a common height, each at its own proportions, in the order they were taken. */
    private fun photos(photos: List<IndexedValue<QualityEvent>>, more: Boolean, sizes: Map<String, ReportImageSize>) {
        if (photos.isEmpty()) return
        heading(if (more) "Weitere Fotos" else "Fotodokumentation",
            "${count(photos.size, "AR-Foto", "AR-Fotos")} mit Modellüberlagerung",
            keep = targetHeight(photos.first().value, sizes) * 0.72f + PHOTO_CAPTION + PHOTO_GAP)
        var index = 0
        while (index < photos.size) {
            // Justified rows: upright photos about 215 pt high, landscape ones two to a row.  A photo
            // still joins the row if the whole row only shrinks a little below its target.
            val row = mutableListOf<IndexedValue<QualityEvent>>()
            var aspects = 0f
            var targets = 0f
            while (index < photos.size) {
                val photo = photos[index].value
                val candidate = aspects + aspect(photo, sizes)
                val target = (targets + targetHeight(photo, sizes)) / (row.size + 1)
                if (row.isNotEmpty() && (FULL - PHOTO_GAP * row.size) / candidate < target * 0.9f) break
                row += photos[index]
                aspects = candidate
                targets += targetHeight(photo, sizes)
                index++
            }
            var height = min(targets / row.size, (FULL - PHOTO_GAP * (row.size - 1)) / aspects)
            if (y + height + PHOTO_CAPTION + PHOTO_GAP > BOTTOM) {
                // Shrink a little rather than leave a large gap; otherwise the row moves on.
                val available = BOTTOM - y - PHOTO_CAPTION - PHOTO_GAP
                if (available >= height * 0.72f) height = available else continuationPage()
            }
            var x = LEFT
            row.forEach { (number, event) ->
                photo(event, number, x, y, height, sizes)
                x += height * aspect(event, sizes) + PHOTO_GAP
            }
            y += height + PHOTO_CAPTION + PHOTO_GAP
        }
    }

    /** Row height a photo aims for: upright ones large, landscape ones two to a row. */
    private fun targetHeight(event: QualityEvent, sizes: Map<String, ReportImageSize>): Float = when {
        aspect(event, sizes) < 0.8f -> PHOTO_HEIGHT
        aspect(event, sizes) < 1.25f -> 165f
        else -> 125f
    }

    private fun history(report: IssuedQualityReport) {
        val record = report.record
        heading("Prüfverlauf", "${count(record.events.size, "Eintrag", "Einträge")} · Datum und Uhrzeit wie erfasst")
        if (record.events.isEmpty()) {
            flow(textRows("Noch keine Einträge vorhanden.", FULL, 8f))
            return
        }
        val columns = floatArrayOf(LEFT, LEFT + 17f, LEFT + 84f)
        val width = RIGHT - columns[2] - 4f
        val header = Row(13f) { top ->
            rect(LEFT, top, FULL, 12f, light)
            listOf("Nr.", "Zeitpunkt", "Status · Kommentar · Maßnahme · Foto").forEachIndexed { i, label ->
                text(columns[i] + 2f, top + 2.6f, label, 6.4f, true, muted)
            }
        }
        var photoNumber = 0
        table(header, record.events.mapIndexed { index, event ->
            val photoNo = if (event.photo != null) ++photoNumber else 0
            // The person only where it is not the one who issued the report.
            val time = listOf(Cell(formatInstant(event.at, event.zoneId, "dd.MM.yyyy"), 7.2f),
                Cell(formatInstant(event.at, event.zoneId, "HH:mm z"), 6.8f, color = muted)) +
                if (event.inspector != report.exporter && event.inspector.isNotBlank())
                    wrap(event.inspector, columns[2] - columns[1] - 6f, 6.4f, false).map { Cell(it, 6.4f, color = muted) } else emptyList()
            cellRows(listOf(listOf(Cell("${index + 1}", 7.2f, color = muted)), time, entry(event, photoNo, width)), columns)
        })
    }

    /** Rows of one table line: every column's wrapped cell lines side by side, then a rule. */
    private fun cellRows(cells: List<List<Cell>>, columns: FloatArray, lineHeight: Float = 9.7f, padTop: Float = 3f,
        padBottom: Float = 4f, after: ((Int, Float) -> Unit)? = null): List<Row> =
        listOf(Row(padTop) {}) + (0 until cells.maxOf { it.size }).map { line ->
            Row(lineHeight) { top ->
                cells.forEachIndexed { column, cell ->
                    cell.getOrNull(line)?.let { text(columns[column] + 2f, top, it.text, it.size, it.bold, it.color) }
                }
                after?.invoke(line, top)
            }
        } + Row(padBottom) { top -> line(LEFT, top + padBottom - 0.8f, FULL, rule) }

    /** Table lines kept whole where possible; the header starts the table and every page it continues on. */
    private fun table(header: Row, lines: List<List<Row>>) {
        lines.forEachIndexed { index, rows ->
            if (index == 0) {
                val total = rows.sumOf { it.height.toDouble() }.toFloat()
                ensure(header.height + if (header.height + total <= USABLE) total else rows.first().height)
                header.draw(y)
                y += header.height
            }
            flow(rows, onNewPage = { header.draw(y); y += header.height })
        }
    }

    private class Cell(val text: String, val size: Float, val bold: Boolean = false, val color: String = "#16303D")

    /** What happened: a status in its colour, comment, next step, or which photo it was. */
    private fun entry(event: QualityEvent, photoNumber: Int, width: Float): List<Cell> = buildList {
        event.status?.let { status -> wrap(status.label, width, 7.2f, true).forEach { add(Cell(it, 7.2f, true, statusColors(status).first)) } }
        if (event.comment.isNotBlank()) wrap(event.comment, width, 7.2f, false).forEach { add(Cell(it, 7.2f)) }
        event.action?.let { action ->
            if (action.isEmpty) add(Cell("Nächster Schritt entfernt.", 7.2f, color = muted))
            if (action.description.isNotBlank()) wrap("Maßnahme: ${action.description}", width, 7.2f, false).forEach { add(Cell(it, 7.2f)) }
            val who = listOfNotNull(action.assignee.takeIf { it.isNotBlank() }?.let { "Zuständig: $it" },
                action.dueDate.takeIf { it.isNotBlank() }?.let { "Termin: ${qualityActionDate(it)}" }).joinToString(" · ")
            if (who.isNotEmpty()) wrap(who, width, 7.2f, false).forEach { add(Cell(it, 7.2f)) }
        }
        event.photo?.let { photo ->
            wrap("Foto $photoNumber · ${photo.alignment}", width, 7.2f, false).forEach { add(Cell(it, 7.2f)) }
            wrap(photo.displayName, width, 6.4f, false).forEach { add(Cell(it, 6.4f, color = "#5F707A")) }
        }
        if (isEmpty()) add(Cell("Eintrag ohne Text", 7.2f, color = muted))
    }

    private fun model(piece: QualityPiece) {
        heading("Modellstand & Bauteildaten", "Angaben aus dem CAD-Modell, keine gemessenen Werte")
        val structural = piece.parts.filterNot(::isFastener)
        val single = structural.singleOrNull()
        columns("", listOf("Quelle" to piece.sourceLabel, "Modelldatum" to qualityDate(piece.modelCreatedAt),
            "Baugruppen-GUID" to piece.assemblyGuid),
            "", listOfNotNull(single?.let { "Bauteil-GUID" to it.guid },
                "Modell-SHA-256" to piece.fingerprint.ifBlank { "Im Altbestand nicht erfasst" }),
            labelWidth = 74f, size = 7.1f)
        if (single != null) {
            val shown = keyAttributes(single).keys
            val rest = single.attributes.filterKeys { it !in shown }
            if (rest.isNotEmpty()) {
                y += 3f
                flow(textRows("Weitere Attribute: " + rest.entries.joinToString(" · ") { (key, value) -> "${attributeLabel(key)}: $value" },
                    FULL, 6.8f, color = muted), keepTogether = false)
            }
        } else if (structural.isNotEmpty()) parts(structural)
        if (structural.size > 1) {
            y += 3f
            flow(textRows("Gleiche Bauteile sind zusammengefasst; Einzel-GUIDs und übrige Attribute stehen im Modell (Modell-SHA-256).",
                FULL, 6.6f, color = muted), keepTogether = false)
        }
    }

    /** Structural parts, identical ones (position, type, profile, material) in one line with their number. */
    private fun parts(parts: List<QualityPart>) {
        y += 6f
        val columns = floatArrayOf(LEFT, LEFT + 92f, LEFT + 118f, LEFT + 182f, LEFT + 314f, LEFT + 404f)
        val widths = floatArrayOf(90f, 24f, 62f, 130f, 88f, RIGHT - columns[5])
        val header = Row(13f) { top ->
            rect(LEFT, top, FULL, 12f, light)
            listOf("Bauteilposition", "Anz.", "Typ", "Profil / Bezeichnung", "Werkstoff", "Abmessungen (Modell)").forEachIndexed { i, label ->
                text(columns[i] + 2f, top + 2.6f, label, 6.4f, true, muted)
            }
        }
        val main = mainPart(parts)
        val groups = parts.groupBy { listOf(it.position, it.type, profileOf(it).orEmpty(), materialOf(it).orEmpty()) }.values
            .sortedWith(compareBy<List<QualityPart>>({ it.first() !== main && main !in it })
                .thenComparator { a, b -> com.qs.tallerxr.projects.NaturalOrder.compare(a.first().position, b.first().position) })
        table(header, groups.map { group ->
            val part = group.first()
            cellRows(listOf(part.position.ifBlank { "–" }, "${group.size}", part.type, profileOf(part) ?: "–", materialOf(part) ?: "–",
                sortedDimensions(part.dimensions))
                .mapIndexed { column, value -> wrap(value, widths[column] - 4f, 7f, column == 0).map { Cell(it, 7f, column == 0) } },
                columns, lineHeight = 9.2f, padTop = 2f, padBottom = 2.6f)
        })
        // The main part's own attributes, as for a single part.
        if (main != null) {
            val shown = setOfNotNull(profileKey(main), materialKey(main))
            val rest = main.attributes.filterKeys { it !in shown }
            if (rest.isNotEmpty()) {
                y += 3f
                flow(textRows("Hauptbauteil ${main.position}: " + rest.entries.joinToString(" · ") { (key, value) -> "${attributeLabel(key)}: $value" },
                    FULL, 6.6f, color = muted), keepTogether = false)
            }
        }
    }

    // ── Overview of several positions ──────────────────────────────────────────────────────────

    private fun cover(collection: QualityReportCollection) {
        val reports = collection.reports
        val order = reports.first().order.takeIf { reports.map { it.order.projectId }.distinct().size == 1 }
        val title = order?.projectName ?: "Mehrere Projekte"
        val created = shortDate(collection.createdAt, collection.zoneId)
        val exporter = collection.exporter.ifBlank { "Nicht angegeben" }
        val positions = count(reports.size, "Position", "Positionen")
        running = Running("Übersicht", "QS-SAMMELBERICHT · $title", positions)
        newPage()
        val summary = QualityStatus.entries.mapNotNull { status ->
            reports.count { it.record.status == status }.takeIf { it > 0 }?.let { "$it × ${status.label}" }
        }.joinToString(" · ")
        documentHeader("QS-SAMMELBERICHT", positions, "Erstellt $created", "von $exporter", title, summary, withLogo = true)
        if (order != null) {
            val pairs = buildList {
                add("Auftragsnummer" to order.orderNumber.ifBlank { "Nicht angegeben" })
                if (order.projectNumber.isNotBlank()) add("Projekt-Nr. / intern" to order.projectNumber)
                if (order.client.isNotBlank()) add("Auftraggeber" to order.client)
                add("Projektverantwortlich" to order.projectResponsible.ifBlank { "Nicht angegeben" })
                if (order.qualityResponsible.isNotBlank()) add("QS-verantwortlich" to order.qualityResponsible)
                if (order.site.isNotBlank()) add("Baustelle / Standort" to order.site)
                if (order.inspectionLocation.isNotBlank()) add("Prüfort" to order.inspectionLocation)
            }
            val half = (pairs.size + 1) / 2
            columns("Auftrag", pairs.take(half), "", pairs.drop(half))
            notes(order)
        }
        heading("Positionen", "Prüfbericht jeder Position ab der genannten Seite; Nummer und Stand wie im QS-Archiv")
        val columns = floatArrayOf(LEFT, LEFT + 18f, LEFT + 126f, LEFT + 152f, LEFT + 256f, LEFT + 368f, LEFT + 396f, LEFT + 492f)
        val widths = floatArrayOf(16f, 106f, 24f, 102f, 110f, 26f, 94f, 35f)
        val header = Row(13f) { top ->
            rect(LEFT, top, FULL, 12f, light)
            listOf("Nr.", "Position", "Rev.", "Status", "Letzte Prüfung", "Fotos", "Bericht", "Seite").forEachIndexed { i, label ->
                if (i == 7) text(RIGHT - 2f - measure(label, 6.4f, true), top + 2.6f, label, 6.4f, true, muted)
                else text(columns[i] + 2f, top + 2.6f, label, 6.4f, true, muted)
            }
        }
        table(header, reports.mapIndexed { index, report ->
            val record = report.record
            val color = statusColors(record.status).first
            val check = record.lastCheck
            val cells = listOf(
                listOf(Cell("${index + 1}", 7.2f, color = muted)),
                wrap(record.piece.position, widths[1] - 4f, 7.2f, true).map { Cell(it, 7.2f, true) } +
                    if (order == null) wrap(report.order.projectName, widths[1] - 4f, 6.4f, false).map { Cell(it, 6.4f, color = "#5F707A") } else emptyList(),
                listOf(Cell("${record.piece.revision}", 7.2f)),
                wrap(record.status.label, widths[3] - 4f, 7.2f, true).map { Cell(it, 7.2f, true, color) },
                if (check == null) listOf(Cell("Noch nicht geprüft", 7.2f, color = "#5F707A"))
                else wrap(check.inspector, widths[4] - 4f, 7.2f, false).map { Cell(it, 7.2f) } +
                    Cell(shortDate(check.at, check.zoneId), 6.6f, color = "#5F707A"),
                listOf(Cell("${record.photos.size}", 7.2f)),
                listOf(Cell(report.number, 7f), Cell("Stand ${edition(report)}", 6.6f, color = "#5F707A")),
            )
            cellRows(cells, columns, after = { line, rowTop ->
                if (line == 0) {
                    text(RIGHT - 2f, rowTop, "…", 7.2f)
                    references += PageReference(page, page.drawings.lastIndex, RIGHT - 2f, 7.2f, index)
                }
            })
        })
    }

    // ── Building blocks ────────────────────────────────────────────────────────────────────────

    private fun newPage() {
        page = ReportPage(running.section)
        pages += page
        rect(0f, 0f, 595f, 4f, navy)
        y = 18f
    }

    private fun continuationPage() {
        newPage()
        var x = LEFT
        logo?.let { size ->
            val (width, height) = logoBox(size, 12f, 70f)
            page.drawings += ReportDrawing("logo", LEFT, y - 1f, width, height, imageId = LOGO_ID)
            x += width + 8f
        }
        val reference = running.reference
        val referenceWidth = measure(reference, 7.2f, true)
        text(x, y, fit(running.title, RIGHT - x - referenceWidth - 16f, 7.2f, true), 7.2f, true)
        text(RIGHT - referenceWidth, y, reference, 7.2f, true, muted)
        y += 12f
        line(LEFT, y, FULL, rule)
        y += 9f
    }

    private fun ensure(height: Float) {
        if (y + height > BOTTOM) continuationPage()
    }

    /**
     * Lays out rows, kept on one page when they fit on one; otherwise they break between rows.
     * [background] is painted behind each page's part of the block, [onNewPage] after a break.
     */
    private fun flow(
        rows: List<Row>,
        keepTogether: Boolean = true,
        padTop: Float = 0f,
        padBottom: Float = 0f,
        background: ((Float, Float) -> List<ReportDrawing>)? = null,
        onNewPage: (() -> Unit)? = null,
    ) {
        if (rows.isEmpty()) return
        val total = padTop + rows.sumOf { it.height.toDouble() }.toFloat() + padBottom
        val first = padTop + rows.first().height + padBottom
        if (y + (if (keepTogether && total <= USABLE) total else first) > BOTTOM) {
            continuationPage()
            onNewPage?.invoke()
        }
        var start = page.drawings.size
        var top = y
        y += padTop
        for (row in rows) {
            if (y + row.height + padBottom > BOTTOM) {
                y += padBottom
                background?.let { page.drawings.addAll(start, it(top, y - top)) }
                continuationPage()
                onNewPage?.invoke()
                start = page.drawings.size
                top = y
                y += padTop
            }
            row.draw(y)
            y += row.height
        }
        y += padBottom
        background?.let { page.drawings.addAll(start, it(top, y - top)) }
    }

    private fun heading(label: String, detail: String = "", keep: Float = 36f) {
        ensure(HEADING + keep)
        y += 8f
        val title = label.uppercase(Locale.GERMAN)
        text(LEFT, y, title, 7.8f, true, teal)
        if (detail.isNotBlank()) {
            val offset = measure(title, 7.8f, true) + 8f
            text(LEFT + offset, y + 0.9f, fit(detail, FULL - offset, 6.8f, false), 6.8f, color = muted)
        }
        y += 11.5f
        line(LEFT, y, FULL, rule)
        y += 5.5f
    }

    private fun columns(leftTitle: String, leftPairs: List<Pair<String, String>>, rightTitle: String, rightPairs: List<Pair<String, String>>,
        labelWidth: Float = 92f, size: Float = 7.9f) {
        val gap = 18f
        val width = (FULL - gap) / 2f
        val second = LEFT + width + gap
        val titled = leftTitle.isNotBlank() || rightTitle.isNotBlank()
        val leftRows = (if (titled) listOf(groupRow(leftTitle, LEFT, width)) else emptyList()) +
            keyValueRows(leftPairs, LEFT, width, labelWidth, size)
        val rightRows = (if (titled) listOf(groupRow(rightTitle, second, width)) else emptyList()) +
            keyValueRows(rightPairs, second, width, labelWidth, size)
        val height = max(leftRows.sumOf { it.height.toDouble() }, rightRows.sumOf { it.height.toDouble() }).toFloat()
        if (height <= USABLE) {
            ensure(height)
            val top = y
            var cursor = top
            leftRows.forEach { it.draw(cursor); cursor += it.height }
            cursor = top
            rightRows.forEach { it.draw(cursor); cursor += it.height }
            y = top + height
        } else {
            flow(leftRows, keepTogether = false)
            y += 6f
            flow(rightRows, keepTogether = false)
        }
        y += 4f
    }

    private fun groupRow(title: String, x: Float, width: Float) = Row(15f) { top ->
        if (title.isNotBlank()) {
            text(x, top, title.uppercase(Locale.GERMAN), 7.2f, true, muted)
            line(x, top + 11f, width, rule)
        }
    }

    private fun keyValueRows(pairs: List<Pair<String, String>>, x: Float, width: Float, labelWidth: Float, size: Float): List<Row> =
        pairs.flatMap { (label, value) ->
            val labels = wrap(label, labelWidth - 6f, 6.8f, false)
            val values = wrap(value, width - labelWidth, size, false)
            (0 until max(labels.size, values.size)).map { line ->
                Row(size * 1.36f) { top ->
                    labels.getOrNull(line)?.let { text(x, top + 0.9f, it, 6.8f, color = muted) }
                    values.getOrNull(line)?.let { text(x + labelWidth, top, it, size) }
                }
            } + Row(1.8f) {}
        }

    private fun labelRow(label: String, x: Float, color: String) = Row(10f) { top -> text(x, top, label, 6.4f, true, color) }

    private fun textRows(value: String, width: Float, size: Float, bold: Boolean = false, color: String = navy, x: Float = LEFT): List<Row> =
        wrap(value, width, size, bold).map { line -> Row(size * 1.36f) { top -> text(x, top, line, size, bold, color) } }

    private fun text(x: Float, top: Float, value: String, size: Float, bold: Boolean = false, color: String = navy) {
        if (value.isEmpty()) return
        page.drawings += ReportDrawing("text", x, top, measure(value, size, bold), size * 1.3f, value, size, bold, color)
    }

    private fun rectangle(x: Float, top: Float, width: Float, height: Float, color: String, radius: Float = 0f) =
        ReportDrawing("rect", x, top, width, height, color = color, radius = radius)

    private fun rect(x: Float, top: Float, width: Float, height: Float, color: String, radius: Float = 0f) {
        page.drawings += rectangle(x, top, width, height, color, radius)
    }

    private fun line(x: Float, top: Float, width: Float, color: String) {
        page.drawings += ReportDrawing("line", x, top, width, color = color)
    }

    private fun wrap(value: String, width: Float, size: Float, bold: Boolean): List<String> =
        wrapQualityText(value, width) { measure(it, size, bold) }

    /** Only for repeated short labels whose full text appears elsewhere in the report. */
    private fun fit(value: String, width: Float, size: Float, bold: Boolean): String {
        if (measure(value, size, bold) <= width) return value
        var text = value
        while (text.isNotEmpty() && measure("$text…", size, bold) > width) text = text.dropLast(1)
        return "$text…"
    }

    private fun statusColors(status: QualityStatus): Pair<String, String> = when (status) {
        QualityStatus.OK -> "#1F7A45" to "#EAF6EE"
        QualityStatus.DEFECT -> "#B3392F" to "#FCECEA"
        QualityStatus.REWORK, QualityStatus.RECHECK -> "#93500C" to "#FFF3E2"
        else -> teal to light
    }

    private val navy = "#16303D"
    private val muted = "#5F707A"
    private val faint = "#8A989F"
    private val teal = "#0B6E7F"
    private val rule = "#D5DEE3"
    private val light = "#F1F5F7"

    private companion object {
        const val LEFT = 34f
        const val RIGHT = 561f
        const val FULL = RIGHT - LEFT
        const val BOTTOM = 796f
        const val FOOTER_LINE = 806f
        /** Content height of a continuation page. */
        const val USABLE = BOTTOM - 39f
        const val HEADING = 25f
        /** Room a position needs to start below the previous one: header, status and data. */
        const val POSITION_START = 300f
        const val PHOTO_HEIGHT = 215f
        const val PHOTO_GAP = 8f
        const val PHOTO_CAPTION = 13f
        const val LOGO_ID = "logo"
        /** Logo on the first page: at most this high and this wide. */
        const val LOGO_HEIGHT = 40f
        const val LOGO_MAX_WIDTH = 160f
        const val PORTRAIT_ASPECT = 1080f / 2340f
        /** Width of landscape photos stacked beside the order and part data. */
        const val SIDE_LANDSCAPE_WIDTH = 236f
        const val DISCLAIMER = "Namen sind lokale Angaben, der Bericht ist nicht digital signiert. AR-Fotos: visueller Vergleich, keine Maßprüfung."
    }
}

/** Attributes worth a line next to the part; the rest goes to the small print. */
private fun keyAttributes(part: QualityPart): Map<String, String> =
    part.attributes.filterKeys { it in KEY_ATTRIBUTES }.toList().sortedBy { KEY_ATTRIBUTES.indexOf(it.first) }.toMap()

private val KEY_ATTRIBUTES = listOf("profile", "PROFILE", "material", "MATERIAL", "GRADE", "lengthMm", "LENGTH",
    "webThicknessMm", "flangeThicknessMm", "WEIGHT")

/** Bolts, welds (Tekla exports them as IfcFastener) and fabrication marks: summarised, never listed. */
internal fun isFastener(part: QualityPart): Boolean =
    part.type == "Schraubengruppe" || part.type == "Fertigungsmarkierung" || part.attributes.containsKey("fastenerCategory") ||
        part.attributes["ifcClass"] in setOf("IfcFastener", "IfcMechanicalFastener", "IfcDiscreteAccessory")

private fun mainPart(parts: List<QualityPart>): QualityPart? = parts.firstOrNull { it.type == "Hauptbauteil" }

private fun plural(type: String, count: Int): String = if (count == 1) type else when (type) {
    "Blech" -> "Bleche"
    "Hauptbauteil" -> "Hauptbauteile"
    "Bauteil" -> "Bauteile"
    else -> type
}

/** Extents smallest first, so identical parts turned differently in the assembly read the same. */
private fun sortedDimensions(dimensions: String): String {
    val values = dimensions.removeSuffix("mm").split("×").map { it.trim().replace(',', '.').toDoubleOrNull() }
    if (values.size != 3 || values.any { it == null }) return dimensions
    return values.filterNotNull().sorted().joinToString(" × ") { String.format(Locale.GERMAN, "%.1f", it) } + " mm"
}

private fun profileKey(part: QualityPart) = listOf("profile", "PROFILE", "NAME_FULL", "name").firstOrNull { part.attributes[it]?.isNotBlank() == true }
private fun materialKey(part: QualityPart) = listOf("material", "MATERIAL", "GRADE").firstOrNull { part.attributes[it]?.isNotBlank() == true }
private fun profileOf(part: QualityPart) = profileKey(part)?.let { part.attributes[it] }
private fun materialOf(part: QualityPart) = materialKey(part)?.let { part.attributes[it] }

private fun edition(report: IssuedQualityReport) = report.edition.toString().padStart(2, '0')

private fun count(value: Int, one: String, many: String) = "$value ${if (value == 1) one else many}"

private fun formatInstant(at: String, zoneId: String, pattern: String): String = runCatching {
    val zone = if (zoneId.isBlank()) ZoneId.systemDefault() else ZoneId.of(zoneId)
    DateTimeFormatter.ofPattern(pattern, Locale.GERMAN).withZone(zone).format(Instant.parse(at))
}.getOrDefault(at.ifBlank { "Nicht erfasst" })

/** Date and minute, for places where the full timestamp is shown elsewhere in the report. */
internal fun shortDate(at: String, zoneId: String = ""): String = formatInstant(at, zoneId, "dd.MM.yyyy · HH:mm")

internal fun attributeLabel(key: String): String = when (key) {
    "profile", "PROFILE" -> "Profil"
    "material", "MATERIAL" -> "Werkstoff laut Modell"
    "GRADE" -> "Güte"
    "lengthMm" -> "Nennlänge [mm]"
    "LENGTH" -> "Länge"
    "webThicknessMm" -> "Stegdicke [mm]"
    "flangeThicknessMm" -> "Flanschdicke [mm]"
    "rootRadiusMm" -> "Ausrundungsradius [mm]"
    "WEIGHT" -> "Gewicht"
    "name" -> "Bezeichnung"
    "NAME_FULL" -> "Vollständige Bezeichnung"
    "objectType" -> "Objekttyp"
    "description" -> "Beschreibung"
    "PHASE" -> "Phase"
    "ifcClass" -> "IFC-Klasse"
    "sourceGlobalId" -> "Quell-ID"
    "fastenerCategory" -> "Verbindungsmittel"
    "Assembly position number" -> "Baugruppenposition"
    "triangles" -> "Dreiecke"
    else -> key
}
