package com.qs.tallerxr.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qs.tallerxr.projects.BrowserModel
import com.qs.tallerxr.quality.*
import java.io.File
import androidx.core.content.FileProvider
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun QualityWindows(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    // A staged PDF goes to the system share sheet (mail, messenger, drive…) and is forgotten here.
    LaunchedEffect(state.qualityShare) {
        val file = state.qualityShare ?: return@LaunchedEffect
        val failure = runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Prüfbericht teilen"))
        }.exceptionOrNull()
        viewModel.qualityShareConsumed(failure?.let { "Teilen nicht möglich: ${it.message ?: it.javaClass.simpleName}" })
    }
    val record = state.quality.record(state.qualityKey)
    if (record != null) {
        // «3D-Modell»: the viewer underneath already shows this piece, or its current file is in the open project.
        val currentModel = state.tree?.takeIf { state.currentProject?.id == record.piece.projectId }
            ?.allModels()?.firstOrNull { it.key == record.piece.sourceKey }
        val onOpenModel: (() -> Unit)? = when {
            state.openedPackage != null && state.openedQualityKey == record.piece.key -> viewModel::closeQuality
            currentModel != null && state.openedPackage == null -> ({ viewModel.openModelFromQuality(currentModel) })
            else -> null
        }
        QualityDetail(state, record, viewModel::closeQuality, viewModel::saveQualityCheck,
            viewModel::exportQualityPdf, viewModel::qualityPhotoFile,
            onOrder = { viewModel.openOrderDetails(record.piece.projectId) },
            onExportArchived = viewModel::exportArchivedQualityPdf, onOpenModel = onOpenModel,
            onShare = { viewModel.shareQualityReport(record.piece.key) }, onShareArchived = viewModel::shareArchivedQualityReport)
    } else if (state.qualityProjectId != null) {
        val allProjects = state.qualityProjectId == "*"
        val records = state.quality.records.filter { allProjects || it.piece.projectId == state.qualityProjectId }
        QualityOverview(
            title = if (allProjects) "QS-Archiv · alle Projekte" else state.currentProject?.name ?: "Projekt",
            records = records,
            document = state.quality,
            projectId = state.qualityProjectId,
            models = if (allProjects) emptyList() else state.tree?.allModels().orEmpty(),
            error = state.qualityError,
            busy = state.qualityBusy,
            progress = state.qualityProgress,
            message = state.qualityMessage,
            onClose = viewModel::closeQuality,
            onSelect = viewModel::selectQualityPiece,
            onExport = viewModel::exportQualityReports,
            onOpenModel = viewModel::openModelFromQuality,
            onSettings = viewModel::openQualitySettings,
            onOrder = if (allProjects) null else ({ viewModel.openOrderDetails(state.qualityProjectId) }),
            archivedCount = state.quality.reports.count { allProjects || it.record.piece.projectId == state.qualityProjectId },
            onExportAll = { uri -> viewModel.exportAllQualityReports(state.qualityProjectId, uri) },
        )
    }
    state.orderProjectId?.let { projectId ->
        key(projectId) {
            OrderDetailsScreen(viewModel.orderDetails(projectId), state.qualityBusy, state.qualityError,
                viewModel::closeOrderDetails, viewModel::saveOrderDetails)
        }
    }
    if (state.qualitySettings) {
        var name by rememberSaveable { mutableStateOf(state.quality.inspector) }
        AlertDialog(
            onDismissRequest = viewModel::closeQualitySettings,
            title = { Text("Einstellungen") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it.take(120) }, label = { Text("Name der prüfenden Person") },
                        supportingText = { Text("Wird für neue Prüfungen und AR-Fotos verwendet.") }, singleLine = true)
                    Text("Bisherige Einträge behalten ihren ursprünglichen Namen sowie Datum und Uhrzeit.",
                        style = MaterialTheme.typography.bodySmall)
                    state.qualityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.qualityMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    ReportLogoSetting(state, viewModel)
                    OutlinedButton(onClick = viewModel::openQualityArchive, enabled = !state.qualityBusy) {
                        Text("QS-Archiv öffnen")
                    }
                    Text("Die QS-Akten bleiben auch beim Entfernen eines Modells oder Projekts erhalten. " +
                        "Bei einer Deinstallation werden die lokalen Akten und Fotos gelöscht. Prüfprotokolle vorher als PDF sichern.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.saveQualityInspector(name) }, enabled = name.isNotBlank() && !state.qualityBusy) {
                    Text(if (state.qualityBusy) "Speichert…" else "Speichern")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::closeQualitySettings, enabled = !state.qualityBusy) { Text("Schließen") } },
        )
    }
}

