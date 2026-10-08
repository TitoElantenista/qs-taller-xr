package com.qs.tallerxr.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class WorkshopStateRepository(context: Context) {
    private val stateFile = AtomicFile(File(context.filesDir, "qsxr/workshop_state.json"))
    private val mutex = Mutex()

    suspend fun load(): Map<String, AssemblyCheckRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { readDocument().checks.associateBy { it.revisionKey } }
    }

    suspend fun markChecked(
        packageData: InstalledPackageSummary,
        comment: String,
    ): Map<String, AssemblyCheckRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val checks = readDocument().checks.associateBy { it.revisionKey }.toMutableMap()
            val record = AssemblyCheckRecord(
                revisionKey = packageData.revisionKey(),
                projectId = packageData.project.projectId,
                assemblyGuid = packageData.assembly.guid,
                positionNumber = packageData.assembly.positionNumber,
                revision = packageData.assembly.revision,
                checkedAt = Instant.now().toString(),
                comment = comment.trim(),
            )
            checks[record.revisionKey] = record
            writeDocument(WorkshopStateDocument(checks = checks.values.sortedBy { it.revisionKey }))
            checks
        }
    }

    suspend fun clear(keys: Collection<String>): Map<String, AssemblyCheckRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val checks = readDocument().checks.associateBy { it.revisionKey }.toMutableMap()
                keys.forEach(checks::remove)
                writeDocument(WorkshopStateDocument(checks = checks.values.sortedBy { it.revisionKey }))
                checks
            }
        }

    private fun readDocument(): WorkshopStateDocument {
        val file = stateFile.baseFile
        if (!file.isFile) return WorkshopStateDocument()
        return runCatching {
            stateFile.openRead().bufferedReader().use { reader ->
                JsonCodec.value.decodeFromString<WorkshopStateDocument>(reader.readText())
            }
        }.getOrElse { WorkshopStateDocument() }
    }

    private fun writeDocument(document: WorkshopStateDocument) {
        stateFile.baseFile.parentFile?.mkdirs()
        val stream = stateFile.startWrite()
        try {
            stream.write(JsonCodec.value.encodeToString(document).encodeToByteArray())
            stateFile.finishWrite(stream)
        } catch (error: Throwable) {
            stateFile.failWrite(stream)
            throw error
        }
    }
}
