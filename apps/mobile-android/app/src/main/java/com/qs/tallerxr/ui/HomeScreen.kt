package com.qs.tallerxr.ui

import com.qs.tallerxr.BuildConfig

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qs.tallerxr.R
import com.qs.tallerxr.projects.ProjectSource
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val PROJECT_FILE_TYPES = arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    uiState: AppUiState,
    onRefresh: () -> Unit,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
    onImportProjectFile: (Uri) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onCancelBusy: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissExitNotice: () -> Unit,
    onOpenInfo: (InfoPage) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjectQuality: (String) -> Unit,
    onOpenOrder: (String) -> Unit,
) {
    var showNewProject by remember { mutableStateOf(false) }
    var namingNewProject by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectCardState?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectCardState?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportProjectFile(uri)
    }
    val idle = !uiState.loading && uiState.busy == null

    if (showNewProject) {
        NewProjectDialog(
            onDismiss = { showNewProject = false },
            onEmpty = {
                showNewProject = false
                namingNewProject = true
            },
            onFile = {
                showNewProject = false
                filePicker.launch(PROJECT_FILE_TYPES)
            },
        )
    }
    if (namingNewProject) {
        NameProjectDialog(
            title = "Neues Projekt",
            initialName = "",
            description = "Das Projekt wird leer angelegt. Danach verknüpfen Sie im Projekt einen oder mehrere " +
                "Ordner mit GLB- oder IFC-Dateien. Die Dateien bleiben, wo sie sind: Es wird nichts importiert " +
                "oder kopiert.",
            confirmLabel = "Projekt anlegen",
            onConfirm = { name ->
                namingNewProject = false
                onCreateProject(name)
            },
            onDismiss = { namingNewProject = false },
        )
    }
    renameTarget?.let { project ->
        NameProjectDialog(
            title = "Projekt umbenennen",
            initialName = project.name,
            description = "Ändert nur den Namen innerhalb der App.",
            confirmLabel = "Speichern",
            onConfirm = { name ->
                onRenameProject(project.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { project ->
        DeleteProjectDialog(
            project = project,
            onConfirm = {
                onDeleteProject(project.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 6.dp).size(48.dp),
                    )
                },
                title = {
                    Column {
                        Text("QS Taller XR", fontWeight = FontWeight.Bold)
                        Text("3D · AR · QS", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenInfo(InfoPage.HELP) }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "So funktioniert's")
                    }
                    IconButton(enabled = idle, onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                    IconButton(enabled = idle, onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                    }
                    IconButton(enabled = idle, onClick = { onOpenInfo(InfoPage.ABOUT) }) {
                        Icon(Icons.Default.Info, contentDescription = "Über die App")
                    }
                },
            )
        },
        floatingActionButton = {
            if (idle && uiState.projects.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showNewProject = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Neues Projekt") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (uiState.loading) {
                item { BusyPanel(BusyState("Projekte werden geladen", uiState.loadingStage, canCancel = false), onCancel = {}) }
            }
            uiState.busy?.let { busy -> item { BusyPanel(busy, onCancelBusy) } }
            item { MessageBanner(uiState, onDismissMessage, onDismissExitNotice) }
            if (!uiState.loading && uiState.projects.isEmpty() && uiState.busy == null) {
                item {
                    EmptyHome(
                        onEmpty = { namingNewProject = true },
                        onFile = { filePicker.launch(PROJECT_FILE_TYPES) },
                        onHelp = { onOpenInfo(InfoPage.HELP) },
                    )
                }
            }
            if (uiState.projects.isNotEmpty()) {
                item {
                    Text(
                        "Projekte (${uiState.projects.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            items(uiState.projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    enabled = idle,
                    onOpen = { onOpenProject(project.id) },
                    onOpenQuality = { onOpenProjectQuality(project.id) },
                    onOpenOrder = { onOpenOrder(project.id) },
                    onRename = { renameTarget = project },
                    onDelete = { deleteTarget = project },
                )
            }
            if (!uiState.loading && uiState.projects.isNotEmpty()) {
                item {
                    Text(
                        "${uiState.storage.packageCount} Modelle · belegt ${formatBytes(uiState.storage.installedBytes)} · " +
                            "frei ${formatBytes(uiState.storage.availableBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (!uiState.loading) {
                item {
                    // Privacy, imprint and licences are under «Über die App» at the top.
                    Text(
                        ARCORE_NOTICE_DE,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectCardState,
    enabled: Boolean,
    onOpen: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenOrder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (project.source == ProjectSource.FOLDER) Icons.Default.Folder else Icons.Default.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    project.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (project.missingFolders.isNotEmpty()) {
                    Text(
                        "Nicht gefunden: ${project.missingFolders.joinToString { "«$it»" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    buildString {
                        append(counted(project.modelCount, "Modell", "Modelle"))
                        if (project.source == ProjectSource.FOLDER) append(" · ${project.readyCount} vorbereitet")
                        project.lastSync?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(enabled = enabled, onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Projektoptionen")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Auftragsdaten") }, onClick = { menu = false; onOpenOrder() })
                    DropdownMenuItem(text = { Text("Qualitätssicherung") }, onClick = {
                        menu = false
                        onOpenQuality()
                    })
                    if (project.source == ProjectSource.FOLDER) {
                        DropdownMenuItem(
                            text = { Text("Umbenennen") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menu = false
                                onRename()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Vom Telefon entfernen") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHome(onEmpty: () -> Unit, onFile: () -> Unit, onHelp: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Erstes Projekt anlegen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "1. Projekt anlegen. 2. Darin einen Ordner vom Telefon, von einer Speicherkarte oder einem " +
                    "USB-Stick verknüpfen, der Ihre IFC- oder GLB-Dateien enthält – je Baugruppe eine Datei. " +
                    "Die Dateien bleiben, wo sie sind; Unterordner werden zu Ebenen des Projekts." +
                    if (BuildConfig.BUNDLED_DEMO_ENABLED) {
                        " Zum Ausprobieren enthält die Debug-Ausgabe das Projekt «Demo · Testmodelle»."
                    } else "",
                style = MaterialTheme.typography.bodySmall,
            )
            FolderTreeExample()
            Button(onClick = onEmpty, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Neues Projekt anlegen")
            }
            OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Inventory2, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Projekt aus einer .qsproject-Datei")
            }
            TextButton(onClick = onHelp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("So funktioniert's")
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onEmpty: () -> Unit, onFile: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Projekt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(onClick = onEmpty, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Neues Projekt", fontWeight = FontWeight.Bold)
                            Text(
                                "Leer anlegen und danach Ordner mit GLB- oder IFC-Dateien verknüpfen.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Card(onClick = onFile, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Aus einer .qsproject-Datei", fontWeight = FontWeight.Bold)
                            Text(
                                "Mit dem PC-Viewer exportiertes Projekt samt Revisionen.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
internal fun NameProjectDialog(
    title: String,
    initialName: String,
    description: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    singleLine = true,
                    label = { Text("Projektname") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
internal fun DeleteProjectDialog(project: ProjectCardState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("«${project.name}» entfernen?") },
        text = {
            Text(
                if (project.source == ProjectSource.FOLDER) {
                    "Die QS-Akten mit Verlauf und Fotos bleiben im QS-Archiv erhalten. Das Projekt wird samt Ordnerverknüpfungen und Zwischenspeicher aus der App " +
                        "entfernt. Die verknüpften Ordner und Ihre GLB-/IFC-Dateien bleiben unverändert."
                } else {
                    "Die QS-Akten mit Verlauf und Fotos bleiben im QS-Archiv erhalten. Die ${project.modelCount} Revisionen werden vom Telefon gelöscht. " +
                        "Die ursprüngliche .qsproject-Datei bleibt unverändert."
                },
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Entfernen") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
internal fun MessageBanner(uiState: AppUiState, onDismiss: () -> Unit, onDismissExit: () -> Unit) {
    val error = uiState.error
    val message = error ?: uiState.status.takeIf(String::isNotBlank)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (error != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Hinweis ausblenden") }
                }
            }
        }
        uiState.lastAbnormalExit?.let { diagnostic ->
            var showDetails by remember(diagnostic) { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(diagnostic, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismissExit) {
                            Icon(Icons.Default.Close, contentDescription = "Hinweis zur früheren Sitzung ausblenden")
                        }
                    }
                    uiState.lastAbnormalExitDetails?.let { details ->
                        TextButton(onClick = { showDetails = !showDetails }) {
                            Text(if (showDetails) "Technische Details ausblenden" else "Technische Details")
                        }
                        if (showDetails) SelectionContainer {
                            Text(details, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("Gespeicherte Einträge: Über die App → Diagnose.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun BusyPanel(busy: BusyState, onCancel: () -> Unit) {
    var now by remember(busy.startedAtMillis) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(busy.startedAtMillis) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val elapsed = ((now - busy.startedAtMillis).coerceAtLeast(0L) / 1_000L)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(busy.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                busy.progress?.let { Text("${(it * 100).roundToInt()} %") }
            }
            val progress = busy.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(busy.stage, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dauer: ${elapsed / 60}:${"%02d".format(elapsed % 60)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (busy.canCancel) OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
    }
}
