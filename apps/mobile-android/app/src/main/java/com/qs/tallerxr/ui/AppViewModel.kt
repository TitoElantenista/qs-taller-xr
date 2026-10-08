package com.qs.tallerxr.ui

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.os.SystemClock
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qs.tallerxr.quality.*
import com.qs.tallerxr.ar.SavedArPhoto
import com.qs.tallerxr.data.AssemblyCheckRecord
import com.qs.tallerxr.data.ImportCase
import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.data.InstalledPackageSummary
import com.qs.tallerxr.data.ProjectImportResult
import com.qs.tallerxr.data.QsPackageRepository
import com.qs.tallerxr.data.StorageStats
import com.qs.tallerxr.data.WorkshopStateRepository
import com.qs.tallerxr.data.allocatableStorageBytes
import com.qs.tallerxr.diagnostics.ProcessExitDiagnostics
import com.qs.tallerxr.projects.BrowserFolder
import com.qs.tallerxr.projects.BrowserModel
import com.qs.tallerxr.projects.FolderProjectDocument
import com.qs.tallerxr.projects.FolderProjectRepository
import com.qs.tallerxr.projects.FolderUnavailableException
import com.qs.tallerxr.projects.LinkedFolder
import com.qs.tallerxr.projects.ModelFormat
import com.qs.tallerxr.projects.ModelOrigin
import com.qs.tallerxr.projects.ModelPreparer
import com.qs.tallerxr.projects.ModelState
import com.qs.tallerxr.projects.NaturalOrder
import com.qs.tallerxr.projects.ProjectListing
import com.qs.tallerxr.projects.ProjectSource
import com.qs.tallerxr.projects.ProjectTree
import com.qs.tallerxr.projects.SourceEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the operator is.  The viewer is not a screen: it opens on top of a project folder. */
internal sealed interface AppScreen {
    data object Home : AppScreen
    data class Project(val projectId: String, val path: List<String> = emptyList()) : AppScreen

    /** About, privacy policy, imprint and licences; reached from the home screen. */
    data class Info(val page: InfoPage) : AppScreen
}

internal data class ProjectCardState(
    val id: String,
    val name: String,
    val source: ProjectSource,
    val modelCount: Int,
    val readyCount: Int,
    val location: String,
    val lastSync: String?,
    /** Labels of linked folders that could not be found when the app last looked. */
    val missingFolders: List<String> = emptyList(),
)

/** One folder linked to the project on screen, with what the last look at it found. */
internal data class LinkedFolderState(
    val id: String,
    val label: String,
    val available: Boolean,
    val modelCount: Int,
    val scannedAt: String?,
)

/** A long operation that owns the home screen: folder scan, `.qsproject` import, bulk preparation. */
internal data class BusyState(
    val title: String,
    val stage: String,
    val progress: Float? = null,
    val startedAtMillis: Long = SystemClock.elapsedRealtime(),
    val canCancel: Boolean = true,
)

internal data class OpeningState(val title: String, val stage: String)

internal data class AppUiState(
    val loading: Boolean = true,
    val loadingStage: String = "Lokaler Speicher wird vorbereitet…",
    val projects: List<ProjectCardState> = emptyList(),
    val screen: AppScreen = AppScreen.Home,
    val tree: BrowserFolder? = null,
    val treeNotice: String? = null,
    val syncing: Boolean = false,
    val search: String = "",
    val busy: BusyState? = null,
    val linkedFolders: List<LinkedFolderState> = emptyList(),
    val opening: OpeningState? = null,
    val openedPackage: InstalledPackage? = null,
    val openedSummary: InstalledPackageSummary? = null,
    val openedRevisions: List<InstalledPackage> = emptyList(),
    val openedSubtitle: String? = null,
    val checks: Map<String, AssemblyCheckRecord> = emptyMap(),
    val quality: QualityDocument = QualityDocument(),
    val openedQualityKey: String? = null,
    val qualityProjectId: String? = null,
    val qualityKey: String? = null,
    val qualitySettings: Boolean = false,
    /** Company logo for reports (private file) and when it last changed, for the preview. */
    val reportLogo: String? = null,
    val reportLogoStamp: Long = 0L,
    val orderProjectId: String? = null,
    val qualityBusy: Boolean = false,
    /** Step of a longer QS operation, e.g. «Prüfbericht 3 von 12». */
    val qualityProgress: String? = null,
    val qualityMessage: String? = null,
    /** A report PDF staged under its proper name for the system share sheet; cleared once handed over. */
    val qualityShare: File? = null,
    val qualityError: String? = null,
    val storage: StorageStats = StorageStats(0L, 0L, 0L, 0),
    val status: String = "",
    val error: String? = null,
    val lastAbnormalExit: String? = null,
    val lastAbnormalExitDetails: String? = null,
) {
    val currentProject: ProjectCardState?
        get() = (screen as? AppScreen.Project)?.let { project -> projects.firstOrNull { it.id == project.projectId } }

    /** Anything that must not be interrupted by opening another project or model. */
    val locked: Boolean get() = busy != null || opening != null || openedPackage != null
}

/**
 * Single owner of the app state: projects, the folder the operator is browsing and the model that
 * is open.  Screens only render this state and send intents back, so entering or leaving a
 * project or a viewer can never leave two screens disagreeing about what is open.
 */
