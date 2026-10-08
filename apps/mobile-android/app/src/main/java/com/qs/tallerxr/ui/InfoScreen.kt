package com.qs.tallerxr.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qs.tallerxr.BuildConfig
import com.qs.tallerxr.R
import com.qs.tallerxr.diagnostics.ArchivedExitDiagnostic
import com.qs.tallerxr.diagnostics.ProcessExitDiagnostics
import com.qs.tallerxr.legal.LegalBlock
import com.qs.tallerxr.legal.LegalInfo
import com.qs.tallerxr.legal.LegalText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class InfoPage(val title: String) {
    ABOUT("Über die App"),
    HELP("So funktioniert's"),
    PRIVACY("Datenschutzerklärung"),
    IMPRINT("Impressum"),
    LICENSES("Open-Source-Lizenzen"),
    DIAGNOSTICS("Diagnose"),
}

/** Where the source code is published; shown as text in «Über die App», not as a link. */
internal const val SOURCE_REPOSITORY = "TitoElantenista/qs-taller-xr"

/** Wording required by the ARCore Additional Terms wherever an app discloses its use of ARCore. */
internal const val ARCORE_NOTICE_EN =
    "This application runs on Google Play Services for AR (ARCore), which is provided by Google LLC " +
        "and governed by the Google Privacy Policy."
