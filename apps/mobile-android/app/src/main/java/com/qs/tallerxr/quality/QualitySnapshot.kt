package com.qs.tallerxr.quality

import android.content.Context
import androidx.core.net.toUri
import com.qs.tallerxr.data.InstalledPackage
import com.qs.tallerxr.data.JsonCodec
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

suspend fun qualitySnapshot(context: Context, model: InstalledPackage, sourceKey: String, sourceLabel: String): QualityPiece =
    withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val modelInput = model.modelUri?.let { context.contentResolver.openInputStream(it.toUri()) }
            ?: model.modelFile.inputStream()
        modelInput.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.update(JsonCodec.value.encodeToString(model.parts).toByteArray(Charsets.UTF_8))
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        val manifest = model.manifest
        QualityPiece(
            key = qualityPieceKey(model.revisionKey(), fingerprint), revisionKey = model.revisionKey(),
            sourceKey = sourceKey, projectId = manifest.project.projectId, projectName = manifest.project.name,
            position = manifest.assembly.positionNumber, assemblyGuid = manifest.assembly.guid,
            revision = manifest.assembly.revision, fingerprint = fingerprint, sourceLabel = sourceLabel,
            modelCreatedAt = manifest.createdAt,
            parts = model.parts.parts.map { part ->
                QualityPart(part.positionNumber, part.guid, when (part.kind) {
                    "BEAM" -> "Träger"
                    "MAIN_PART" -> "Hauptbauteil"
                    "PLATE" -> "Blech"
                    "BOLT_GROUP" -> "Schraubengruppe"
                    "FABRICATION_MARK" -> "Fertigungsmarkierung"
                    else -> part.kind
                }, (0..2).joinToString(" × ") { axis ->
                    String.format(Locale.GERMAN, "%.1f", (part.boundsAssemblyM.max[axis] - part.boundsAssemblyM.min[axis]) * 1000)
                } + " mm", flattenQualityAttributes(part.properties))
            },
        )
    }

private fun flattenQualityAttributes(properties: JsonObject): Map<String, String> {
    val result = linkedMapOf<String, String>()
    fun visit(prefix: String, value: JsonElement) {
        if (value is JsonObject) value.entries.sortedBy { it.key }.forEach { (key, child) ->
            visit(if (prefix.isBlank()) key else "$prefix · $key", child)
        } else {
            val text = if (value is JsonPrimitive) value.content else value.toString()
            if (text.isNotBlank() && text != "null") result[prefix] = text
        }
    }
    visit("", properties)
    return result
}
