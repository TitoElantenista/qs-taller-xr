package com.qs.tallerxr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Root of the app.  Three layers, all driven by one state: the home screen with the projects, the
 * browser of one project, and the viewer of one model on top of it.  Exactly one of them is
 * composed at a time, and the viewer is only removed after it reports that its renderer is gone.
 */
@Composable
fun QsApp() {
    val viewModel: AppViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val opened = uiState.openedPackage
    val openedSummary = uiState.openedSummary
    val openedQualityKey = uiState.openedQualityKey

    // The viewer owns Back while it exists, so it can release Filament before it closes.  Below
    // it, Back walks up the folders of the project and only then returns to the home screen.
    BackHandler(enabled = opened == null && (uiState.opening != null || uiState.screen != AppScreen.Home)) {
        viewModel.navigateUp()
    }

    Box(Modifier.fillMaxSize()) {
        if (opened != null && openedSummary != null) {
            key(openedSummary.packageId) {
                PackageViewer(
                    packageData = opened,
                    availableRevisions = uiState.openedRevisions,
                    qualityRecord = uiState.quality.record(uiState.openedQualityKey),
                    inspectorName = uiState.quality.inspector,
                    onOpenQuality = viewModel::openQualityPiece,
                    onOpenQualitySettings = viewModel::openQualitySettings,
                    onSaveQualityPhoto = { photo, alignment, inspector ->
                        val qualityKey = openedQualityKey ?: error("Die QS-Akte ist nicht verfügbar.")
                        viewModel.saveQualityPhoto(qualityKey, inspector, photo, alignment)
                    },
                    onBack = viewModel::closeModel,
                    active = true,
                    subtitle = uiState.openedSubtitle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            when (uiState.screen) {
                AppScreen.Home -> HomeScreen(
                    uiState = uiState,
                    onRefresh = viewModel::refresh,
                    onOpenProject = viewModel::openProject,
                    onCreateProject = viewModel::createProject,
                    onImportProjectFile = viewModel::importProjectFile,
                    onRenameProject = viewModel::renameProject,
                    onDeleteProject = viewModel::deleteProject,
                    onCancelBusy = viewModel::cancelBusy,
                    onDismissMessage = viewModel::dismissMessage,
                    onDismissExitNotice = viewModel::dismissExitNotice,
                    onOpenInfo = viewModel::openInfo,
                    onOpenSettings = viewModel::openQualitySettings,
                    onOpenProjectQuality = viewModel::openProjectQuality,
                    onOpenOrder = viewModel::openOrderDetails,
                )
                is AppScreen.Info -> InfoScreen(
                    page = (uiState.screen as AppScreen.Info).page,
                    onOpen = viewModel::openInfo,
                    onUp = { viewModel.navigateUp() },
                )
                is AppScreen.Project -> ProjectScreen(
                    uiState = uiState,
                    onUp = { viewModel.navigateUp() },
                    onHome = viewModel::goHome,
                    onNavigate = viewModel::navigateTo,
                    onSearch = viewModel::setSearch,
                    onOpenModel = { model -> viewModel.openModel(model) },
                    onPrepareAgain = { model -> viewModel.openModel(model, forcePrepare = true) },
                    onForgetPrepared = viewModel::forgetPreparedModel,
                    onDeleteRevision = viewModel::deleteRevision,
                    onSync = viewModel::syncCurrentProject,
                    onOpenQuality = viewModel::openQualityProject,
                    onOpenOrder = { uiState.currentProject?.id?.let(viewModel::openOrderDetails) },
                    onLinkFolder = viewModel::linkFolder,
                    onRelinkFolder = viewModel::relinkFolder,
                    onUnlinkFolder = viewModel::unlinkFolder,
                    onPrepareAll = viewModel::prepareAllInCurrentProject,
                    onRenameProject = viewModel::renameProject,
                    onDeleteProject = viewModel::deleteProject,
                    onCancelBusy = viewModel::cancelBusy,
                    onDismissMessage = viewModel::dismissMessage,
                    onDismissExitNotice = viewModel::dismissExitNotice,
                )
            }
            uiState.opening?.let { opening ->
                OpeningOverlay(opening = opening, onCancel = viewModel::cancelOpening)
            }
        }
        QualityWindows(uiState, viewModel)
        // Once per installation, on the home screen: what a project is and how a check runs.
        val context = LocalContext.current
        val guide = remember(context) { GuidePreferences(context) }
        var firstRun by rememberSaveable { mutableStateOf(!guide.seen(GuidePreferences.FIRST_RUN)) }
        if (firstRun && opened == null && uiState.screen == AppScreen.Home && !uiState.loading) {
            FirstRunGuide(onFinish = {
                guide.markSeen(GuidePreferences.FIRST_RUN)
                firstRun = false
            })
        }
    }
}

/** Covers the project while a model is being prepared, so nothing else can be started meanwhile. */
@Composable
private fun OpeningOverlay(opening: OpeningState, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xCC071019))
            // Swallows touches: the list underneath must not react while a model is opening.
            .clickable(enabled = true, onClick = {}, indication = null, interactionSource = null),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(24.dp).widthIn(max = 420.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Text(opening.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(opening.stage, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Beim ersten Öffnen wird das Bauteil auf dem Telefon vorbereitet. Danach öffnet es sofort.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Abbrechen") }
            }
        }
    }
}
