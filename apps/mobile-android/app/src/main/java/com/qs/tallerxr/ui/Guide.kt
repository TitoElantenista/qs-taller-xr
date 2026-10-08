package com.qs.tallerxr.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import com.qs.tallerxr.projects.ModelFormat
import com.qs.tallerxr.projects.SourceEntry
import kotlinx.coroutines.launch

/**
 * Help for operators who install the app from the store and meet it for the first time: a short
 * guide on first start (and again from «So funktioniert's»), worked examples in the empty states,
 * one-time hints at the first visit of a screen and a summary of what a linked folder contains.
 */

/** What the operator has already seen; per installation, never synchronised anywhere. */
internal class GuidePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    fun seen(key: String): Boolean = preferences.getBoolean(key, false)
    fun markSeen(key: String) = preferences.edit { putBoolean(key, true) }

    companion object {
        const val FIRST_RUN = "first-run"
        const val HINT_PROJECT = "hint-project"
        const val HINT_VIEWER = "hint-viewer"
        const val HINT_QUALITY = "hint-quality"
    }
}

private class GuideStep(val icon: ImageVector, val title: String, val text: String, val example: (@Composable () -> Unit)? = null)

private val GUIDE_STEPS = listOf(
    GuideStep(Icons.Default.ViewInAr, "Bauteile prüfen in 3D und AR",
        "QS Taller XR zeigt die CAD-Baugruppe als 3D-Modell, legt sie in AR über das reale Bauteil und " +
            "dokumentiert die Prüfung mit Status, Fotos und PDF-Bericht. Alles bleibt auf dem Telefon.") { WorkflowStrip() },
    GuideStep(Icons.Default.Folder, "Ein Projekt besteht aus Ordnern",
        "Legen Sie ein Projekt an und verknüpfen Sie einen Ordner auf dem Telefon, der Speicherkarte oder einem " +
            "USB-Stick. Die Dateien werden nicht kopiert: Änderungen im Ordner übernimmt der Abgleich (⟳). " +
            "Unterordner werden zu Ebenen des Projekts, bis zu zwölf tief.") { FolderTreeExample() },
    GuideStep(Icons.AutoMirrored.Filled.InsertDriveFile, "Eine Datei je Baugruppe",
        "Unterstützt werden IFC und GLB sowie .qsproject-Dateien aus dem PC-Viewer. Exportieren Sie jede " +
            "Baugruppe als eigene Datei – zum Beispiel in Tekla Structures die Baugruppe auswählen und als IFC " +
            "exportieren –, nicht das ganze Gebäude. Prüfakte, Bericht und AR-Ausrichtung gehören immer zu einer " +
            "Baugruppe. IFC-Dateien werden beim ersten Öffnen vorbereitet; das dauert einen Moment.") { FormatList() },
    GuideStep(Icons.AutoMirrored.Filled.Assignment, "Ausrichten, prüfen, dokumentieren",
        "So läuft eine Prüfung ab:") { CheckSteps() },
)

