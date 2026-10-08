package com.qs.tallerxr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qs.tallerxr.quality.OrderDetails
import com.qs.tallerxr.quality.qualityDate

@Composable
internal fun OrderDetailsScreen(initial: OrderDetails, busy: Boolean, error: String?,
    onClose: () -> Unit, onSave: (OrderDetails) -> Unit) {
    var name by rememberSaveable(initial.projectId) { mutableStateOf(initial.projectName) }
    var orderNumber by rememberSaveable(initial.projectId) { mutableStateOf(initial.orderNumber) }
    var projectNumber by rememberSaveable(initial.projectId) { mutableStateOf(initial.projectNumber) }
    var client by rememberSaveable(initial.projectId) { mutableStateOf(initial.client) }
    var projectResponsible by rememberSaveable(initial.projectId) { mutableStateOf(initial.projectResponsible) }
    var qualityResponsible by rememberSaveable(initial.projectId) { mutableStateOf(initial.qualityResponsible) }
    var site by rememberSaveable(initial.projectId) { mutableStateOf(initial.site) }
    var location by rememberSaveable(initial.projectId) { mutableStateOf(initial.inspectionLocation) }
    var notes by rememberSaveable(initial.projectId) { mutableStateOf(initial.notes) }
    QualityWindow("Auftragsdaten", initial.projectName, onClose) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).widthIn(max = 740.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Diese Daten werden für die Prüfberichte aller Positionen dieses Projekts verwendet.",
                    style = MaterialTheme.typography.bodyMedium)
                OrderField("Projektname · Pflichtfeld", name, { name = it }, busy)
                OrderField("Auftragsnummer", orderNumber, { orderNumber = it }, busy)
                OrderField("Interne Projektnummer · optional", projectNumber, { projectNumber = it }, busy)
                OrderField("Auftraggeber / Kunde", client, { client = it }, busy)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Verantwortung & Prüfort", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OrderField("Projektverantwortliche Person", projectResponsible, { projectResponsible = it }, busy)
                OrderField("QS-verantwortliche Person", qualityResponsible, { qualityResponsible = it }, busy)
                Text("Wer die einzelne Prüfung durchführt, wird weiterhin beim jeweiligen Prüfvermerk erfasst.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OrderField("Baustelle / Standort", site, { site = it }, busy)
                OrderField("Prüfort", location, { location = it }, busy)
                OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text("Bemerkungen zum Auftrag") },
                    minLines = 3, maxLines = 8, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${notes.length}/4000 · optional") })
                Text("Bereits erstellte Berichte und frühere Prüfvermerke behalten ihre damaligen Angaben.",
                    style = MaterialTheme.typography.bodySmall)
                if (initial.updatedAt.isNotBlank()) Text("Zuletzt geändert: ${qualityDate(initial.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Surface(shadowElevation = 4.dp) {
                Row(Modifier.widthIn(max = 740.dp).fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    OutlinedButton(onClick = onClose, enabled = !busy) { Text("Abbrechen") }
                    Button(enabled = !busy && name.isNotBlank(), onClick = {
                        onSave(initial.copy(projectName = name, orderNumber = orderNumber, projectNumber = projectNumber,
                            client = client, projectResponsible = projectResponsible, qualityResponsible = qualityResponsible,
                            site = site, inspectionLocation = location, notes = notes))
                    }) { Text(if (busy) "Speichert…" else "Speichern") }
                }
            }
        }
    }
}

@Composable
private fun OrderField(label: String, value: String, onChange: (String) -> Unit, busy: Boolean) {
    OutlinedTextField(value, { onChange(it.take(200)) }, label = { Text(label) }, singleLine = true,
        enabled = !busy, modifier = Modifier.fillMaxWidth())
}
