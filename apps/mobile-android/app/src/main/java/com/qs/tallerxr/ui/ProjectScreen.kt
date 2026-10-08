package com.qs.tallerxr.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qs.tallerxr.projects.BrowserFolder
import com.qs.tallerxr.projects.BrowserModel
import com.qs.tallerxr.projects.ModelFormat
import com.qs.tallerxr.projects.ModelOrigin
import com.qs.tallerxr.projects.ModelState
import com.qs.tallerxr.projects.ProjectSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectScreen(
    uiState: AppUiState,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onNavigate: (List<String>) -> Unit,
    onSearch: (String) -> Unit,
    onOpenModel: (BrowserModel) -> Unit,
    onPrepareAgain: (BrowserModel) -> Unit,
    onForgetPrepared: (BrowserModel) -> Unit,
    onDeleteRevision: (BrowserModel) -> Unit,
    onSync: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenOrder: () -> Unit,
    onLinkFolder: (Uri) -> Unit,
    onRelinkFolder: (String, Uri) -> Unit,
    onUnlinkFolder: (String) -> Unit,
    onPrepareAll: () -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onCancelBusy: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissExitNotice: () -> Unit,
) {
    val screen = uiState.screen as? AppScreen.Project ?: return
    val project = uiState.currentProject
    val root = uiState.tree
    val folder = root?.resolve(screen.path)
    val isFolderProject = project?.source == ProjectSource.FOLDER
    val interactive = uiState.busy == null && uiState.opening == null
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var deleteRevision by remember { mutableStateOf<BrowserModel?>(null) }
    var manageFolders by remember { mutableStateOf(false) }
    var unlinkTarget by remember { mutableStateOf<LinkedFolderState?>(null) }
    // Survives the trip to the system folder picker, which may recreate the activity.
    var relinkTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val linkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onLinkFolder(uri)
    }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = relinkTarget
        relinkTarget = null
        if (uri != null && target != null) onRelinkFolder(target, uri)
    }

    if (manageFolders) {
        ManageFoldersDialog(
            folders = uiState.linkedFolders,
            enabled = interactive,
            onAdd = {
                manageFolders = false
                linkPicker.launch(null)
            },
            onRelink = { folder ->
                manageFolders = false
                relinkTarget = folder.id
                relinkPicker.launch(null)
            },
            onUnlink = { folder ->
                manageFolders = false
                unlinkTarget = folder
            },
            onDismiss = { manageFolders = false },
        )
    }
    unlinkTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { unlinkTarget = null },
            title = { Text("Verknüpfung mit «${folder.label}» entfernen?") },
            text = {
                Text(
                    "Die ${folder.modelCount} Modelle dieses Ordners verschwinden aus dem Projekt, zusammen mit ihrem " +
                        "Zwischenspeicher. Der Ordner und seine Dateien werden nicht verändert.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUnlinkFolder(folder.id)
                        unlinkTarget = null
                    },
                ) { Text("Entfernen") }
            },
            dismissButton = { OutlinedButton(onClick = { unlinkTarget = null }) { Text("Abbrechen") } },
        )
    }

    if (rename && project != null) {
        NameProjectDialog(
            title = "Projekt umbenennen",
            initialName = project.name,
            description = "Ändert nur den Namen innerhalb der App.",
            confirmLabel = "Speichern",
            onConfirm = { name ->
                onRenameProject(project.id, name)
                rename = false
            },
            onDismiss = { rename = false },
        )
    }
    if (delete && project != null) {
        DeleteProjectDialog(
            project = project,
            onConfirm = {
                delete = false
                onDeleteProject(project.id)
            },
            onDismiss = { delete = false },
        )
    }
    deleteRevision?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteRevision = null },
            title = { Text("Diese Revision löschen?") },
            text = { Text("${model.title}. Die ursprüngliche .qsproject-Datei bleibt unverändert.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteRevision(model)
                        deleteRevision = null
                    },
                ) { Text("Löschen") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteRevision = null }) { Text("Abbrechen") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Eine Ebene nach oben")
                    }
                },
                title = {
                    Column {
                        Text(
                            project?.name ?: "Projekt",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            project?.location.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (isFolderProject) {
                        IconButton(enabled = interactive && !uiState.syncing, onClick = onSync) {
                            Icon(Icons.Default.Sync, contentDescription = "Mit dem Ordner synchronisieren")
                        }
                    }
                    Box {
                        IconButton(enabled = interactive, onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Projektoptionen")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Auftragsdaten") }, onClick = { menu = false; onOpenOrder() })
                            DropdownMenuItem(text = { Text("Qualitätssicherung") }, onClick = {
                                menu = false
                                onOpenQuality()
                            })
                            if (isFolderProject) {
                                DropdownMenuItem(
                                    text = { Text("Ordner verknüpfen") },
                                    onClick = {
                                        menu = false
                                        linkPicker.launch(null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Ordner verwalten") },
                                    onClick = {
                                        menu = false
                                        manageFolders = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Alle Modelle vorbereiten") },
                                    onClick = {
                                        menu = false
                                        onPrepareAll()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Projekt umbenennen") },
                                    onClick = {
                                        menu = false
                                        rename = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Vom Telefon entfernen") },
                                onClick = {
                                    menu = false
                                    delete = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (uiState.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Breadcrumbs(
                projectName = project?.name ?: "Projekt",
                path = screen.path,
                enabled = interactive,
                onHome = onHome,
                onNavigate = onNavigate,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.busy?.let { busy -> item { BusyPanel(busy, onCancelBusy) } }
                item { MessageBanner(uiState, onDismissMessage, onDismissExitNotice) }
                uiState.treeNotice?.let { notice ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(notice, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(enabled = interactive, onClick = { manageFolders = true }) {
                                    Text("Ordner verwalten")
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = uiState.search,
                        onValueChange = onSearch,
                        enabled = interactive,
                        singleLine = true,
                        label = { Text("Suchen in ${folder?.name ?: "diesem Projekt"}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isFolderProject && uiState.linkedFolders.isEmpty() && uiState.busy == null) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Noch kein Ordner verknüpft", fontWeight = FontWeight.Bold)
                                Text(
                                    "Wählen Sie den Ordner, in dem die IFC- oder GLB-Dateien des Projekts liegen – auf " +
                                        "dem Telefon, einer Speicherkarte oder einem USB-Stick. Das Projekt merkt sich nur, " +
                                        "welche Ordner dazugehören; die Dateien bleiben, wo sie sind. Unterordner werden " +
                                        "zu Ebenen, jede Datei ist eine Baugruppe. Weitere Ordner lassen sich später über " +
                                        "⋮ → «Ordner verknüpfen» hinzufügen.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                FolderTreeExample()
                                Button(enabled = interactive, onClick = { linkPicker.launch(null) }) {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                    Text("  Ordner verknüpfen")
                                }
                            }
                        }
                    }
                } else if (folder == null) {
                    item { Text("Projekt wird geladen…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (uiState.search.isNotBlank()) {
                    val results = folder.search(uiState.search)
                    item {
                        Text(
                            "${results.size} Treffer",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(results, key = { (model, _) -> "search:${model.key}" }) { (model, path) ->
                        ModelCard(
                            model = model,
                            location = path.drop(folder.path.size).joinToString(" › ").ifBlank { null },
                            enabled = interactive,
                            onOpen = { onOpenModel(model) },
                            onPrepareAgain = { onPrepareAgain(model) },
                            onForgetPrepared = { onForgetPrepared(model) },
                            onDeleteRevision = { deleteRevision = model },
                        )
                    }
                } else {
                    if (folder.folders.isEmpty() && folder.models.isEmpty()) {
                        item {
                            Text(
                                if (isFolderProject && folder.path.isEmpty()) {
                                    "In den verknüpften Ordnern liegen keine IFC- oder GLB-Dateien. Legen Sie die Dateien " +
                                        "dort ab (auch in Unterordnern) und tippen Sie auf ⟳, oder verknüpfen Sie über ⋮ " +
                                        "einen anderen Ordner."
                                } else {
                                    "Hier liegen keine IFC- oder GLB-Dateien."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (isFolderProject && folder.path.isEmpty()) {
                        item {
                            OneTimeHint(
                                GuidePreferences.HINT_PROJECT,
                                "Projekt durchsuchen",
                                "Ordner öffnen die nächste Ebene, Modelle die 3D-Ansicht. ⟳ liest neue Dateien ein; " +
                                    "⋮ führt zu Qualitätssicherung, Auftragsdaten und Ordnern.",
                            )
                        }
                    }
                    items(folder.folders, key = { "folder:${it.path.joinToString("/")}" }) { child ->
                        FolderCard(child, enabled = interactive, onOpen = { onNavigate(child.path) })
                    }
                    items(folder.models, key = { "model:${it.key}" }) { model ->
                        ModelCard(
                            model = model,
                            location = null,
                            enabled = interactive,
                            onOpen = { onOpenModel(model) },
                            onPrepareAgain = { onPrepareAgain(model) },
                            onForgetPrepared = { onForgetPrepared(model) },
                            onDeleteRevision = { deleteRevision = model },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(
    projectName: String,
    path: List<String>,
    enabled: Boolean,
    onHome: () -> Unit,
    onNavigate: (List<String>) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(enabled = enabled, onClick = onHome) {
            Icon(Icons.Default.Home, contentDescription = "Alle Projekte")
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
        AssistChip(enabled = enabled, onClick = { onNavigate(emptyList()) }, label = { Text(projectName, maxLines = 1) })
        path.forEachIndexed { index, segment ->
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            AssistChip(
                enabled = enabled,
                onClick = { onNavigate(path.take(index + 1)) },
                label = { Text(segment, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun FolderCard(folder: BrowserFolder, enabled: Boolean, onOpen: () -> Unit) {
    Card(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        // One line per folder: a project with hundreds of drawings stays scannable.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(folder.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(
                buildString {
                    append(counted(folder.modelCount, "Modell", "Modelle"))
                    if (folder.folders.isNotEmpty()) append(" · ${folder.folders.size} Ordner")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun ModelCard(
    model: BrowserModel,
    location: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onPrepareAgain: () -> Unit,
    onForgetPrepared: () -> Unit,
    onDeleteRevision: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ViewInAr, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                location?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    model.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateLabel(model)
            }
            Box {
                IconButton(enabled = enabled, onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Modelloptionen")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    when (model.origin) {
                        is ModelOrigin.FolderFile -> {
                            DropdownMenuItem(
                                text = { Text("Neu vorbereiten und öffnen") },
                                onClick = {
                                    menu = false
                                    onPrepareAgain()
                                },
                            )
                            if (model.state == ModelState.READY || model.state == ModelState.STALE) {
                                DropdownMenuItem(
                                    text = { Text("Speicher dieses Modells freigeben") },
                                    onClick = {
                                        menu = false
                                        onForgetPrepared()
                                    },
                                )
                            }
                        }
                        is ModelOrigin.InstalledRevision -> DropdownMenuItem(
                            text = { Text("Diese Revision löschen") },
                            onClick = {
                                menu = false
                                onDeleteRevision()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StateLabel(model: BrowserModel) {
    val (text, color) = when (model.state) {
        ModelState.READY -> "Bereit · öffnet sofort" to Color(0xFF5AD67D)
        ModelState.PENDING -> when (model.format) {
            ModelFormat.IFC -> "Wird beim Öffnen vorbereitet (IFC wird eingelesen)" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> "Wird beim Öffnen vorbereitet" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        ModelState.STALE -> "Datei wurde geändert · wird beim Öffnen aktualisiert" to Color(0xFFFFB703)
        ModelState.ERROR -> (model.error ?: "Konnte nicht vorbereitet werden") to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
            Text(
                model.format.label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ManageFoldersDialog(
    folders: List<LinkedFolderState>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onRelink: (LinkedFolderState) -> Unit,
    onUnlink: (LinkedFolderState) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verknüpfte Ordner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Das Projekt speichert nur, welche Ordner dazugehören. Dateien werden weder importiert noch kopiert.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (folders.isEmpty()) {
                    Text("Noch kein Ordner verknüpft.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                folders.forEach { folder ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (folder.available) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(folder.label, fontWeight = FontWeight.Bold)
                            Text(
                                if (folder.available) {
                                    counted(folder.modelCount, "Modell", "Modelle") + folder.scannedAt?.let { " · gelesen $it" }.orEmpty()
                                } else {
                                    "Nicht gefunden: gelöscht, verschoben oder Speicher nicht verbunden. " +
                                        "${counted(folder.modelCount, "Modell", "Modelle")} aus dem letzten Stand."
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = enabled, onClick = { onRelink(folder) }) { Text("Neu verknüpfen") }
                                OutlinedButton(enabled = enabled, onClick = { onUnlink(folder) }) { Text("Entfernen") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(enabled = enabled, onClick = onAdd) { Text("Ordner verknüpfen") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