/** First-start guide: four pages to swipe, can be skipped; «So funktioniert's» shows it again. */
@Composable
internal fun FirstRunGuide(onFinish: () -> Unit) {
    val pager = rememberPagerState { GUIDE_STEPS.size }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onFinish, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("So funktioniert's", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onFinish) { Text("Überspringen") }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        GuideStepContent(GUIDE_STEPS[page])
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(GUIDE_STEPS.size) { index ->
                            Box(Modifier.size(if (index == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                                .background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                    val last = pager.currentPage == GUIDE_STEPS.lastIndex
                    Button(onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Text(if (last) "Los geht's" else "Weiter")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideStepContent(step: GuideStep) {
    Icon(step.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
    Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(step.text, style = MaterialTheme.typography.bodyMedium)
    step.example?.invoke()
}

/** The same guide as a page under «Über die App», followed by short answers to common questions. */
@Composable
internal fun HelpPage(modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GUIDE_STEPS.forEachIndexed { index, step ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            GuideStepContent(step)
        }
        Spacer(Modifier.height(8.dp))
        Text("Häufige Fragen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HELP_ANSWERS.forEach { (question, answer) ->
            Text(question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(answer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val HELP_ANSWERS = listOf(
    "Welche Dateien kann ich verwenden?" to "IFC (.ifc) und GLB (.glb) aus verknüpften Ordnern sowie .qsproject-Dateien " +
        "aus dem PC-Viewer. IFC-Dateien bis 256 MB, Modelle bis drei Millionen Dreiecke. Andere Dateien im Ordner " +
        "werden ignoriert.",
    "Warum eine Datei je Baugruppe?" to "Die Prüfakte, der PDF-Bericht und die AR-Ausrichtung beziehen sich auf genau " +
        "eine Baugruppe. Ein ganzes Gebäude ist für das Telefon zu groß und ließe sich am Bauteil nicht ausrichten.",
    "Wie gliedere ich ein Projekt?" to "Mit Unterordnern im verknüpften Ordner, zum Beispiel Los › Bauteilgruppe › " +
        "Position. Jede Ebene erscheint in der App als Ordner; bis zu zwölf Ebenen werden gelesen.",
    "Was passiert, wenn sich eine Datei ändert?" to "Der Abgleich (⟳) übernimmt neue, geänderte und gelöschte Dateien. " +
        "Eine geänderte Geometrie bekommt eine eigene QS-Akte; frühere Prüfungen bleiben im Archiv erhalten.",
    "Wie wähle ich die Punkte für AR?" to "Im 3D-Modell über «AR 0/6» zwei bis sechs Punkte antippen: gelbe " +
        "Punkte sind Ecken, Ringe sind Bohrungsmitten. Einen Punkt 0,5 s halten und zu einer Ecke ziehen setzt " +
        "einen neuen Punkt mit Linie, zu einem anderen Punkt ziehen verbindet beide; Antippen entfernt einen Punkt. " +
        "Am besten drei weit auseinanderliegende Punkte, die nicht auf einer Linie und nicht alle auf derselben " +
        "Fläche liegen. Die Bauteil-Info ist währenddessen ausgeschaltet.",
    "Was brauche ich für AR?" to "Ein Gerät mit Google Play-Diensten für AR (ARCore), gutes Licht und ein Bauteil mit " +
        "erkennbaren Ecken. 3D-Ansicht und Qualitätssicherung funktionieren auch ohne AR.",
    "Wo liegen meine Daten?" to "Nur auf diesem Telefon. Die verknüpften Ordner werden nicht verändert. Beim " +
        "Deinstallieren gehen Prüfakten und Fotos verloren – Prüfberichte vorher als PDF sichern.",
    "Wie kommt mein Firmenlogo in den Bericht?" to "Einstellungen (Zahnrad auf der Startseite oder in der " +
        "Qualitätssicherung) → «Logo für Prüfberichte». Es gilt für alle danach erstellten Berichte.",
)

/** 3D → AR → QS as three small tiles. */
@Composable
private fun WorkflowStrip() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Icons.Default.TouchApp to "3D-Modell", Icons.Default.ViewInAr to "AR am Bauteil",
            Icons.AutoMirrored.Filled.Assignment to "Prüfbericht").forEachIndexed { index, (icon, label) ->
            if (index > 0) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** A worked example of a linked folder: what becomes the project, its levels and its models. */
@Composable
internal fun FolderTreeExample(modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Beispiel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TreeLine(0, Icons.Default.FolderOpen, "Halle Nord", "verknüpfter Ordner")
            TreeLine(1, Icons.Default.Folder, "Los 1", "Ebene 1")
            TreeLine(2, Icons.Default.Folder, "Stützen", "Ebene 2")
            TreeLine(3, Icons.AutoMirrored.Filled.InsertDriveFile, "100201.2.ifc", "eine Baugruppe")
            TreeLine(3, Icons.AutoMirrored.Filled.InsertDriveFile, "100202.1.ifc", null)
            TreeLine(2, Icons.Default.Folder, "Träger", null)
            TreeLine(1, Icons.Default.Folder, "Los 2", null)
        }
    }
}

@Composable
private fun TreeLine(level: Int, icon: ImageVector, name: String, note: String?) {
    Row(Modifier.padding(start = (level * 18).dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp),
            tint = if (icon == Icons.AutoMirrored.Filled.InsertDriveFile) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.primary)
        Text(" $name", style = MaterialTheme.typography.bodySmall,
            fontFamily = if (icon == Icons.AutoMirrored.Filled.InsertDriveFile) FontFamily.Monospace else FontFamily.Default)
        if (note != null) Text("  ← $note", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormatList() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ".ifc" to "IFC aus Tekla, Advance Steel, Revit … mit Positionsnummern, Profilen und Werkstoffen",
                ".glb" to "3D-Modell ohne Bauteildaten, z. B. aus anderen CAD-Programmen exportiert",
                ".qsproject" to "fertiges Projekt aus dem PC-Viewer, mit Revisionen",
            ).forEach { (format, text) ->
                Row {
                    Text(format, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(92.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CheckSteps() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Icons.Default.TouchApp to "Modell öffnen und über «AR 0/6» zwei bis sechs gut erkennbare Ecken oder " +
                "Bohrungsmitten antippen (Punkte und Ringe im Modell).",
            Icons.Default.ViewInAr to "«AR» öffnen, das Bauteil aufnehmen und dieselben Punkte im Standbild markieren. " +
                "Eine zweite Blickrichtung macht die Ausrichtung genauer.",
            Icons.Default.CameraAlt to "Verankern und mit «AR-Foto» Belege aufnehmen.",
            Icons.AutoMirrored.Filled.Assignment to "Unter Qualitätssicherung Status und Kommentar erfassen und den " +
                "Prüfbericht als PDF exportieren – einzeln oder mehrere Positionen gemeinsam.",
        ).forEachIndexed { index, (icon, text) ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp, top = 2.dp).size(20.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** A tip shown until the operator closes it, once per installation. */
@Composable
internal fun OneTimeHint(key: String, title: String, text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context) { GuidePreferences(context) }
    var visible by remember(key) { mutableStateOf(!preferences.seen(key)) }
    if (!visible) return
    Card(modifier.fillMaxWidth().widthIn(max = 640.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.padding(top = 8.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            IconButton(onClick = { preferences.markSeen(key); visible = false }) {
                Icon(Icons.Default.Close, contentDescription = "Tipp ausblenden", tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

/** IFC files this large usually hold a whole building or several assemblies. */
internal const val LARGE_IFC_BYTES = 50L * 1024L * 1024L

/**
 * What a freshly linked folder holds, in the words the operator needs: models by format, in how
 * many folders and levels, and files that are probably more than a single Baugruppe.
 */
internal fun folderScanSummary(label: String, entries: List<SourceEntry>, truncated: Boolean, relinked: Boolean = false): String =
    buildString {
        append("«$label» ").append(if (relinked) "neu verknüpft: " else "verknüpft: ")
        if (entries.isEmpty()) {
            append("keine Modelle gefunden. Gelesen werden .ifc- und .glb-Dateien, auch in Unterordnern – " +
                "je Baugruppe eine Datei.")
        } else {
            val counts = ModelFormat.entries.mapNotNull { format ->
                entries.count { it.format == format }.takeIf { it > 0 }?.let { "$it ${format.label}" }
            }
            append(if (entries.size == 1) "1 Modell" else "${entries.size} Modelle")
            append(" (").append(counts.joinToString(", ")).append(")")
            val folders = entries.map { it.path }.distinct()
            val depth = entries.maxOf { it.path.size }
            when {
                depth == 0 -> append(" direkt im Ordner.")
                else -> append(" in ${if (folders.size == 1) "1 Ordner" else "${folders.size} Ordnern"}, " +
                    "${if (depth == 1) "1 Ebene" else "bis $depth Ebenen"} tief.")
            }
            append(" Die Dateien bleiben, wo sie sind.")
            val large = entries.filter { it.format == ModelFormat.IFC && it.sizeBytes > LARGE_IFC_BYTES }
            if (large.isNotEmpty()) {
                append(" ${if (large.size == 1) "Eine IFC-Datei ist" else "${large.size} IFC-Dateien sind"} größer als " +
                    "${LARGE_IFC_BYTES / (1024 * 1024)} MB («${large.first().name}»): Enthält sie mehr als eine " +
                    "Baugruppe? Für die Prüfung je Baugruppe eine Datei exportieren.")
            }
        }
        if (truncated) append(" Der Ordner ist sehr groß und wurde nicht vollständig gelesen.")
    }