/** Company logo printed at the top of every new report. */
@Composable
private fun ReportLogoSetting(state: AppUiState, viewModel: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importReportLogo(uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Logo für Prüfberichte", style = MaterialTheme.typography.titleSmall)
        val path = state.reportLogo
        if (path != null) {
            val bitmap by produceState<android.graphics.Bitmap?>(null, path, state.reportLogoStamp) {
                value = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            }
            Box(Modifier.fillMaxWidth().height(64.dp).background(Color.White), contentAlignment = Alignment.CenterStart) {
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Logo für Prüfberichte",
                    modifier = Modifier.padding(8.dp).fillMaxHeight(), contentScale = ContentScale.Fit) }
            }
        } else {
            Text("Kein Logo. Es erscheint oben links auf der ersten Seite neuer Berichte und klein auf den Folgeseiten.",
                style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !state.qualityBusy) {
                Text(if (path == null) "Logo wählen…" else "Anderes Logo…")
            }
            if (path != null) TextButton(onClick = viewModel::removeReportLogo, enabled = !state.qualityBusy) { Text("Entfernen") }
        }
        Text("PNG mit transparentem Hintergrund wirkt am besten. Bereits archivierte Berichte bleiben unverändert.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QualityWindow(title: String, subtitle: String, onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                        actions = actions,
                    )
                },
                content = content,
            )
        }
    }
}