internal const val ARCORE_NOTICE_DE =
    "Diese App verwendet Google Play-Dienste für AR (ARCore), bereitgestellt von Google LLC. Es gilt " +
        "die Datenschutzerklärung von Google."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoScreen(page: InfoPage, onOpen: (InfoPage) -> Unit, onUp: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                title = { Text(page.title, fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        when (page) {
            InfoPage.ABOUT -> AboutPage(onOpen, Modifier.fillMaxSize().padding(padding))
            InfoPage.HELP -> HelpPage(Modifier.padding(padding))
            InfoPage.PRIVACY -> LegalPage(listOf("legal/datenschutz.txt"), emptyList(), Modifier.padding(padding))
            InfoPage.IMPRINT -> LegalPage(listOf("legal/impressum.txt"), emptyList(), Modifier.padding(padding))
            InfoPage.LICENSES -> LegalPage(
                assets = listOf("legal/lizenzen.txt"),
                verbatim = listOf(
                    "Apache License 2.0" to "legal/apache-2.0.txt",
                    "Mozilla Public License 2.0" to "legal/mpl-2.0.txt",
                    "MIT – Fuel und Result" to "legal/mit-fuel-result.txt",
                    "Native Drittanbieterhinweise" to "legal/native-notices.txt",
                ),
                modifier = Modifier.padding(padding),
            )
            InfoPage.DIAGNOSTICS -> DiagnosticsPage(Modifier.padding(padding))
        }
    }
}

@Composable
private fun AboutPage(onOpen: (InfoPage) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val info = remember { LegalInfo.fromBuild() }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF0B1E2A)),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text("QS Taller XR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (info.name.isNotBlank()) {
                        Text(info.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Text(
                "Bauteile und Baugruppen in 3D prüfen und in AR über das reale Bauteil legen – ausgerichtet " +
                    "über Punkte, die Sie am Modell wählen und am Bauteil markieren. Projekte entstehen aus " +
                    "einem Ordner mit GLB- oder IFC-Dateien oder aus einer .qsproject-Datei. Projekte und " +
                    "Prüfakten werden lokal verarbeitet. Prüfstatus, Kommentare, Prüfer und Zeitpunkt bleiben " +
                    "im Verlauf nachvollziehbar. AR-Fotos und Auftragsdaten ergänzen die PDF-Prüfprotokolle. " +
                    "Hinweise zu den AR-Diensten finden Sie im Datenschutz.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ARCORE_NOTICE_DE, style = MaterialTheme.typography.bodySmall)
                    Text(
                        ARCORE_NOTICE_EN,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinkRow("Datenschutzerklärung von Google") { context.openInBrowser("https://policies.google.com/privacy") }
                    if (BuildConfig.HUAWEI_AR_ENABLED) {
                        Text("Huawei AR Engine steht auf kompatiblen Geräten als experimentelle Alternative zur Verfügung. " +
                            "Die Punktausrichtung erfolgt dort im Livebild; die Standbild-Ausrichtung ist derzeit " +
                            "nur mit Google ARCore verfügbar.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            // Plain text on purpose, no link: the app has no network access of its own.
            SelectionContainer {
                Text(
                    "Quellcode auf GitHub: $SOURCE_REPOSITORY",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(listOf(InfoPage.HELP, InfoPage.PRIVACY, InfoPage.IMPRINT, InfoPage.LICENSES)) { target ->
            Card(onClick = { onOpen(target) }, modifier = Modifier.fillMaxWidth()) { LinkRow(target.title, onClick = null) }
        }
        if (info.privacyUrl.startsWith("https://")) {
            item {
                LinkRow("Datenschutzerklärung im Browser öffnen") { context.openInBrowser(info.privacyUrl) }
            }
        }
        item {
            Card(onClick = { onOpen(InfoPage.DIAGNOSTICS) }, modifier = Modifier.fillMaxWidth()) {
                LinkRow(InfoPage.DIAGNOSTICS.title, onClick = null)
            }
        }
        if (info.email.isNotBlank()) {
            item {
                Card(
                    onClick = {
                        context.openExternally(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${info.email}"))
                                .putExtra(Intent.EXTRA_SUBJECT, "QS Taller XR ${BuildConfig.VERSION_NAME}"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { LinkRow("Kontakt: ${info.email}", onClick = null) }
            }
        }
        item {
            Text(
                "Die AR-Überlagerung ist ein Hilfsmittel zur visuellen Kontrolle, keine Messung und kein " +
                    "automatisches Abnahmekriterium.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsPage(modifier: Modifier) {
    val context = LocalContext.current
    val records by produceState<List<ArchivedExitDiagnostic>?>(null, context) {
        value = withContext(Dispatchers.IO) { ProcessExitDiagnostics.readArchivedExits(context) }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Diese Einträge betreffen frühere App-Sitzungen. Sie werden nur auf diesem Gerät gespeichert. " +
                "Eine unveränderte Zeit oder ID bezeichnet denselben Eintrag, keinen neuen Vorfall. " +
                "Die technische Zustandsfolge beschreibt die zuletzt gemeldeten Schritte, nicht die bewiesene Ursache.",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (records == null) item { Text("Einträge werden geladen…") }
        else if (records.orEmpty().isEmpty()) item { Text("Keine gespeicherten Systembeendigungen vorhanden.") }
        items(records.orEmpty(), key = { it.id }) { record ->
            var expanded by remember(record) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.title, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Details ausblenden" else "Technische Details anzeigen")
                    }
                    if (expanded) SelectionContainer {
                        Text(record.details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
    if (onClick == null) content() else Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
}

/** The app holds no INTERNET permission: links and mail are handed to whatever app handles them. */
private fun Context.openExternally(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser or mail app on a locked down workshop phone: nothing to open.
    }
}

/**
 * A web page in the browser, never in another app that claims the address: the Google Docs app
 * would ask for an account before showing a published, public document.
 */
private fun Context.openInBrowser(url: String) {
    val page = Uri.parse(url)
    val inBrowser = Intent(Intent.ACTION_VIEW, page).addCategory(Intent.CATEGORY_BROWSABLE)
        .apply { selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) }
    try {
        startActivity(inBrowser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openExternally(Intent(Intent.ACTION_VIEW, page))
    }
}

private sealed interface LegalRow {
    data class Block(val block: LegalBlock) : LegalRow
    data class Verbatim(val text: String) : LegalRow
}

@Composable
private fun LegalPage(assets: List<String>, verbatim: List<Pair<String, String>>, modifier: Modifier) {
    val context = LocalContext.current
    val rows by produceState<List<LegalRow>>(emptyList(), assets, verbatim) {
        value = withContext(Dispatchers.IO) {
            val info = LegalInfo.fromBuild()
            fun read(path: String) = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            buildList {
                if (!BuildConfig.HUAWEI_AR_ENABLED && "legal/datenschutz.txt" in assets) {
                    add(LegalRow.Block(LegalBlock.Paragraph(
                        "Diese Ausgabe verwendet für AR ausschließlich Google ARCore. " +
                            "Die Angaben zu Huawei AR Engine in dieser Erklärung betreffen die separat " +
                            "bereitgestellte experimentelle Debug-Ausgabe.",
                    )))
                }
                assets.forEach { path -> LegalText.parse(LegalText.fill(read(path), info)).forEach { add(LegalRow.Block(it)) } }
                verbatim.forEach { (title, path) ->
                    add(LegalRow.Block(LegalBlock.Heading(title)))
                    // Paragraph sized pieces keep a fifteen page licence scrolling smoothly.
                    read(path).split(Regex("\\n\\s*\\n")).filter(String::isNotBlank).forEach { add(LegalRow.Verbatim(it)) }
                }
            }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows) { row ->
            when (row) {
                is LegalRow.Verbatim -> Text(
                    row.text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is LegalRow.Block -> when (val block = row.block) {
                    is LegalBlock.Title -> Text(
                        block.text,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    is LegalBlock.Heading -> Text(
                        block.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    is LegalBlock.Paragraph -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (LegalText.MISSING in block.text) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                    is LegalBlock.Bullet -> Row {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium)
                        Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