internal class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val packages = QsPackageRepository(application.applicationContext)
    private val folders = FolderProjectRepository(application.applicationContext)
    private val preparer = ModelPreparer(application.applicationContext, folders)
    private val workshop = WorkshopStateRepository(application.applicationContext)
    private val qualityStore = QualityStore(File(application.filesDir, "qsxr/quality"))
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var installed: List<InstalledPackageSummary> = emptyList()
    private var folderProjects: List<FolderProjectDocument> = emptyList()
    private val listings = HashMap<String, ProjectListing>()
    private val unavailableFolders = HashSet<String>()
    private val modelErrors = HashMap<String, String>()

    private var refreshJob: Job? = null
    private var busyJob: Job? = null
    private var openJob: Job? = null
    private var syncJob: Job? = null
    private var cacheCleanupJob: Job? = null
    private var openGeneration = 0L

    init {
        val previousExit = ProcessExitDiagnostics.readPreviousExit(application)
        _uiState.update { it.copy(lastAbnormalExit = previousExit?.summary, lastAbnormalExitDetails = previousExit?.details) }
        previousExit?.let { diagnostic ->
            viewModelScope.launch(Dispatchers.IO) {
                ProcessExitDiagnostics.archivePreviousExitAndMarkHandled(application, diagnostic)
            }
        }
        recordProcessState("home:init")
        refresh()
    }

    private fun recordProcessState(state: String) = ProcessExitDiagnostics.recordState(getApplication(), state)

    // ── Loading ──────────────────────────────────────────────────────────────────────────────────

    fun refresh() {
        if (_uiState.value.locked || refreshJob?.isActive == true) return
        _uiState.update { it.copy(loading = true, loadingStage = "Lokaler Speicher wird vorbereitet…", error = null) }
        refreshJob = viewModelScope.launch {
            try {
                val recoveredCache = withContext(Dispatchers.IO) {
                    val imports = File(getApplication<Application>().cacheDir, "project-imports")
                    val removed = imports.listFiles()?.count { it.deleteRecursively() } ?: 0
                    packages.clearOpenCache()
                    preparer.clearWorkFiles()
                    removed
                }
                _uiState.update { it.copy(loadingStage = "Unterbrochene Importe werden geprüft…") }
                val recovered = packages.recoverIncompleteImports() + recoveredCache
                _uiState.update { it.copy(loadingStage = "Projekte werden gelesen…") }
                installed = packages.listInstalled()
                folderProjects = folders.list()
                // Opening the app looks at every linked folder once: a folder that was deleted or
                // moved is reported right away, not when a model fails to open.
                _uiState.update { it.copy(loadingStage = "Verknüpfte Ordner werden geprüft…") }
                listings.clear()
                unavailableFolders.clear()
                folderProjects.forEach { project ->
                    val listing = folders.listing(project.id)
                    listings[project.id] = listing
                    folders.prune(project.id, listing)
                    project.folders.filterNot { folders.isAvailable(it) }.forEach { unavailableFolders += it.id }
                }
                val checks = workshop.load()
                try {
                    val quality = qualityStore.load(checks.values, installed)
                    val logo = qualityStore.reportLogo()
                    _uiState.update { it.copy(quality = quality, qualityError = null, reportLogo = logo?.path,
                        reportLogoStamp = logo?.lastModified() ?: 0L) }
                } catch (error: Exception) {
                    _uiState.update { it.copy(qualityError = error.message) }
                }
                publishProjects()
                val missing = folderProjects.flatMap { project ->
                    project.folders.filter { it.id in unavailableFolders }.map { "«${it.label}» (${project.name})" }
                }
                _uiState.update {
                    it.copy(
                        checks = checks,
                        status = when {
                            missing.isNotEmpty() -> "Nicht gefunden: ${missing.joinToString()}. Die Ordner wurden " +
                                "gelöscht oder verschoben, oder ihr Speicher ist nicht verbunden."
                            recovered > 0 -> "$recovered unterbrochene Vorgänge wurden bereinigt."
                            else -> ""
                        },
                    )
                }
                // The project on screen may have been deleted or may need its tree again.
                (_uiState.value.screen as? AppScreen.Project)?.let { showProject(it.projectId, it.path) }
                recordProcessState("home:ready")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordProcessState("home:error:${error::class.java.simpleName}")
                _uiState.update {
                    it.copy(error = "Die Projekte konnten nicht geladen werden: ${error.message ?: error::class.java.simpleName}")
                }
            } finally {
                _uiState.update { it.copy(loading = false) }
                refreshJob = null
            }
        }
    }

    private suspend fun publishProjects() {
        val cards = withContext(Dispatchers.IO) {
            val fromFolders = folderProjects.map { project ->
                val listing = listings[project.id] ?: ProjectListing()
                ProjectCardState(
                    id = project.id,
                    name = _uiState.value.quality.orders[project.id]?.projectName ?: project.name,
                    source = ProjectSource.FOLDER,
                    modelCount = listing.entries.size,
                    readyCount = listing.entries.count { folders.stateOf(project.id, it) == ModelState.READY },
                    location = when (project.folders.size) {
                        0 -> "Noch kein Ordner verknüpft"
                        1 -> project.folders.first().label
                        else -> "${project.folders.size} Ordner: ${project.folders.joinToString { it.label }}"
                    },
                    lastSync = listing.scannedAt.values.maxOrNull()?.let(::formatInstant),
                    missingFolders = project.folders.filter { it.id in unavailableFolders }.map(LinkedFolder::label),
                )
            }
            val fromFiles = installed.groupBy { it.project.projectId }.map { (projectId, revisions) ->
                ProjectCardState(
                    id = projectId,
                    name = _uiState.value.quality.orders[projectId]?.projectName ?: revisions.first().project.name,
                    source = ProjectSource.QSPROJECT,
                    modelCount = revisions.size,
                    readyCount = revisions.size,
                    location = ".qsproject-Datei",
                    lastSync = revisions.maxOfOrNull { it.createdAt }?.let(::formatInstant),
                )
            }
            (fromFolders + fromFiles).sortedWith(compareBy(NaturalOrder) { it.name })
        }
        val storage = StorageStats(
            installedBytes = packages.storageStats().installedBytes + folders.storageBytes(),
            availableBytes = getApplication<Application>().allocatableStorageBytes(),
            totalBytes = getApplication<Application>().filesDir.totalSpace,
            packageCount = cards.sumOf { it.modelCount },
        )
        _uiState.update { it.copy(projects = cards, storage = storage) }
    }

    // ── Navigation ───────────────────────────────────────────────────────────────────────────────

    fun openProject(projectId: String) {
        if (_uiState.value.locked) return
        recordProcessState("project:open")
        viewModelScope.launch {
            showProject(projectId, emptyList())
            if (folderProjects.any { it.id == projectId }) syncProject(projectId, silent = true)
        }
    }

    fun navigateTo(path: List<String>) {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        if (_uiState.value.opening != null || _uiState.value.openedPackage != null) return
        val resolved = _uiState.value.tree?.resolve(path)?.path ?: emptyList()
        _uiState.update { it.copy(screen = screen.copy(path = resolved), search = "") }
    }

    fun openInfo(page: InfoPage) {
        val state = _uiState.value
        if (state.opening != null || state.openedPackage != null || state.screen is AppScreen.Project) return
        _uiState.update { it.copy(screen = AppScreen.Info(page)) }
    }

    /** One level up; returns false when the operator is already on the home screen. */
    fun navigateUp(): Boolean {
        val state = _uiState.value
        (state.screen as? AppScreen.Info)?.let { info ->
            _uiState.update {
                // The help is mostly opened from the home screen, so it returns there.
                it.copy(screen = if (info.page == InfoPage.ABOUT || info.page == InfoPage.HELP) AppScreen.Home
                    else AppScreen.Info(InfoPage.ABOUT))
            }
            return true
        }
        val screen = state.screen as? AppScreen.Project ?: return false
        if (state.opening != null) {
            cancelOpening()
            return true
        }
        if (state.search.isNotEmpty()) {
            _uiState.update { it.copy(search = "") }
        } else if (screen.path.isNotEmpty()) {
            _uiState.update { it.copy(screen = screen.copy(path = screen.path.dropLast(1))) }
        } else {
            goHome()
        }
        return true
    }

    fun goHome() {
        if (_uiState.value.opening != null || _uiState.value.openedPackage != null) return
        syncJob?.cancel()
        _uiState.update {
            it.copy(
                screen = AppScreen.Home, tree = null, treeNotice = null, syncing = false, search = "",
                linkedFolders = emptyList(),
            )
        }
        viewModelScope.launch { publishProjects() }
        recordProcessState("home:ready")
    }

    fun setSearch(value: String) = _uiState.update { it.copy(search = value) }

    fun dismissMessage() = _uiState.update { it.copy(error = null, status = "") }

    fun dismissExitNotice() = _uiState.update { it.copy(lastAbnormalExit = null, lastAbnormalExitDetails = null) }

    private suspend fun showProject(projectId: String, path: List<String>) {
        val tree = buildTree(projectId)
        if (tree == null) {
            _uiState.update {
                it.copy(
                    screen = AppScreen.Home, tree = null, treeNotice = null, syncing = false, search = "",
                    linkedFolders = emptyList(),
                )
            }
            return
        }
        val project = folderProjects.firstOrNull { it.id == projectId }
        val listing = listings[projectId] ?: ProjectListing()
        _uiState.update {
            it.copy(
                screen = AppScreen.Project(projectId, tree.resolve(path).path),
                tree = tree,
                linkedFolders = project?.folders.orEmpty().map { folder ->
                    LinkedFolderState(
                        id = folder.id,
                        label = folder.label,
                        available = folder.id !in unavailableFolders,
                        modelCount = listing.entries.count { entry -> entry.folderId == folder.id },
                        scannedAt = listing.scannedAt[folder.id]?.let(::formatInstant),
                    )
                },
            )
        }
    }

    private suspend fun buildTree(projectId: String): BrowserFolder? = withContext(Dispatchers.IO) {
        val folderProject = folderProjects.firstOrNull { it.id == projectId }
        if (folderProject != null) {
            val listing = listings[projectId] ?: ProjectListing()
            return@withContext ProjectTree.fromLinkedFolders(folderProject.name, folderProject.folders, listing.entries) { entry ->
                val error = modelErrors[errorKey(projectId, entry.key)]
                BrowserModel(
                    key = entry.key,
                    title = entry.title,
                    detail = listOfNotNull(
                        entry.format.label,
                        entry.sizeBytes.takeIf { it >= 0 }?.let(::formatBytes),
                        entry.lastModified.takeIf { it > 0 }?.let { formatInstant(Instant.ofEpochMilli(it).toString()) },
                    ).joinToString(" · "),
                    format = entry.format,
                    state = if (error != null) ModelState.ERROR else folders.stateOf(projectId, entry),
                    origin = ModelOrigin.FolderFile(entry),
                    error = error,
                )
            }
        }
        val revisions = installed.filter { it.project.projectId == projectId }
        if (revisions.isEmpty()) return@withContext null
        ProjectTree.fromInstalled(revisions.first().project.name, revisions) { summary ->
            BrowserModel(
                key = summary.packageId,
                title = summary.assembly.positionNumber.removeSuffix(".1").takeIf { summary.project.projectId == DEMO_PROJECT_ID }
                    ?: "${summary.assembly.positionNumber} · Rev. ${summary.assembly.revision}",
                detail = listOfNotNull(
                    "${summary.partCount} Bauteile",
                    formatBytes(summary.archiveBytes),
                ).joinToString(" · "),
                format = ModelFormat.QSXR,
                state = ModelState.READY,
                origin = ModelOrigin.InstalledRevision(summary),
            )
        }
    }

    private suspend fun refreshTree() {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        showProject(screen.projectId, screen.path)
    }

    // ── Projects and their linked folders ────────────────────────────────────────────────────────

    /** A project is created empty; folders are linked to it from inside. */
    fun createProject(name: String) {
        if (_uiState.value.locked) return
        viewModelScope.launch {
            runCatching { folders.create(name) }
                .onSuccess { project ->
                    folderProjects = folders.list()
                    listings[project.id] = ProjectListing()
                    publishProjects()
                    showProject(project.id, emptyList())
                    _uiState.update {
                        it.copy(status = "Projekt «${project.name}» angelegt. Jetzt einen Ordner mit GLB- oder IFC-Dateien verknüpfen.")
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(error = "Das Projekt konnte nicht angelegt werden: ${error.message}") } }
        }
    }

    /** Links a system folder to the project on screen.  Only the reference is stored. */
    fun linkFolder(treeUri: Uri) {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        if (_uiState.value.locked) return
        startBusy("Ordner wird verknüpft", "Suche nach GLB- und IFC-Dateien…") {
            val project = folders.linkFolder(screen.projectId, treeUri)
            folderProjects = folders.list()
            val folder = project.folders.last()
            val scan = folders.scan(folder) { folderCount, files -> updateBusy("$files Modelle in $folderCount Ordnern…") }
            storeScan(project.id, folder, scan.entries)
            _uiState.update {
                it.copy(
                    status = folderScanSummary(folder.label, scan.entries, scan.truncated),
                )
            }
            publishProjects()
            refreshTree()
        }
    }

    /** The folder moved or its permission was lost: keep the link, point it at a new pick. */
    fun relinkFolder(folderId: String, treeUri: Uri) {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        if (_uiState.value.locked) return
        startBusy("Ordner wird neu verknüpft", "Suche nach GLB- und IFC-Dateien…") {
            val project = folders.relinkFolder(screen.projectId, folderId, treeUri) ?: error("Das Projekt existiert nicht mehr.")
            folderProjects = folders.list()
            val folder = project.folders.first { it.id == folderId }
            val scan = folders.scan(folder) { folderCount, files -> updateBusy("$files Modelle in $folderCount Ordnern…") }
            unavailableFolders -= folderId
            storeScan(project.id, folder, scan.entries)
            _uiState.update { it.copy(treeNotice = null, status = folderScanSummary(folder.label, scan.entries, scan.truncated, relinked = true)) }
            publishProjects()
            refreshTree()
        }
    }

    /** Removes the link and what was derived from that folder.  The folder itself is untouched. */
    fun unlinkFolder(folderId: String) {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        if (_uiState.value.locked) return
        viewModelScope.launch {
            val label = folderProjects.firstOrNull { it.id == screen.projectId }?.folders?.firstOrNull { it.id == folderId }?.label
            folders.unlinkFolder(screen.projectId, folderId) ?: return@launch
            folderProjects = folders.list()
            unavailableFolders -= folderId
            val listing = folders.listing(screen.projectId)
            listings[screen.projectId] = listing
            folders.prune(screen.projectId, listing)
            _uiState.update {
                it.copy(treeNotice = null, status = "Verknüpfung mit «${label.orEmpty()}» entfernt. Der Ordner selbst wurde nicht verändert.")
            }
            publishProjects()
            refreshTree()
        }
    }

    private suspend fun storeScan(projectId: String, folder: LinkedFolder, entries: List<SourceEntry>) {
        val current = listings[projectId] ?: folders.listing(projectId)
        val updated = current.copy(
            entries = current.entries.filterNot { it.folderId == folder.id } + entries,
            scannedAt = current.scannedAt + (folder.id to Instant.now().toString()),
        )
        listings[projectId] = updated
        folders.saveListing(projectId, updated)
        folders.prune(projectId, updated)
    }

    fun syncCurrentProject() {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        syncProject(screen.projectId, silent = false)
    }

    /**
     * Looks into every linked folder of the project.  A folder that is gone keeps its last
     * listing, so what was already prepared from it stays usable, and is reported by name.
     */
    private fun syncProject(projectId: String, silent: Boolean) {
        val project = folderProjects.firstOrNull { it.id == projectId } ?: return
        if (syncJob?.isActive == true) return
        _uiState.update { it.copy(syncing = true) }
        syncJob = viewModelScope.launch {
            try {
                val before = (listings[projectId] ?: ProjectListing()).entries.mapTo(HashSet(), SourceEntry::key)
                val missing = ArrayList<String>()
                project.folders.forEach { folder ->
                    try {
                        storeScan(projectId, folder, folders.scan(folder).entries)
                        unavailableFolders -= folder.id
                    } catch (error: FolderUnavailableException) {
                        unavailableFolders += folder.id
                        missing += folder.label
                    }
                }
                val after = (listings[projectId] ?: ProjectListing()).entries.mapTo(HashSet(), SourceEntry::key)
                val added = (after - before).size
                val removed = (before - after).size
                _uiState.update {
                    it.copy(
                        treeNotice = missing.takeIf { names -> names.isNotEmpty() }?.let { names ->
                            "Nicht gefunden: ${names.joinToString { name -> "«$name»" }}. Die Ordner wurden gelöscht oder " +
                                "verschoben, oder ihr Speicher ist nicht verbunden. Unter «Ordner verwalten» lassen " +
                                "sie sich neu verknüpfen oder aus dem Projekt entfernen."
                        },
                        status = when {
                            added > 0 || removed > 0 -> "Ordner synchronisiert: $added neu, $removed entfernt."
                            silent -> it.status
                            else -> "Die verknüpften Ordner sind unverändert."
                        },
                    )
                }
                if ((_uiState.value.screen as? AppScreen.Project)?.projectId == projectId) refreshTree()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!silent) _uiState.update { it.copy(error = "Die Ordner konnten nicht synchronisiert werden: ${error.message}") }
            } finally {
                _uiState.update { it.copy(syncing = false) }
                syncJob = null
            }
        }
    }

    fun renameProject(projectId: String, name: String) {
        val details = orderDetails(projectId).copy(projectName = name)
        saveOrderDetails(details)
    }

    fun deleteProject(projectId: String) {
        if (_uiState.value.locked) return
        viewModelScope.launch {
            val folderProject = folderProjects.firstOrNull { it.id == projectId }
            val keys = if (folderProject != null) {
                val keys = (listings.remove(projectId) ?: ProjectListing()).entries.map { revisionKey(projectId, it.key) }
                folders.delete(projectId)
                folderProjects = folders.list()
                modelErrors.keys.removeAll { it.startsWith("$projectId/") }
                keys
            } else {
                val revisions = installed.filter { it.project.projectId == projectId }
                packages.deleteProject(projectId)
                installed = packages.listInstalled()
                revisions.map { it.revisionKey() }
            }
            val checks = workshop.clear(keys)
            _uiState.update {
                it.copy(
                    checks = checks,
                    screen = AppScreen.Home,
                    tree = null,
                    treeNotice = null,
                    search = "",
                    linkedFolders = emptyList(),
                    status = "Projekt aus der App entfernt. Ordner und Dateien wurden nicht verändert.",
                )
            }
            publishProjects()
        }
    }

    fun prepareAllInCurrentProject() {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        val project = folderProjects.firstOrNull { it.id == screen.projectId } ?: return
        if (_uiState.value.locked) return
        startBusy("Modelle werden vorbereitet", "Prüfe, was noch fehlt…", wakeLock = true) {
            val pending = withContext(Dispatchers.IO) {
                (listings[project.id] ?: ProjectListing()).entries.filter { folders.stateOf(project.id, it) != ModelState.READY }
            }
            var failed = 0
            pending.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                updateBusy("${entry.name} (${index + 1}/${pending.size})", index.toFloat() / pending.size)
                try {
                    preparer.prepare(project, entry)
                    modelErrors.remove(errorKey(project.id, entry.key))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failed += 1
                    modelErrors[errorKey(project.id, entry.key)] = error.message ?: error::class.java.simpleName
                }
            }
            _uiState.update {
                it.copy(
                    status = if (pending.isEmpty()) {
                        "Alle Modelle waren bereits vorbereitet."
                    } else {
                        "${pending.size - failed} Modelle vorbereitet" + if (failed > 0) " · $failed mit Fehler." else "."
                    },
                )
            }
            refreshTree()
        }
    }

    fun forgetPreparedModel(model: BrowserModel) {
        val screen = _uiState.value.screen as? AppScreen.Project ?: return
        val entry = (model.origin as? ModelOrigin.FolderFile)?.entry ?: return
        if (_uiState.value.locked) return
        viewModelScope.launch {
            folders.deletePrepared(screen.projectId, entry)
            modelErrors.remove(errorKey(screen.projectId, entry.key))
            refreshTree()
        }
    }

    // ── `.qsproject` files ───────────────────────────────────────────────────────────────────────

    fun importProjectFile(uri: Uri) {
        if (_uiState.value.locked) return
        recordProcessState("import:start")
        startBusy(".qsproject wird importiert", "Lokale Kopie wird vorbereitet…", wakeLock = true) {
            var temporary: File? = null
            try {
                temporary = copyProjectFileToCache(uri)
                val result = packages.importProject(temporary) { progress ->
                    updateBusy(
                        stage = if (progress.total > 1) {
                            "${progress.stage} (${progress.completed}/${progress.total})"
                        } else {
                            progress.stage
                        },
                        progress = 0.18f + progress.fraction.coerceIn(0f, 1f) * 0.82f,
                        canCancel = progress.fraction < 0.96f,
                    )
                }
                val checks = workshop.clear(result.packages.map { it.revisionKey() })
                installed = packages.listInstalled()
                _uiState.update { it.copy(checks = checks, status = importResultText(result)) }
                publishProjects()
                showProject(result.project.projectId, emptyList())
                recordProcessState("import:complete")
            } finally {
                temporary?.delete()
            }
        }
    }

    fun deleteRevision(model: BrowserModel) {
        val summary = (model.origin as? ModelOrigin.InstalledRevision)?.summary ?: return
        if (_uiState.value.locked) return
        viewModelScope.launch {
            val deleted = packages.deletePackage(summary.packageId)
            val checks = if (deleted) workshop.clear(listOf(summary.revisionKey())) else workshop.load()
            installed = packages.listInstalled()
            _uiState.update {
                it.copy(
                    checks = checks,
                    status = "Revision gelöscht: ${summary.assembly.positionNumber} · Rev. ${summary.assembly.revision}.",
                )
            }
            publishProjects()
            refreshTree()
        }
    }

    private suspend fun copyProjectFileToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val folder = File(context.cacheDir, "project-imports").apply { mkdirs() }
        val file = File(folder, "${UUID.randomUUID()}.qsproject")
        val declaredLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0L }
        val availableBytes = context.allocatableStorageBytes()
        val estimatedBytes = declaredLength?.let { length ->
            (length * 2L).coerceAtMost(Long.MAX_VALUE - IMPORT_SPACE_MARGIN) + IMPORT_SPACE_MARGIN
        } ?: IMPORT_SPACE_MARGIN
        require(estimatedBytes <= availableBytes) {
            "Zu wenig Speicherplatz: geschätzt ${formatBytes(estimatedBytes)} benötigt, ${formatBytes(availableBytes)} verfügbar."
        }
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Die gewählte Datei konnte nicht geöffnet werden." }
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var nextReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAXIMUM_PROJECT_BYTES) { "Das Projekt ist größer als 8 GB." }
                        output.write(buffer, 0, count)
                        if (copied >= nextReport) {
                            updateBusy(
                                stage = "Projekt wird kopiert · ${formatBytes(copied)}" +
                                    declaredLength?.let { " von ${formatBytes(it)}" }.orEmpty(),
                                progress = declaredLength?.let { (copied.toFloat() / it).coerceIn(0f, 1f) * 0.18f },
                            )
                            nextReport = copied + COPY_PROGRESS_INTERVAL
                        }
                    }
                }
            }
            updateBusy("Kopie abgeschlossen · Inhalt wird geprüft", 0.18f)
            file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    // ── Opening and closing a model ──────────────────────────────────────────────────────────────

    fun openModel(model: BrowserModel, forcePrepare: Boolean = false) {
        val state = _uiState.value
        val screen = state.screen as? AppScreen.Project ?: return
        if (state.locked) return
        recordProcessState("viewer:loading:${model.title}")
        cacheCleanupJob?.cancel()
        val generation = ++openGeneration
        _uiState.update {
            it.copy(opening = OpeningState(model.title, "Wird geöffnet…"), error = null, status = "")
        }
        openJob = viewModelScope.launch {
            try {
                val opened = when (val origin = model.origin) {
                    is ModelOrigin.FolderFile -> openFolderModel(screen.projectId, origin.entry, forcePrepare, generation)
                    is ModelOrigin.InstalledRevision -> openInstalledRevision(origin.summary)
                }
                if (generation != openGeneration) return@launch
                _uiState.update { it.copy(opening = OpeningState(model.title, "QS-Akte wird zugeordnet…")) }
                var qualityKey: String? = null
                try {
                    val snapshot = qualitySnapshot(getApplication(), opened.packageData, model.key,
                        "${opened.subtitle} › ${model.title}")
                    val document = qualityStore.register(snapshot, qualitySourceVersion(model))
                    qualityKey = snapshot.key
                    _uiState.update { it.copy(quality = document, qualityError = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _uiState.update { it.copy(qualityError = error.message ?: "Die QS-Akte konnte nicht geöffnet werden.") }
                }
                if (generation != openGeneration) return@launch
                _uiState.update {
                    it.copy(
                        opening = null,
                        openedQualityKey = qualityKey,
                        openedPackage = opened.packageData,
                        openedSummary = opened.summary,
                        openedRevisions = opened.revisions,
                        openedSubtitle = opened.subtitle,
                    )
                }
                recordProcessState("viewer:open:${model.title}")
            } catch (_: CancellationException) {
                // cancelOpening already restored a consistent state.
            } catch (error: Throwable) {
                if (generation != openGeneration) return@launch
                recordProcessState("viewer:open-error:${error::class.java.simpleName}")
                val message = error.message ?: error::class.java.simpleName
                if (model.origin is ModelOrigin.FolderFile) {
                    modelErrors[errorKey(screen.projectId, model.key)] = message
                }
                _uiState.update { it.copy(opening = null, error = "«${model.title}» konnte nicht geöffnet werden: $message") }
                refreshTree()
            } finally {
                if (generation == openGeneration) openJob = null
            }
        }
    }

    private class Opened(
        val packageData: InstalledPackage,
        val summary: InstalledPackageSummary,
        val revisions: List<InstalledPackage>,
        val subtitle: String,
    )

    private suspend fun openFolderModel(
        projectId: String,
        entry: SourceEntry,
        force: Boolean,
        generation: Long,
    ): Opened {
        val project = folderProjects.firstOrNull { it.id == projectId } ?: error("Das Projekt existiert nicht mehr.")
        val prepared = preparer.prepare(project, entry, force) { stage ->
            _uiState.update { state ->
                if (generation != openGeneration || state.opening == null) state else {
                    state.copy(opening = state.opening.copy(stage = stage))
                }
            }
        }
        modelErrors.remove(errorKey(projectId, entry.key))
        val manifest = prepared.manifest
        return Opened(
            packageData = prepared,
            summary = InstalledPackageSummary(
                packageId = manifest.packageId,
                createdAt = manifest.createdAt,
                producer = manifest.producer,
                project = manifest.project,
                assembly = manifest.assembly,
                partCount = prepared.parts.parts.size,
                archiveBytes = entry.sizeBytes.coerceAtLeast(0L),
            ),
            revisions = emptyList(),
            subtitle = (listOf(_uiState.value.quality.orders[projectId]?.projectName ?: project.name) + entry.path).joinToString(" › "),
        )
    }

    private suspend fun openInstalledRevision(summary: InstalledPackageSummary): Opened {
        val details = revisionCandidates(summary, installed).map { packages.loadPackage(it) }
        return Opened(
            packageData = details.first { it.manifest.packageId == summary.packageId },
            summary = summary,
            revisions = details,
            subtitle = "${_uiState.value.quality.orders[summary.project.projectId]?.projectName ?: summary.project.name} · Rev. ${summary.assembly.revision}",
        )
    }

    fun cancelOpening() {
        if (_uiState.value.opening == null) return
        openGeneration += 1
        openJob?.cancel()
        openJob = null
        _uiState.update { it.copy(opening = null, status = "Öffnen abgebrochen.") }
        recordProcessState("project:ready")
    }

    /** Called by the viewer once it has released its renderer; the project folder is still there. */
    fun closeModel() {
        val state = _uiState.value
        recordProcessState("viewer:closing:${state.openedSummary?.assembly?.positionNumber ?: "unknown"}")
        openGeneration += 1
        openJob?.cancel()
        openJob = null
        val cachedIds = state.openedRevisions.map { it.manifest.packageId }
        _uiState.update {
            it.copy(
                opening = null,
                openedPackage = null,
                openedSummary = null,
                openedRevisions = emptyList(),
                openedSubtitle = null,
                openedQualityKey = null,
            )
        }
        cacheCleanupJob?.cancel()
        cacheCleanupJob = viewModelScope.launch {
            refreshTree()
            if (cachedIds.isNotEmpty()) {
                delay(VIEWER_CACHE_RELEASE_DELAY_MILLIS)
                packages.clearOpenCache(cachedIds)
            }
            recordProcessState("project:ready")
        }
    }

    // Quality windows overlay the viewer, so reading a record never discards the AR anchor.
    fun orderDetails(projectId: String): OrderDetails = _uiState.value.quality.orders[projectId]
        ?: OrderDetails(projectId, _uiState.value.projects.firstOrNull { it.id == projectId }?.name
            ?: _uiState.value.quality.records.firstOrNull { it.piece.projectId == projectId }?.piece?.projectName
            ?: "Projekt")

    fun openOrderDetails(projectId: String) {
        if (_uiState.value.qualityBusy) return
        _uiState.update { it.copy(orderProjectId = projectId, qualityMessage = null, qualityError = null) }
    }

    fun closeOrderDetails() {
        if (!_uiState.value.qualityBusy) _uiState.update { it.copy(orderProjectId = null) }
    }

    fun saveOrderDetails(details: OrderDetails) = qualityOperation {
        val document = qualityStore.saveOrder(details)
        _uiState.update { it.copy(quality = document, orderProjectId = null, qualityMessage = "Auftragsdaten gespeichert.") }
        publishProjects()
    }

    fun openProjectQuality(projectId: String) {
        if (_uiState.value.locked) return
        viewModelScope.launch {
            showProject(projectId, emptyList())
            openQualityProject()
        }
    }

    fun openQualityProject() {
        val project = _uiState.value.currentProject ?: return
        _uiState.update { it.copy(qualityProjectId = project.id, qualityKey = null, qualityMessage = null) }
    }

    fun openQualityArchive() {
        _uiState.update { it.copy(qualitySettings = false, qualityProjectId = "*", qualityKey = null, qualityMessage = null) }
    }

    fun openQualityPiece() {
        _uiState.update { it.copy(qualityKey = it.openedQualityKey, qualityProjectId = null,
            qualityMessage = null, qualitySettings = it.openedQualityKey == null) }
    }

    fun selectQualityPiece(key: String) {
        _uiState.update { it.copy(qualityKey = key, qualityMessage = null) }
    }

    fun closeQuality() {
        if (_uiState.value.qualityBusy) return
        _uiState.update {
            if (it.qualityKey != null) it.copy(qualityKey = null, qualityMessage = null)
            else it.copy(qualityProjectId = null, qualitySettings = false, qualityMessage = null)
        }
    }

    fun openQualitySettings() { _uiState.update { it.copy(qualitySettings = true, qualityMessage = null) } }
    fun closeQualitySettings() {
        if (!_uiState.value.qualityBusy) _uiState.update { it.copy(qualitySettings = false) }
    }

    fun saveQualityInspector(name: String) = qualityOperation {
        val document = qualityStore.saveInspector(name)
        _uiState.update { it.copy(quality = document, qualitySettings = false, qualityMessage = "Prüfername gespeichert.") }
    }

    fun saveQualityCheck(status: QualityStatus?, inspector: String, comment: String, action: QualityAction?) {
        val key = _uiState.value.qualityKey ?: return
        qualityOperation {
            val document = qualityStore.addCheck(key, status, inspector, comment, action)
            _uiState.update { it.copy(quality = document, qualityMessage = "Eintrag mit Datum und Uhrzeit gespeichert.") }
        }
    }

    suspend fun saveQualityPhoto(key: String, inspector: String, photo: SavedArPhoto, alignment: String) {
        val document = qualityStore.addPhoto(key, inspector, photo.capturedAt, photo.displayName, alignment) {
            getApplication<Application>().contentResolver.openInputStream(photo.uri)
                ?: error("Das AR-Foto konnte nicht gelesen werden.")
        }
        _uiState.update { it.copy(quality = document) }
    }

    fun qualityPhotoFile(photo: QualityPhoto): File = qualityStore.photoFile(photo)

    fun exportQualityPdf(key: String, uri: Uri) {
        val record = _uiState.value.quality.record(key) ?: return
        val projectName = orderDetails(record.piece.projectId).projectName
        qualityOperation {
            val (document, report) = qualityStore.issueReport(key, projectName) { snapshot, output ->
                QualityPdf.write(snapshot, qualityStore::photoFile, output, qualityStore.reportLogo())
            }
            _uiState.update { it.copy(quality = document) }
            try {
                copyReport(report, uri)
            } catch (error: Exception) {
                throw IllegalStateException("Das PDF ist unter Berichte archiviert, konnte aber nicht an das gewählte Ziel " +
                    "kopiert werden: ${error.message}", error)
            }
            _uiState.update { it.copy(qualityMessage = "Prüfprotokoll exportiert und als Stand ${report.edition} archiviert.") }
        }
    }

    /**
     * Several positions at once: every position gets its own archived edition, exactly as a single
     * export would, then either one collected PDF ([combined]) or one PDF per position goes to the
     * destination (a file, or a folder chosen by the operator).
     */
    fun exportQualityReports(keys: Collection<String>, destination: Uri, combined: Boolean) {
        val document = _uiState.value.quality
        val records = qualityBatchOrder(keys.mapNotNull(document::record)) { orderDetails(it.piece.projectId).projectName }
        if (records.isEmpty()) return
        qualityOperation {
            val issued = mutableListOf<IssuedQualityReport>()
            try {
                records.forEachIndexed { index, record ->
                    _uiState.update { it.copy(qualityProgress = "Prüfbericht ${index + 1} von ${records.size} · ${record.piece.position}") }
                    val (next, report) = qualityStore.issueReport(record.piece.key, orderDetails(record.piece.projectId).projectName) { snapshot, output ->
                        QualityPdf.write(snapshot, qualityStore::photoFile, output, qualityStore.reportLogo())
                    }
                    _uiState.update { it.copy(quality = next) }
                    issued += report
                }
                val resolver = getApplication<Application>().contentResolver
                if (combined) {
                    _uiState.update { it.copy(qualityProgress = "Sammelbericht mit ${issued.size} Positionen wird erstellt…") }
                    val collection = QualityReportCollection(Instant.now().toString(), ZoneId.systemDefault().id,
                        _uiState.value.quality.inspector, issued)
                    val staging = File(getApplication<Application>().cacheDir, "qs-sammelbericht-${UUID.randomUUID()}.pdf")
                    try {
                        withContext(Dispatchers.IO) {
                            staging.outputStream().use { QualityPdf.write(collection, qualityStore::photoFile, it, qualityStore.reportLogo()) }
                            resolver.openOutputStream(destination, "wt")?.use { output -> staging.inputStream().use { it.copyTo(output) } }
                                ?: error("Die PDF-Datei konnte nicht geschrieben werden.")
                        }
                    } finally { staging.delete() }
                } else {
                    withContext(Dispatchers.IO) {
                        val folder = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination))
                        issued.forEachIndexed { index, report ->
                            _uiState.update { it.copy(qualityProgress = "PDF ${index + 1} von ${issued.size} wird gespeichert…") }
                            val file = qualityStore.verifyReportFile(report)
                            val target = DocumentsContract.createDocument(resolver, folder, "application/pdf", qualityReportFileName(report))
                                ?: error("Im gewählten Ordner kann keine Datei angelegt werden.")
                            resolver.openOutputStream(target, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                                ?: error("Die PDF-Datei konnte nicht geschrieben werden.")
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val archived = if (issued.isEmpty()) "" else " ${issued.size} von ${records.size} Berichten sind unter «Berichte» der Positionen archiviert."
                throw IllegalStateException("Der Export wurde nicht abgeschlossen: ${error.message}.$archived", error)
            }
            _uiState.update {
                it.copy(qualityMessage = if (combined) "${issued.size} Prüfberichte archiviert und als ein Sammelbericht gespeichert."
                    else "${issued.size} Prüfberichte archiviert und einzeln im gewählten Ordner gespeichert.")
            }
        }
    }

    /**
     * Company logo for the reports, from an image the operator picks.  Stored privately as a PNG of
     * at most [REPORT_LOGO_PIXELS] on its long side (transparency kept); used by reports issued from now on.
     */
    fun importReportLogo(uri: Uri) = qualityOperation {
        val resolver = getApplication<Application>().contentResolver
        val logo = withContext(Dispatchers.IO) {
            // Measuring only: decodeStream returns null here by design, the size is in the options.
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = resolver.openInputStream(uri) ?: error("Das Bild konnte nicht geöffnet werden.")
            stream.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Die Datei ist kein lesbares Bild (PNG, JPEG oder WebP)." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= REPORT_LOGO_PIXELS) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Das Bild konnte nicht gelesen werden.")
            val scale = minOf(1f, REPORT_LOGO_PIXELS.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) decoded.scale((decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1)).also { if (it !== decoded) decoded.recycle() } else decoded
            try {
                qualityStore.saveReportLogo { output ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "Das Logo konnte nicht gespeichert werden." }
                }
            } finally { bitmap.recycle() }
        }
        _uiState.update { it.copy(reportLogo = logo.path, reportLogoStamp = logo.lastModified(),
            qualityMessage = "Logo gespeichert. Es erscheint in allen neu erstellten Berichten.") }
    }

    fun removeReportLogo() = qualityOperation {
        qualityStore.removeReportLogo()
        _uiState.update { it.copy(reportLogo = null, reportLogoStamp = 0L,
            qualityMessage = "Logo entfernt. Bereits archivierte Berichte bleiben unverändert.") }
    }

    fun exportArchivedQualityPdf(id: String, uri: Uri) {
        val report = _uiState.value.quality.reports.firstOrNull { it.id == id } ?: return
        qualityOperation {
            copyReport(report, uri)
            _uiState.update { it.copy(qualityMessage = "Archivierter Bericht unverändert gespeichert.") }
        }
    }

    /** A new edition of [key], staged for the share sheet (mail, messenger…) instead of a chosen file. */
    fun shareQualityReport(key: String) {
        val record = _uiState.value.quality.record(key) ?: return
        val projectName = orderDetails(record.piece.projectId).projectName
        qualityOperation {
            val (document, report) = qualityStore.issueReport(key, projectName) { snapshot, output ->
                QualityPdf.write(snapshot, qualityStore::photoFile, output, qualityStore.reportLogo())
            }
            _uiState.update { it.copy(quality = document) }
            val staged = stageForShare(report)
            _uiState.update { it.copy(qualityShare = staged, qualityMessage = "Stand ${report.edition} archiviert und zum Teilen bereit.") }
        }
    }

    fun shareArchivedQualityReport(id: String) {
        val report = _uiState.value.quality.reports.firstOrNull { it.id == id } ?: return
        qualityOperation {
            val staged = stageForShare(report)
            _uiState.update { it.copy(qualityShare = staged) }
        }
    }

    /** The staged PDF was handed to the share sheet, or could not be: then [error] says why. */
    fun qualityShareConsumed(error: String? = null) {
        _uiState.update { it.copy(qualityShare = null, qualityError = error ?: it.qualityError) }
    }

    private suspend fun stageForShare(report: IssuedQualityReport): File = withContext(Dispatchers.IO) {
        val file = qualityStore.verifyReportFile(report)
        val folder = File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        File(folder, qualityReportFileName(report)).also { file.copyTo(it, overwrite = true) }
    }

    /**
     * Every archived edition of every position in scope (one project, or all with [projectId] "*")
     * copied unchanged into the chosen folder, one subfolder per project in the archive case.  No
     * new editions are issued: this is the operator's backup of what exists.
     */
    fun exportAllQualityReports(projectId: String, destination: Uri) {
        val document = _uiState.value.quality
        val reports = document.reports.filter { projectId == "*" || it.record.piece.projectId == projectId }
        if (reports.isEmpty()) return
        val records = qualityBatchOrder(reports.map { it.record }.distinctBy { it.piece.key }) { orderDetails(it.piece.projectId).projectName }
        val ordered = records.flatMap { record -> reports.filter { it.record.piece.key == record.piece.key }.sortedBy { it.edition } }
        qualityOperation {
            val resolver = getApplication<Application>().contentResolver
            var written = 0
            try {
                withContext(Dispatchers.IO) {
                    val root = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination))
                    val folders = HashMap<String, Uri>()
                    ordered.forEachIndexed { index, report ->
                        _uiState.update { it.copy(qualityProgress = "PDF ${index + 1} von ${ordered.size} · ${report.record.piece.position}") }
                        val file = qualityStore.verifyReportFile(report)
                        val folder = if (projectId == "*") {
                            val name = orderDetails(report.record.piece.projectId).projectName.ifBlank { report.record.piece.projectName }
                                .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(60)
                            folders.getOrPut(name) {
                                DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, name)
                                    ?: error("Im gewählten Ordner kann kein Unterordner angelegt werden.")
                            }
                        } else root
                        val target = DocumentsContract.createDocument(resolver, folder, "application/pdf", qualityReportFileName(report))
                            ?: error("Im gewählten Ordner kann keine Datei angelegt werden.")
                        resolver.openOutputStream(target, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                            ?: error("Die PDF-Datei konnte nicht geschrieben werden.")
                        written += 1
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw IllegalStateException("Die Sicherung wurde nach $written von ${ordered.size} Dateien abgebrochen: ${error.message}", error)
            }
            _uiState.update {
                it.copy(qualityMessage = "$written archivierte Prüfberichte (${records.size} Positionen) im gewählten Ordner gesichert.")
            }
        }
    }

    private suspend fun copyReport(report: IssuedQualityReport, uri: Uri) {
        val file = qualityStore.verifyReportFile(report)
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("Die PDF-Datei konnte nicht geschrieben werden.")
        }
    }

    fun openModelFromQuality(model: BrowserModel) {
        _uiState.update { it.copy(qualityProjectId = null, qualityKey = null) }
        openModel(model)
    }

    private fun qualityOperation(block: suspend () -> Unit) {
        if (_uiState.value.qualityBusy) return
        _uiState.update { it.copy(qualityBusy = true, qualityMessage = null, qualityError = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) {
                _uiState.update { it.copy(qualityError = "Für diesen Bericht ist zu wenig Arbeitsspeicher frei. Bitte andere Apps schließen und erneut versuchen.") }
            }
            catch (error: Exception) {
                _uiState.update { it.copy(qualityError = error.message ?: "Der QS-Vorgang konnte nicht gespeichert werden.") }
            } finally { _uiState.update { it.copy(qualityBusy = false, qualityProgress = null) } }
        }
    }

    // ── Long operations ──────────────────────────────────────────────────────────────────────────

    private fun startBusy(
        title: String,
        stage: String,
        wakeLock: Boolean = false,
        block: suspend () -> Unit,
    ) {
        _uiState.update { it.copy(busy = BusyState(title, stage), error = null, status = "") }
        busyJob = viewModelScope.launch {
            val lock = if (!wakeLock) null else runCatching {
                getApplication<Application>().getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QSTallerXR:long-operation")
                    .apply {
                        setReferenceCounted(false)
                        acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                    }
            }.getOrNull()
            try {
                block()
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(status = "$title: abgebrochen. Es wurde nichts halb geändert.") }
                withContext(kotlinx.coroutines.NonCancellable) { refreshTree() }
            } catch (error: Throwable) {
                recordProcessState("busy:error:${error::class.java.simpleName}")
                _uiState.update { it.copy(error = error.message ?: error::class.java.simpleName) }
            } finally {
                if (lock?.isHeld == true) lock.release()
                _uiState.update { it.copy(busy = null) }
                busyJob = null
            }
        }
    }

    private fun updateBusy(stage: String, progress: Float? = null, canCancel: Boolean = true) {
        _uiState.update { state ->
            state.busy?.let { state.copy(busy = it.copy(stage = stage, progress = progress, canCancel = canCancel)) }
                ?: state
        }
    }

    fun cancelBusy() {
        val busy = _uiState.value.busy ?: return
        if (!busy.canCancel) return
        _uiState.update { it.copy(busy = busy.copy(stage = "Wird sicher abgebrochen…", canCancel = false)) }
        busyJob?.cancel(CancellationException("Vom Benutzer abgebrochen."))
    }

    private companion object {
        const val MAXIMUM_PROJECT_BYTES = 8L * 1024L * 1024L * 1024L
        const val COPY_PROGRESS_INTERVAL = 4L * 1024L * 1024L
        const val IMPORT_SPACE_MARGIN = 64L * 1024L * 1024L
        const val WAKE_LOCK_TIMEOUT_MILLIS = 2L * 60L * 60L * 1_000L
        const val VIEWER_CACHE_RELEASE_DELAY_MILLIS = 1_500L
    }
}

