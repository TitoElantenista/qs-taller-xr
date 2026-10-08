package com.qs.tallerxr.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Catalog(val projects: List<CatalogProject> = emptyList())

@Serializable
data class CatalogProject(
    val id: String,
    val name: String,
    val drawings: List<CatalogDrawing> = emptyList(),
)

@Serializable
data class CatalogDrawing(
    val number: String,
    val positions: List<CatalogPosition> = emptyList(),
)

@Serializable
data class CatalogPosition(
    val id: String,
    val positionNumber: String,
    val revision: Int,
    val occurrenceCount: Int = 0,
    val producer: String = "",
    val isUnassigned: Boolean = false,
)

@Serializable
data class SourceDetail(
    val source: CatalogPosition,
    val occurrences: List<Occurrence> = emptyList(),
)

@Serializable
data class Occurrence(
    val sourceGlobalId: String,
    val name: String? = null,
    val memberCount: Int = 0,
)

@Serializable
data class PackageManifest(
    val schemaVersion: String,
    val packageId: String,
    val createdAt: String,
    val producer: Producer,
    val project: ProjectRef,
    val assembly: AssemblyRef,
    val coordinateSystem: CoordinateSystem,
    val files: PackageFiles,
)

@Serializable
data class Producer(val name: String, val version: String)

@Serializable
data class ProjectRef(val projectId: String, val name: String)

@Serializable
data class AssemblyRef(val guid: String, val positionNumber: String, val revision: Int)

@Serializable
data class CoordinateSystem(
    val handedness: String,
    val upAxis: String,
    val linearUnit: String,
    val matrixStorage: String,
    val vectorConvention: String,
    val transformNotation: String,
)

@Serializable
data class PackageFiles(
    val model: String,
    val registrationModel: String,
    val parts: String,
    val thumbnail: String,
    val checksums: String,
)

@Serializable
data class PartDocument(
    val schemaVersion: String,
    val assemblyGuid: String,
    val parts: List<Part>,
)

@Serializable
data class Part(
    val guid: String,
    val positionNumber: String,
    val kind: String,
    val gltfNodeName: String,
    val boundsAssemblyM: Bounds,
    val inspectable: Boolean,
    val properties: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class Bounds(val min: List<Float>, val max: List<Float>)

@Serializable
data class ChecksumDocument(
    val schemaVersion: String,
    val algorithm: String,
    val files: List<ChecksumEntry>,
)

@Serializable
data class ChecksumEntry(
    val path: String,
    val sha256: String,
    val byteLength: Long,
)

@Serializable
data class ProjectBundleManifest(
    val schemaVersion: String,
    val bundleId: String,
    val createdAt: String,
    val producer: Producer,
    val project: ProjectRef,
    val exportMode: String,
    val packages: List<ProjectBundleEntry>,
    val skipped: List<ProjectBundleSkippedEntry> = emptyList(),
)

@Serializable
data class ProjectBundleEntry(
    val packageId: String,
    val sourceId: String,
    val occurrenceId: String,
    val positionNumber: String,
    val revision: Int,
    val path: String,
    val sha256: String,
    val byteLength: Long,
)

@Serializable
data class ProjectBundleSkippedEntry(
    val sourceId: String,
    val occurrenceId: String,
    val positionNumber: String,
    val drawingNumber: String,
    val revision: Int,
    val occurrence: Int,
    val stage: String,
    val reason: String,
)

enum class ImportCase { NEW_PROJECT, NEW_REVISION, OVERWRITTEN }

data class ProjectImportProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val fraction: Float,
    val estimatedRequiredBytes: Long? = null,
)

data class ProjectImportResult(
    val project: ProjectRef,
    val packages: List<InstalledPackageSummary>,
    val cases: Map<ImportCase, Int>,
    val skipped: List<ProjectBundleSkippedEntry> = emptyList(),
)

@Serializable
data class InstalledPackageSummary(
    val packageId: String,
    val createdAt: String,
    val producer: Producer,
    val project: ProjectRef,
    val assembly: AssemblyRef,
    val partCount: Int,
    val archiveBytes: Long,
) {
    fun revisionKey(): String = listOf(
        project.projectId,
        assembly.guid,
        assembly.revision.toString(),
    ).joinToString(":")
}

@Serializable
data class PackageCatalogDocument(
    val schemaVersion: String = "1.0",
    val packages: List<InstalledPackageSummary> = emptyList(),
)

data class StorageStats(
    val installedBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long,
    val packageCount: Int = 0,
)

@Serializable
data class AssemblyCheckRecord(
    val revisionKey: String,
    val projectId: String,
    val assemblyGuid: String,
    val positionNumber: String,
    val revision: Int,
    val checkedAt: String,
    val comment: String = "",
)

@Serializable
data class WorkshopStateDocument(
    val schemaVersion: String = "1.0",
    val checks: List<AssemblyCheckRecord> = emptyList(),
)

data class InstalledPackage(
    val root: File,
    val manifest: PackageManifest,
    val parts: PartDocument,
    val checksums: ChecksumDocument,
    /** Set when the GLB is read straight from the operator's folder instead of from [root]. */
    val modelUri: String? = null,
) {
    val modelFile: File get() = File(root, manifest.files.model)

    /** What the renderer loads: the linked file itself, or the model stored with the package. */
    val modelLocation: String get() = modelUri ?: modelFile.toURI().toString()
    val registrationModelFile: File
        get() = File(root, manifest.files.registrationModel)

    fun revisionKey(): String = listOf(
        manifest.project.projectId,
        manifest.assembly.guid,
        manifest.assembly.revision.toString(),
    ).joinToString(":")
}