@Composable
internal fun QualityStatusLabel(status: QualityStatus) {
    val color = when (status) {
        QualityStatus.OK -> Color(0xFF268844)
        QualityStatus.DEFECT, QualityStatus.REWORK -> MaterialTheme.colorScheme.error
        QualityStatus.RECHECK -> Color(0xFFB46C00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(status.label, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun QualityOverview(title: String, records: List<QualityRecord>, document: QualityDocument, projectId: String,
    models: List<BrowserModel>, error: String?, busy: Boolean, progress: String?, message: String?,
    onClose: () -> Unit, onSelect: (String) -> Unit, onExport: (Collection<String>, android.net.Uri, Boolean) -> Unit,
    onOpenModel: (BrowserModel) -> Unit, onSettings: () -> Unit, onOrder: (() -> Unit)?,
    archivedCount: Int = 0, onExportAll: (android.net.Uri) -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    val backupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onExportAll(uri)
    }
    var filter by remember { mutableStateOf<QualityStatus?>(null) }
    // Several positions can be chosen and exported together; a tap then selects instead of opening.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var exportChoice by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(listOf<String>()) }
    val visible = records.filter {
        (filter == null || it.status == filter) &&
            (query.isBlank() || "${it.piece.position} ${document.orders[it.piece.projectId]?.projectName ?: it.piece.projectName} ${it.piece.assemblyGuid} ${it.piece.sourceLabel}"
                .contains(query, ignoreCase = true))
    }.sortedByDescending { it.events.lastOrNull()?.at ?: it.registeredAt }
    val pendingModels = models.filter { model -> !qualitySourceMatches(document, projectId, model) }
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
    val chosen = selected.filter { key -> records.any { it.piece.key == key } }
    fun toggle(key: String) { selected = if (key in selected) selected - key else selected + key }
    fun finishSelection() { selecting = false; selected = emptyList() }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && pending.isNotEmpty()) { onExport(pending, uri, true); finishSelection() }
        pending = emptyList()
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && pending.isNotEmpty()) { onExport(pending, uri, false); finishSelection() }
        pending = emptyList()
    }
    QualityWindow("Qualitätssicherung", if (selecting) "${chosen.size} ausgewählt · $title" else title,
        onClose = { if (selecting) finishSelection() else onClose() },
        actions = {
            if (selecting) {
                TextButton(onClick = { selected = (chosen + visible.map { it.piece.key }).distinct() }, enabled = !busy) { Text("Alle") }
                IconButton(onClick = ::finishSelection) { Icon(Icons.Default.Close, "Auswahl beenden") }
            } else {
                IconButton(onClick = { selecting = true }, enabled = records.isNotEmpty() && !busy) {
                    Icon(Icons.Default.Checklist, "Mehrere Positionen auswählen")
                }
                if (onOrder != null) IconButton(onClick = onOrder) { Icon(Icons.AutoMirrored.Filled.Assignment, "Auftragsdaten") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Prüfername und Einstellungen") }
                Box {
                    IconButton(onClick = { more = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Weitere Aktionen") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text(if (archivedCount == 0) "Keine archivierten Berichte" else
                                "Alle Berichte sichern (${counted(archivedCount, "PDF", "PDFs")})") },
                            enabled = archivedCount > 0,
                            onClick = { more = false; backupFolder.launch(null) },
                        )
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                progress?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!selecting) item {
                    OneTimeHint(GuidePreferences.HINT_QUALITY, "So funktioniert die Qualitätssicherung",
                        "Position antippen: Status, Kommentar, Fotos und PDF-Bericht. Über das Listensymbol mehrere " +
                            "Positionen gemeinsam exportieren. Prüfername und Logo unter Einstellungen.")
                }
                item {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Position, Projekt oder GUID suchen") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Alle (${records.size})") })
                        QualityStatus.entries.forEach { status ->
                            val count = records.count { it.status == status }
                            if (status != QualityStatus.LEGACY || count > 0) FilterChip(selected = filter == status,
                                onClick = { filter = if (filter == status) null else status }, label = { Text("${status.label} ($count)") })
                        }
                    }
                    Text(if (selecting) "Positionen antippen, die gemeinsam exportiert werden sollen. «Alle» wählt die angezeigten."
                        else "Gespeicherte Modellstände · Eine geänderte Geometrie oder neue Revision erhält eine eigene QS-Akte.",
                        style = MaterialTheme.typography.bodySmall)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                items(visible, key = { it.piece.key }) { record ->
                    val isChosen = record.piece.key in chosen
                    Card(onClick = { if (selecting) toggle(record.piece.key) else onSelect(record.piece.key) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isChosen) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            else CardDefaults.cardColors()) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (selecting) Checkbox(checked = isChosen, onCheckedChange = { toggle(record.piece.key) },
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                            Column(Modifier.weight(1f).padding(if (selecting) PaddingValues(top = 16.dp, end = 16.dp, bottom = 16.dp)
                                else PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(record.piece.position, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${document.orders[record.piece.projectId]?.projectName ?: record.piece.projectName} · Rev. ${record.piece.revision} · " +
                                    (record.piece.fingerprint.take(10).ifBlank { "Altbestand" }), style = MaterialTheme.typography.labelSmall)
                                val model = models.firstOrNull { it.key == record.piece.sourceKey }
                                val binding = document.bindings[qualityBindingKey(record.piece.projectId, record.piece.sourceKey)]
                                val sourceHint = when {
                                    record.piece.fingerprint.isEmpty() -> "Modellstand im Altbestand nicht erfasst"
                                    binding?.pieceKey != record.piece.key -> "Früherer Modellstand"
                                    model != null && !qualitySourceMatches(document, projectId, model) ->
                                        "Quelldatei geändert oder nicht lesbar – aktuellen Stand erneut öffnen"
                                    projectId != "*" && model == null -> "Modell nicht mehr im Projekt vorhanden"
                                    else -> "Zuletzt geöffneter Modellstand"
                                }
                                Text(sourceHint, style = MaterialTheme.typography.bodySmall)
                                QualityStatusLabel(record.status)
                                record.lastCheck?.let { Text("${it.inspector} · ${qualityDate(it.at, it.zoneId)}", style = MaterialTheme.typography.bodySmall) }
                                Text("${counted(record.photos.size, "Foto", "Fotos")} · ${counted(record.events.size, "Verlaufseintrag", "Verlaufseinträge")}", style = MaterialTheme.typography.labelSmall)
                                if (model != null && !selecting) TextButton(onClick = { onOpenModel(model) }) { Text("Aktuelles Modell öffnen") }
                            }
                        }
                    }
                }
                if (visible.isEmpty()) item { Text("Keine QS-Akten für diese Auswahl.") }
                if (!selecting && pendingModels.isNotEmpty() && (filter == null || filter == QualityStatus.OPEN)) {
                    item { Text("Modelle erstmals oder erneut öffnen (${pendingModels.size})", style = MaterialTheme.typography.titleSmall) }
                    items(pendingModels, key = { "model:${it.key}" }) { model ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(model.title, fontWeight = FontWeight.SemiBold)
                                Text("Aktuellen Modellstand öffnen und der QS-Akte zuordnen.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onOpenModel(model) }) { Text("Modell öffnen") }
                            }
                        }
                    }
                }
            }
            if (selecting) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (chosen.isEmpty()) "Keine Position ausgewählt" else "${chosen.size} " +
                            if (chosen.size == 1) "Position" else "Positionen",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { exportChoice = true }, enabled = chosen.isNotEmpty() && !busy) { Text("PDF exportieren") }
                    }
                }
            }
        }
    }
    if (exportChoice) {
        val projectNames = records.filter { it.piece.key in chosen }
            .map { document.orders[it.piece.projectId]?.projectName ?: it.piece.projectName }
        AlertDialog(
            onDismissRequest = { exportChoice = false },
            title = { Text("${chosen.size} Prüfberichte exportieren") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Für jede Position wird wie beim Einzelexport ein neuer Stand im QS-Archiv angelegt.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedCard(onClick = {
                        exportChoice = false
                        pending = chosen
                        single.launch(qualityCollectionFileName(projectNames, java.time.LocalDate.now()))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Ein Sammelbericht (eine PDF-Datei)", fontWeight = FontWeight.SemiBold)
                            Text("Übersicht mit Status und Seitenzahlen, danach alle Positionen.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedCard(onClick = {
                        exportChoice = false
                        pending = chosen
                        folder.launch(null)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Einzelne PDFs in einen Ordner", fontWeight = FontWeight.SemiBold)
                            Text("Eine Datei je Position, benannt nach Position, Revision und Stand.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportChoice = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun QualityDetail(state: AppUiState, record: QualityRecord, onClose: () -> Unit,
    onSave: (QualityStatus?, String, String, QualityAction?) -> Unit, onExport: (String, android.net.Uri) -> Unit,
    photoFile: (QualityPhoto) -> File, onOrder: () -> Unit,
    onExportArchived: (String, android.net.Uri) -> Unit, onOpenModel: (() -> Unit)? = null,
    onShare: () -> Unit = {}, onShareArchived: (String) -> Unit = {}) {
    var tab by rememberSaveable(record.piece.key) { mutableIntStateOf(0) }
    var editing by rememberSaveable(record.piece.key) { mutableStateOf(false) }
    var selectedPhoto by remember { mutableStateOf<QualityEvent?>(null) }
    var exportingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var exportingReportId by rememberSaveable { mutableStateOf<String?>(null) }
    val reports = state.quality.reports.filter { it.record.piece.key == record.piece.key }.sortedByDescending { it.edition }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val key = exportingKey
        val reportId = exportingReportId
        exportingKey = null
        exportingReportId = null
        if (uri != null) {
            if (reportId != null) onExportArchived(reportId, uri)
            else if (key != null) onExport(key, uri)
        }
    }
    LaunchedEffect(record.events.size) { editing = false }
    QualityWindow(record.piece.position, "Qualitätssicherung · Rev. ${record.piece.revision}", onClose,
        actions = {
            if (onOpenModel != null) IconButton(onClick = onOpenModel, enabled = !state.qualityBusy) {
                Icon(Icons.Default.ViewInAr, "3D-Modell anzeigen")
            }
            IconButton(onClick = onShare, enabled = !state.qualityBusy) { Icon(Icons.Default.Share, "Prüfbericht teilen") }
            TextButton(enabled = !state.qualityBusy, onClick = {
                exportingKey = record.piece.key
                exportingReportId = null
                val name = record.piece.position.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(70)
                picker.launch("QS-Protokoll-$name-Rev${record.piece.revision}-${java.time.LocalDate.now()}.pdf")
            }) { Text("PDF exportieren") }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.qualityBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Prüfung", "Fotos (${record.photos.size})", "Verlauf (${record.events.size})", "Bauteile", "Berichte (${reports.size})")
                    .forEachIndexed { index, label ->
                        FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
                    }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    state.qualityMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    state.qualityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                when (tab) {
                    0 -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            QualityStatusLabel(record.status)
                            record.lastCheck?.let { event ->
                                Text("${event.inspector}\n${qualityDate(event.at, event.zoneId)}")
                                if (event.comment.isNotBlank()) Text(event.comment)
                            } ?: Text("Für diesen Modellstand wurde noch keine Prüfung dokumentiert.")
                            Button(onClick = { editing = true }, enabled = !state.qualityBusy) { Text("Prüfung / Kommentar dokumentieren") }
                            OutlinedButton(onClick = onOrder, enabled = !state.qualityBusy) { Text("Auftragsdaten") }
                            val order = state.quality.orders[record.piece.projectId]
                            if (order != null) Text("${order.projectName}" +
                                order.orderNumber.takeIf { it.isNotBlank() }?.let { " · Auftrag $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall)
                            record.currentAction?.let { action ->
                                Text("Nächster Schritt", fontWeight = FontWeight.SemiBold)
                                QualityActionText(action)
                            }
                            Text("Prüfumfang: ${record.piece.position} · gesamte Position / Baugruppe. " +
                                "Jeder Eintrag bleibt mit Name, Datum und Uhrzeit im Verlauf erhalten.", style = MaterialTheme.typography.bodySmall)
                            Text("${order?.projectName ?: record.piece.projectName} · Revision ${record.piece.revision}\n" +
                                "Modellstand: ${record.piece.fingerprint.take(16).ifBlank { "Im Altbestand nicht erfasst" }}",
                                style = MaterialTheme.typography.bodySmall)
                            if (record.piece.fingerprint.isEmpty()) Text("Der bisherige Prüfvermerk enthält weder einen Prüfernamen " +
                                "noch eine eindeutige Modellkennung. Er gilt nicht automatisch als Freigabe des aktuellen Modells.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("AR-Fotos werden nach dem Verankern über „AR-Foto“ aufgenommen und dieser Akte zugeordnet.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    1 -> {
                        if (record.photos.isEmpty()) item { Text("Noch keine AR-Fotos vorhanden.") }
                        items(record.photos.reversed(), key = { it.id }) { event ->
                            Card(onClick = { selectedPhoto = event }, modifier = Modifier.fillMaxWidth()) {
                                QualityPhotoImage(photoFile(checkNotNull(event.photo)), Modifier.fillMaxWidth().height(190.dp))
                                Column(Modifier.padding(12.dp)) {
                                    Text("${event.inspector} · ${qualityDate(event.at, event.zoneId)}", style = MaterialTheme.typography.bodySmall)
                                    Text(event.photo.alignment, style = MaterialTheme.typography.labelSmall)
                                    Text("Foto öffnen", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    2 -> {
                        if (record.events.isEmpty()) item { Text("Noch keine Einträge vorhanden.") }
                        items(record.events.reversed(), key = { it.id }) { event ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(qualityDate(event.at, event.zoneId), fontWeight = FontWeight.SemiBold)
                                    Text(event.inspector)
                                    event.status?.let { QualityStatusLabel(it) }
                                        ?: Text(if (event.photo != null) "AR-Foto hinzugefügt" else "Kommentar / Maßnahme ergänzt")
                                    if (event.comment.isNotBlank()) Text(event.comment)
                                    event.action?.let { QualityActionText(it) }
                                    if (event.photo != null) TextButton(onClick = { selectedPhoto = event }) { Text("AR-Foto ansehen") }
                                }
                            }
                        }
                    }
                    3 -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Projekt: ${state.quality.orders[record.piece.projectId]?.projectName ?: record.piece.projectName}")
                                Text("Position: ${record.piece.position} · Revision ${record.piece.revision}")
                                Text("Quelle: ${record.piece.sourceLabel}")
                                Text("Modelldatum: ${qualityDate(record.piece.modelCreatedAt)}")
                                Text("Baugruppen-GUID ${record.piece.assemblyGuid} · Modellkennung " +
                                    record.piece.fingerprint.ifBlank { "nicht erfasst" },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(counted(record.piece.parts.size, "Bauteil", "Bauteile"), style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        items(record.piece.parts) { part ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(part.position, fontWeight = FontWeight.Bold)
                                    partSummary(part.attributes, part.dimensions)?.let { Text(it) }
                                    Text("Typ: ${part.type}", style = MaterialTheme.typography.bodySmall)
                                    labelledAttributes(part.attributes).forEach { (label, value) ->
                                        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("GUID ${part.guid}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    4 -> {
                        item { Text("Jede Ausgabe bleibt mit den damaligen Auftragsdaten und Prüfvermerken gespeichert.",
                            style = MaterialTheme.typography.bodySmall) }
                        if (reports.isEmpty()) item { Text("Noch keine Berichte erstellt. Über „PDF exportieren“ die erste Ausgabe erstellen.") }
                        items(reports, key = { it.id }) { report ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${report.number} · Stand ${report.edition.toString().padStart(2, '0')}", fontWeight = FontWeight.SemiBold)
                                    Text(report.order.projectName)
                                    Text(qualityDate(report.createdAt, report.zoneId), style = MaterialTheme.typography.bodySmall)
                                    Text("Erstellt von: ${report.exporter.ifBlank { "Nicht angegeben" }}", style = MaterialTheme.typography.bodySmall)
                                    QualityStatusLabel(report.record.status)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(enabled = !state.qualityBusy, onClick = {
                                            exportingKey = null
                                            exportingReportId = report.id
                                            picker.launch(report.suggestedFileName)
                                        }) { Text("Als PDF speichern") }
                                        TextButton(enabled = !state.qualityBusy, onClick = { onShareArchived(report.id) }) { Text("Teilen") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (editing) QualityCheckEditor(record, state.quality.inspector, state.qualityBusy, state.qualityError,
        onDismiss = { if (!state.qualityBusy) editing = false }, onSave = onSave)
    selectedPhoto?.let { event ->
        QualityWindow("AR-Foto", "${event.inspector} · ${qualityDate(event.at, event.zoneId)}", { selectedPhoto = null }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(Color.Black), contentAlignment = Alignment.Center) {
                QualityPhotoImage(photoFile(checkNotNull(event.photo)), Modifier.fillMaxSize(), zoom = true)
            }
        }
    }
}

@Composable
private fun QualityCheckEditor(record: QualityRecord, inspector: String, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (QualityStatus?, String, String, QualityAction?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(inspector) }
    var comment by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<QualityStatus?>(null) }
    var menu by remember { mutableStateOf(false) }
    val originalAction = record.currentAction ?: QualityAction()
    var actionText by rememberSaveable { mutableStateOf(originalAction.description) }
    var assignee by rememberSaveable { mutableStateOf(originalAction.assignee) }
    var due by rememberSaveable { mutableStateOf(qualityActionDate(originalAction.dueDate)) }
    var actionExpanded by rememberSaveable { mutableStateOf(!originalAction.isEmpty) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val actionAllowed = (selected ?: record.status) in setOf(QualityStatus.DEFECT, QualityStatus.REWORK, QualityStatus.RECHECK)
    val actionChanged = actionAllowed && (actionText != originalAction.description || assignee != originalAction.assignee ||
        due != qualityActionDate(originalAction.dueDate))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Prüfung dokumentieren") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${record.piece.position} · Rev. ${record.piece.revision}")
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !busy) { Text(selected?.label ?: "Status beibehalten · Kommentar") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Status beibehalten · Kommentar / Maßnahme") }, onClick = { selected = null; menu = false })
                    QualityStatus.entries.filter { it != QualityStatus.LEGACY }.forEach { status ->
                        DropdownMenuItem(text = { Text(status.label) }, onClick = { selected = status; menu = false })
                    }
                }
            }
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Geprüft von · Pflichtfeld") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(comment, { comment = it.take(4000) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (selected == null && !actionChanged) "Kommentar · Pflichtfeld" else "Kommentar / festgestellte Abweichung") },
                minLines = 3, maxLines = 6, enabled = !busy, supportingText = { Text("${comment.length}/4000") })
            if (actionAllowed) {
                TextButton(onClick = { actionExpanded = !actionExpanded }, enabled = !busy) {
                    Text(if (actionExpanded) "Maßnahme ausblenden" else "Maßnahme / nächsten Schritt angeben")
                }
                if (actionExpanded) {
                    OutlinedTextField(actionText, { actionText = it.take(2000); validationError = null },
                        label = { Text("Nächster Schritt / Maßnahme · optional") }, minLines = 2, maxLines = 4,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(assignee, { assignee = it.take(120) }, label = { Text("Zuständige Person · optional") },
                        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(due, { due = it.take(10); validationError = null }, label = { Text("Termin · TT.MM.JJJJ · optional") },
                        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = validationError != null)
                }
            }
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Datum und Uhrzeit werden beim Speichern automatisch erfasst. Der bisherige Verlauf bleibt erhalten.",
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Button(enabled = !busy && name.isNotBlank() && (selected != null || comment.isNotBlank() || actionChanged),
            onClick = {
                try {
                    val action = if (actionChanged) QualityAction(actionText, assignee, parseQualityActionDate(due)) else null
                    onSave(selected, name, comment, action)
                } catch (invalid: IllegalArgumentException) { validationError = invalid.message }
            }) { Text(if (busy) "Speichert…" else "Eintrag speichern") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Abbrechen") } })
}

@Composable
private fun QualityActionText(action: QualityAction) {
    if (action.isEmpty) Text("Nächster Schritt entfernt.", style = MaterialTheme.typography.bodySmall)
    if (action.description.isNotBlank()) Text(action.description)
    if (action.assignee.isNotBlank()) Text("Zuständig: ${action.assignee}", style = MaterialTheme.typography.bodySmall)
    if (action.dueDate.isNotBlank()) Text("Termin: ${qualityActionDate(action.dueDate)}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun QualityPhotoImage(file: File, modifier: Modifier, zoom: Boolean = false) {
    var loadFinished by remember(file.path, zoom) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path, zoom) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val max = if (zoom) 2560 else 600
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > max || bounds.outHeight / inSampleSize > max) inSampleSize *= 2
            }
            BitmapFactory.decodeFile(file.path, options)
        }
        loadFinished = true
    }
    var scale by remember(file.path) { mutableFloatStateOf(1f) }
    var x by remember(file.path) { mutableFloatStateOf(0f) }
    var y by remember(file.path) { mutableFloatStateOf(0f) }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = "AR-Foto mit Modellüberlagerung",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().then(if (!zoom) Modifier else Modifier.pointerInput(file.path) {
                    detectTransformGestures { _, pan, factor, _ ->
                        scale = (scale * factor).coerceIn(1f, 5f)
                        x = if (scale == 1f) 0f else (x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2)
                        y = if (scale == 1f) 0f else (y + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2)
                    }
                }).graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y })
        } else Text(if (!loadFinished) "Foto wird geladen…" else "Foto nicht verfügbar", modifier = Modifier.padding(12.dp))
    }
}