internal fun revisionKey(projectId: String, modelKey: String): String = "$projectId:$modelKey:1"

/** Project of the models bundled with the app (build_bundled_demo_samples.py). */
private const val DEMO_PROJECT_ID = "d6655dc6-fc60-5b13-a966-99af0c630ce0"

/** Long side of the stored report logo: sharp at its printed size of about 4–6 cm. */
private const val REPORT_LOGO_PIXELS = 1000

private fun errorKey(projectId: String, modelKey: String): String = "$projectId/$modelKey"

private fun importResultText(result: ProjectImportResult): String {
    val details = buildList {
        result.cases[ImportCase.NEW_PROJECT]?.takeIf { it > 0 }?.let { add("neues Projekt: $it Baugruppen") }
        result.cases[ImportCase.NEW_REVISION]?.takeIf { it > 0 }?.let { add("neue Revisionen: $it") }
        result.cases[ImportCase.OVERWRITTEN]?.takeIf { it > 0 }?.let { add("überschriebene Revisionen: $it") }
        result.skipped.takeIf { it.isNotEmpty() }?.let { add("beim Export übersprungen: ${it.size}") }
    }
    return "${result.project.name}: ${details.joinToString(" · ")}"
}

internal fun revisionCandidates(
    selected: InstalledPackageSummary,
    installed: List<InstalledPackageSummary>,
): List<InstalledPackageSummary> {
    val sameGuid = installed.filter {
        it.project.projectId == selected.project.projectId && it.assembly.guid == selected.assembly.guid
    }
    val candidates = if (sameGuid.size > 1) {
        sameGuid
    } else {
        installed.filter {
            it.project.projectId == selected.project.projectId &&
                it.assembly.positionNumber == selected.assembly.positionNumber
        }
    }
    return (candidates + selected).distinctBy { it.packageId }.sortedByDescending { it.assembly.revision }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.0f KB".format(bytes / 1024.0)
}

private val instantFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

internal fun formatInstant(value: String): String =
    runCatching { instantFormatter.format(Instant.parse(value)) }.getOrDefault(value)
