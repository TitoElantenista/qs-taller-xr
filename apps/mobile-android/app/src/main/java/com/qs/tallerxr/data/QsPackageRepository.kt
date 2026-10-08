package com.qs.tallerxr.data

import android.content.Context
import android.util.AtomicFile
import com.qs.tallerxr.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class QsPackageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir
    private val qsRoot = File(filesRoot, "qsxr")
    private val archivesRoot = File(qsRoot, "archives")
    private val legacyPackagesRoot = File(qsRoot, "packages")
    private val openCacheRoot = File(appContext.cacheDir, "qsxr-open")
    private val catalogFile = AtomicFile(File(qsRoot, "catalog.json"))
    private val mutex = Mutex()

    suspend fun listInstalled(
        onCatalogBuildProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<InstalledPackageSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readOrBuildCatalog(onCatalogBuildProgress).packages.sortedWith(summaryComparator)
        }
    }

    suspend fun loadPackage(summary: InstalledPackageSummary): InstalledPackage =
        withContext(Dispatchers.IO) {
            val legacyRoot = File(legacyPackagesRoot, summary.packageId)
            if (legacyRoot.isDirectory) return@withContext loadExtracted(legacyRoot)

            val archive = archiveFile(summary.packageId)
            require(archive.isFile) { "Das Paket ${summary.packageId} wurde nicht gefunden." }
            val destination = File(openCacheRoot, summary.packageId)
            destination.deleteRecursively()
            try {
                val validated = validateArchive(archive)
                require(validated.manifest.packageId == summary.packageId) {
                    "Die gespeicherte packageId stimmt nicht mit dem Katalog überein."
                }
                extractArchive(
                    archive,
                    destination,
                    validated.manifest,
                    validated.checksums,
                )
                loadExtracted(destination)
            } catch (error: Throwable) {
                destination.deleteRecursively()
                throw error
            }
        }

    suspend fun clearOpenCache(packageIds: Collection<String>? = null) = withContext(Dispatchers.IO) {
        if (packageIds == null) {
            openCacheRoot.deleteRecursively()
        } else {
            packageIds.forEach { File(openCacheRoot, it).deleteRecursively() }
        }
    }

    suspend fun recoverIncompleteImports(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverArchiveTransactions() + recoverLegacyTransactions()
        }
    }

    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            val catalog = readOrBuildCatalog()
            StorageStats(
                installedBytes = qsRoot.treeSize(),
                availableBytes = appContext.allocatableStorageBytes(),
                totalBytes = filesRoot.totalSpace,
                packageCount = catalog.packages.size,
            )
        }
    }

    suspend fun deletePackage(packageId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { UUID.fromString(packageId) }.getOrElse { return@withContext false }
        mutex.withLock {
            val catalog = readOrBuildCatalog()
            val exists = catalog.packages.any { it.packageId == packageId }
            if (!exists) return@withLock false
            archiveFile(packageId).delete()
            File(legacyPackagesRoot, packageId).deleteRecursively()
            File(openCacheRoot, packageId).deleteRecursively()
            writeCatalog(catalog.copy(packages = catalog.packages.filterNot { it.packageId == packageId }))
            true
        }
    }

    suspend fun deleteProject(projectId: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val catalog = readOrBuildCatalog()
            val targets = catalog.packages.filter { it.project.projectId == projectId }
            targets.forEach { summary ->
                archiveFile(summary.packageId).delete()
                File(legacyPackagesRoot, summary.packageId).deleteRecursively()
                File(openCacheRoot, summary.packageId).deleteRecursively()
            }
            writeCatalog(
                catalog.copy(packages = catalog.packages.filterNot { it.project.projectId == projectId }),
            )
            targets.size
        }
    }

    suspend fun importProject(
        archive: File,
        onProgress: (ProjectImportProgress) -> Unit = {},
    ): ProjectImportResult = withContext(Dispatchers.IO) {
        require(archive.isFile && archive.length() <= MAXIMUM_PROJECT_BYTES) {
            "Das Projekt existiert nicht oder ist größer als 8 GB."
        }
        onProgress(ProjectImportProgress("Projekt wird geöffnet und geprüft", 0, 1, 0f))
        qsRoot.mkdirs()
        archivesRoot.mkdirs()
        val transaction = File(qsRoot, ".archive-transaction-${UUID.randomUUID()}")
        val stagedRoot = File(transaction, "staged")
        check(stagedRoot.mkdirs()) { "Der Import konnte nicht vorbereitet werden." }
        try {
            val staged = ArrayList<InstalledPackageSummary>()
            val bundle: ProjectBundleManifest
            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry(BUNDLE_MANIFEST)
                    ?: error("$BUNDLE_MANIFEST fehlt.")
                val manifestBytes = zip.getInputStream(manifestEntry).use {
                    it.readLimited(MAXIMUM_BUNDLE_MANIFEST_BYTES)
                }
                bundle = decode(manifestBytes)
                val estimatedRequiredBytes = estimateRequiredBytes(archive.length(), bundle)
                val additionalRequiredBytes = estimatedRequiredBytes - archive.length()
                validateBundle(bundle, additionalRequiredBytes)
                writeTransactionJournal(transaction, bundle.packages.map { it.packageId })
                onProgress(
                    ProjectImportProgress(
                        "Manifest geprüft",
                        1,
                        1,
                        0.04f,
                        estimatedRequiredBytes,
                    ),
                )
                val expectedNames = bundle.packages.mapTo(HashSet()) { it.path }.apply {
                    add(BUNDLE_MANIFEST)
                }
                val actualNames = zip.entries().asSequence().filterNot { it.isDirectory }
                    .map { it.name }.toList()
                require(
                    actualNames.size == actualNames.toSet().size && actualNames.toSet() == expectedNames,
                ) { "Das Projekt enthält nicht deklarierte oder unvollständige Dateien." }

                val progressStep = (bundle.packages.size / MAXIMUM_PROGRESS_UPDATES)
                    .coerceAtLeast(1)
                bundle.packages.forEachIndexed { index, item ->
                    currentCoroutineContext().ensureActive()
                    if (index % progressStep == 0) {
                        onProgress(
                            ProjectImportProgress(
                                "Prüfe ${item.positionNumber} · Rev. ${item.revision}",
                                index,
                                bundle.packages.size,
                                0.04f + 0.9f * index / bundle.packages.size.coerceAtLeast(1),
                                estimatedRequiredBytes,
                            ),
                        )
                    }
                    val entry = zip.getEntry(item.path) ?: error("${item.path} fehlt.")
                    require(entry.size < 0 || entry.size == item.byteLength) {
                        "Falsche Dateigröße für ${item.path}."
                    }
                    val stagedArchive = File(stagedRoot, "${item.packageId}.qsxr")
                    val coroutineContext = currentCoroutineContext()
                    val copied = zip.getInputStream(entry).use { input ->
                        stagedArchive.outputStream().buffered().use { output ->
                            input.copyLimitedAndDigest(output, MAXIMUM_PACKAGE_BYTES) {
                                coroutineContext.ensureActive()
                            }
                        }
                    }
                    require(copied.byteLength == item.byteLength) {
                        "Falsche Dateigröße für ${item.path}."
                    }
                    require(copied.sha256.equals(item.sha256, ignoreCase = true)) {
                        "Falsche Prüfsumme für ${item.path}."
                    }
                    val validated = try {
                        validateArchive(stagedArchive)
                    } catch (error: Throwable) {
                        throw IllegalArgumentException(
                            "${item.positionNumber} · Rev. ${item.revision}: " +
                                (error.message ?: error::class.java.simpleName),
                            error,
                        )
                    }
                    validateBundlePackage(bundle, item, validated)
                    staged += validated.toSummary(copied.byteLength)
                    if ((index + 1) % progressStep == 0 || index + 1 == bundle.packages.size) {
                        onProgress(
                            ProjectImportProgress(
                                "Geprüft: ${item.positionNumber} · Rev. ${item.revision}",
                                index + 1,
                                bundle.packages.size,
                                0.04f + 0.9f * (index + 1) /
                                    bundle.packages.size.coerceAtLeast(1),
                                estimatedRequiredBytes,
                            ),
                        )
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(
                ProjectImportProgress(
                    "Katalog und atomare Installation werden vorbereitet",
                    bundle.packages.size,
                    bundle.packages.size,
                    0.96f,
                ),
            )
            withContext(NonCancellable) {
                mutex.withLock {
                    installTransaction(transaction, bundle.project, staged, bundle.skipped) {
                            stage, fraction ->
                        onProgress(
                            ProjectImportProgress(
                                stage,
                                bundle.packages.size,
                                bundle.packages.size,
                                fraction,
                            ),
                        )
                    }
                }
            }.also {
                onProgress(ProjectImportProgress("Import abgeschlossen", 1, 1, 1f))
            }
        } catch (error: Throwable) {
            transaction.deleteRecursively()
            throw error
        } finally {
            archive.delete()
        }
    }

    private fun installTransaction(
        transaction: File,
        project: ProjectRef,
        staged: List<InstalledPackageSummary>,
        skipped: List<ProjectBundleSkippedEntry>,
        onProgress: (String, Float) -> Unit,
    ): ProjectImportResult {
        require(staged.isNotEmpty()) { "Das Projekt enthält keine Baugruppen." }
        require(staged.map { it.revisionKey() }.toSet().size == staged.size) {
            "Das Projekt enthält zwei Pakete für dasselbe Vorkommen und dieselbe Revision."
        }
        onProgress("Installierter Katalog wird gelesen", 0.965f)
        val oldCatalog = readOrBuildCatalog()
        File(transaction, OLD_CATALOG).writeText(JsonCodec.value.encodeToString(oldCatalog))
        val oldByRevision = oldCatalog.packages.associateBy { it.revisionKey() }
        val oldProjects = oldCatalog.packages.mapTo(HashSet()) { it.project.projectId }
        val cases = mutableMapOf<ImportCase, Int>().withDefault { 0 }
        staged.forEach { summary ->
            val kind = when {
                summary.project.projectId !in oldProjects -> ImportCase.NEW_PROJECT
                summary.revisionKey() in oldByRevision -> ImportCase.OVERWRITTEN
                else -> ImportCase.NEW_REVISION
            }
            cases[kind] = cases.getValue(kind) + 1
        }

        val backupArchives = File(transaction, "backup-archives").apply { mkdirs() }
        val backupLegacy = File(transaction, "backup-legacy").apply { mkdirs() }
        val stagedByKey = staged.associateBy { it.revisionKey() }
        try {
            onProgress("Sicherungskopie wird erstellt", 0.965f)
            stagedByKey.keys.forEachIndexed { index, key ->
                oldByRevision[key]?.let { old ->
                    archiveFile(old.packageId).takeIf(File::isFile)?.let { source ->
                        check(source.renameTo(File(backupArchives, source.name))) {
                            "${old.positionLabel()} konnte nicht gesichert werden."
                        }
                    }
                    File(legacyPackagesRoot, old.packageId).takeIf(File::isDirectory)?.let { source ->
                        check(source.renameTo(File(backupLegacy, source.name))) {
                            "${old.positionLabel()} konnte nicht gesichert werden."
                        }
                    }
                }
                if (index % TRANSACTION_PROGRESS_INTERVAL == 0) {
                    onProgress(
                        "Revisionen werden gesichert · ${index + 1}/${stagedByKey.size}",
                        0.965f + 0.01f * (index + 1) / stagedByKey.size.coerceAtLeast(1),
                    )
                }
            }
            staged.forEachIndexed { index, summary ->
                val destination = archiveFile(summary.packageId)
                if (destination.exists()) {
                    check(destination.renameTo(File(backupArchives, destination.name))) {
                        "${summary.positionLabel()} konnte nicht gesichert werden."
                    }
                }
                if (index % TRANSACTION_PROGRESS_INTERVAL == 0) {
                    onProgress(
                        "Ziele werden geprüft · ${index + 1}/${staged.size}",
                        0.975f,
                    )
                }
            }
            File(transaction, TRANSACTION_PUBLISHING).writeText("publishing")
            onProgress("Komprimierte Pakete werden veröffentlicht", 0.976f)
            archivesRoot.mkdirs()
            staged.forEachIndexed { index, summary ->
                val source = File(transaction, "staged/${summary.packageId}.qsxr")
                check(source.renameTo(archiveFile(summary.packageId))) {
                    "${summary.positionLabel()} konnte nicht installiert werden."
                }
                if (index % TRANSACTION_PROGRESS_INTERVAL == 0 || index + 1 == staged.size) {
                    onProgress(
                        "Pakete werden veröffentlicht · ${index + 1}/${staged.size}",
                        0.976f + 0.014f * (index + 1) / staged.size.coerceAtLeast(1),
                    )
                }
            }
            val replacementKeys = stagedByKey.keys
            val newCatalog = PackageCatalogDocument(
                packages = (oldCatalog.packages.filterNot { it.revisionKey() in replacementKeys } + staged)
                    .sortedWith(summaryComparator),
            )
            onProgress("Katalog wird gespeichert", 0.993f)
            writeCatalog(newCatalog)
            File(transaction, TRANSACTION_COMMITTED).writeText("committed")
            var deletedFiles = 0
            runCatching {
                deleteTreeIncrementally(transaction) {
                    deletedFiles += 1
                    if (deletedFiles % CLEANUP_PROGRESS_INTERVAL == 0) {
                        onProgress(
                            "Alter Speicher wird freigegeben · $deletedFiles Dateien",
                            0.997f,
                        )
                    }
                }
            }
            onProgress("Installation veröffentlicht", 0.999f)
            return ProjectImportResult(project, staged, cases, skipped)
        } catch (error: Throwable) {
            rollbackArchiveTransaction(transaction)
            throw error
        }
    }

    private fun validateBundlePackage(
        bundle: ProjectBundleManifest,
        item: ProjectBundleEntry,
        validated: ValidatedPackage,
    ) {
        require(validated.manifest.packageId == item.packageId) {
            "Die packageId von ${item.path} stimmt nicht überein."
        }
        require(validated.manifest.project == bundle.project) {
            "${item.path} gehört zu einem anderen Projekt."
        }
        require(
            validated.manifest.assembly.positionNumber == item.positionNumber &&
                validated.manifest.assembly.revision == item.revision,
        ) { "Die für ${item.path} angegebene Revision stimmt nicht überein." }
    }

    private data class ValidatedPackage(
        val manifest: PackageManifest,
        val checksums: ChecksumDocument,
        val parts: PartDocument,
    ) {
        fun toSummary(archiveBytes: Long) = InstalledPackageSummary(
            packageId = manifest.packageId,
            createdAt = manifest.createdAt,
            producer = manifest.producer,
            project = manifest.project,
            assembly = manifest.assembly,
            partCount = parts.parts.size,
            archiveBytes = archiveBytes,
        )
    }

    private fun validateArchive(archive: File): ValidatedPackage {
        require(archive.length() in 1..MAXIMUM_PACKAGE_BYTES.toLong()) {
            "Das Paket ist größer als 512 MB."
        }
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.none { it.isDirectory }) { "Das Paket enthält Ordner." }
            val names = entries.map { it.name }
            require(names.size == names.toSet().size) { "Das Paket enthält doppelte Dateien." }
            require(names.all { it == File(it).name }) {
                "Das Paket enthält einen unzulässigen Pfad."
            }
            val manifestBytes = zip.readEntry("manifest.json", MAXIMUM_PACKAGE_METADATA_BYTES)
            val manifest = decode<PackageManifest>(manifestBytes)
            validateManifest(manifest)
            require(names.toSet() == packageEntries(manifest)) {
                "Das Paket enthält nicht deklarierte oder unvollständige Dateien."
            }
            val checksumsBytes = zip.readEntry("checksums.json", MAXIMUM_PACKAGE_METADATA_BYTES)
            val checksums = decode<ChecksumDocument>(checksumsBytes)
            validateArchiveChecksums(zip, manifest, checksums, manifestBytes)
            val parts = decode<PartDocument>(
                zip.readEntry(manifest.files.parts, MAXIMUM_PACKAGE_METADATA_BYTES),
            )
            validateReferences(manifest, parts)
            return ValidatedPackage(manifest, checksums, parts)
        }
    }

    private fun extractArchive(
        archive: File,
        destination: File,
        manifest: PackageManifest,
        checksums: ChecksumDocument,
    ) {
        check(destination.mkdirs()) { "${destination.name} konnte nicht vorbereitet werden." }
        val expected = checksums.files.associateBy { it.path }
        var total = 0L
        ZipFile(archive).use { zip ->
            packageEntries(manifest).forEach { name ->
                val entry = zip.getEntry(name) ?: error("$name fehlt.")
                val limit = if (name.endsWith(".json")) {
                    MAXIMUM_PACKAGE_METADATA_BYTES
                } else {
                    MAXIMUM_PACKAGE_BYTES
                }
                val result = zip.getInputStream(entry).use { input ->
                    File(destination, name).outputStream().buffered().use { output ->
                        input.copyLimitedAndDigest(output, limit)
                    }
                }
                total += result.byteLength
                require(total <= MAXIMUM_PACKAGE_BYTES) {
                    "Der entpackte Inhalt ist größer als 512 MB."
                }
                expected[name]?.let { checksum ->
                    require(result.byteLength == checksum.byteLength) {
                        "Falsche Dateigröße für $name."
                    }
                    require(result.sha256.equals(checksum.sha256, ignoreCase = true)) {
                        "Falsche Prüfsumme für $name."
                    }
                }
            }
        }
    }

    private fun loadExtracted(root: File): InstalledPackage {
        val manifestFile = File(root, "manifest.json").also {
            require(it.isFile) { "manifest.json fehlt im installierten Paket." }
        }
        val manifest = decode<PackageManifest>(manifestFile.readSmallJson())
        validateManifest(manifest)
        val files = packageEntries(manifest).associateWith { name ->
            File(root, name).also { require(it.isFile) { "$name fehlt im installierten Paket." } }
        }
        val checksums = decode<ChecksumDocument>(files.getValue("checksums.json").readSmallJson())
        validateInstalledFiles(files, manifest, checksums)
        val parts = decode<PartDocument>(files.getValue(manifest.files.parts).readSmallJson())
        validateReferences(manifest, parts)
        return InstalledPackage(root, manifest, parts, checksums)
    }

    private fun readOrBuildCatalog(
        onBuildProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): PackageCatalogDocument {
        if (BuildConfig.BUNDLED_DEMO_ENABLED) {
            installBundledSamplesOnce()
        } else {
            removePreviouslyBundledSamples()
        }
        readCatalog()?.let { return it }
        val summaries = mutableListOf<InstalledPackageSummary>()
        val archives = archivesRoot.listFiles()
            ?.filter { it.isFile && it.extension == "qsxr" }
            .orEmpty()
        val legacyPackages = legacyPackagesRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            .orEmpty()
        val total = archives.size + legacyPackages.size
        val progressStep = (total / MAXIMUM_CATALOG_PROGRESS_UPDATES).coerceAtLeast(1)
        var completed = 0

        if (total > 0) onBuildProgress(0, total)
        archives.forEach { archive ->
            summaryFromArchive(archive)?.let(summaries::add)
            completed += 1
            if (completed % progressStep == 0 || completed == total) {
                onBuildProgress(completed, total)
            }
        }
        legacyPackages.forEach { root ->
            summaryFromLegacy(root)?.let(summaries::add)
            completed += 1
            if (completed % progressStep == 0 || completed == total) {
                onBuildProgress(completed, total)
            }
        }
        val catalog = PackageCatalogDocument(
            packages = summaries.distinctBy { it.packageId }.sortedWith(summaryComparator),
        )
        writeCatalog(catalog)
        return catalog
    }

    private class BundledSample(
        val asset: String,
        val packageId: String,
        val marker: String,
        /** Markers written by earlier app versions for this package. */
        val previousMarkers: List<String> = emptyList(),
    )

    /**
     * Seeds the demo models through the same archive/catalog path as imported packages.  Every
     * model has its own marker that survives package and project deletion, so a sample the user
     * deleted is not resurrected on the next refresh while a newly bundled one still arrives.
     */
    private fun installBundledSamplesOnce() {
        BUNDLED_SAMPLES.forEach { sample ->
            runCatching { installBundledSampleOnce(sample) }
        }
    }

    /** Remove only samples auto-installed by an older release; user imports and QS records remain. */
    private fun removePreviouslyBundledSamples() {
        val catalog = readCatalog()
        val removed = mutableSetOf<String>()
        BUNDLED_SAMPLES.forEach { sample ->
            val markers = (sample.previousMarkers + sample.marker).map { AtomicFile(File(qsRoot, it)) }
            if (markers.none { markerMatches(it, sample.packageId) }) return@forEach
            val summary = catalog?.packages?.find { it.packageId == sample.packageId }
                ?: summaryFromArchive(archiveFile(sample.packageId))
                ?: summaryFromLegacy(File(legacyPackagesRoot, sample.packageId))
            if (summary != null && !isPreviouslyBundledDemo(summary, sample.packageId)) return@forEach
            val archive = archiveFile(sample.packageId)
            val legacy = File(legacyPackagesRoot, sample.packageId)
            check(!archive.exists() || archive.delete()) { "Das frühere Testmodell konnte nicht entfernt werden." }
            check(!legacy.exists() || legacy.deleteRecursively()) { "Das frühere Testmodell konnte nicht entfernt werden." }
            File(openCacheRoot, sample.packageId).deleteRecursively()
            removed += sample.packageId
        }
        if (removed.isEmpty()) return
        if (catalog != null) writeCatalog(catalog.copy(packages = catalog.packages.filterNot { it.packageId in removed }))
        // Clear seed markers only after the catalog is saved. A manually reimported demo stays put.
        BUNDLED_SAMPLES.filter { it.packageId in removed }.forEach { sample ->
            (sample.previousMarkers + sample.marker).forEach { AtomicFile(File(qsRoot, it)).delete() }
        }
    }

    private fun installBundledSampleOnce(sample: BundledSample) {
        val marker = AtomicFile(File(qsRoot, sample.marker))
        if (markerMatches(marker, sample.packageId)) return

        qsRoot.mkdirs()
        archivesRoot.mkdirs()
        val destination = archiveFile(sample.packageId)
        val seededBefore = sample.previousMarkers.any {
            markerMatches(AtomicFile(File(qsRoot, it)), sample.packageId)
        }
        if (seededBefore && !destination.isFile) {
            // An earlier version installed it and the user removed it: keep it removed.
            writeMarker(marker, sample.packageId)
            return
        }
        val stagedArchive = File(qsRoot, ".bundled-${UUID.randomUUID()}.tmp")
        try {
            val copied = appContext.assets.open(sample.asset).use { input ->
                stagedArchive.outputStream().buffered().use { output ->
                    input.copyLimitedAndDigest(output, MAXIMUM_PACKAGE_BYTES)
                }
            }
            val bundled = validateArchive(stagedArchive)
            require(bundled.manifest.packageId == sample.packageId) {
                "Die packageId des integrierten Demomodells stimmt nicht mit der App überein."
            }
            // The bundled copy always wins: it may carry a newer project name or geometry.
            if (destination.isFile) {
                check(destination.delete()) { "Das integrierte Demomodell konnte nicht ersetzt werden." }
            }
            File(openCacheRoot, sample.packageId).deleteRecursively()
            check(stagedArchive.renameTo(destination)) {
                "Das integrierte Demomodell konnte nicht installiert werden."
            }
            val summary = bundled.toSummary(destination.length().takeIf { it > 0L } ?: copied.byteLength)
            readCatalog()?.let { catalog ->
                writeCatalog(
                    catalog.copy(
                        packages = (catalog.packages.filterNot { it.packageId == summary.packageId } + summary)
                            .sortedWith(summaryComparator),
                    ),
                )
            }
            writeMarker(marker, sample.packageId)
        } finally {
            stagedArchive.delete()
        }
    }

    private fun markerMatches(marker: AtomicFile, packageId: String): Boolean = runCatching {
        marker.openRead().bufferedReader().use { reader -> reader.readText().trim() == packageId }
    }.getOrDefault(false)

    private fun writeMarker(marker: AtomicFile, packageId: String) {
        val stream = marker.startWrite()
        try {
            stream.write("$packageId\n".encodeToByteArray())
            marker.finishWrite(stream)
        } catch (error: Throwable) {
            marker.failWrite(stream)
            throw error
        }
    }

    private fun readCatalog(): PackageCatalogDocument? {
        if (!catalogFile.baseFile.isFile) return null
        return runCatching {
            catalogFile.openRead().bufferedReader().use { reader ->
                JsonCodec.value.decodeFromString<PackageCatalogDocument>(reader.readText())
            }
        }.getOrNull()
    }

    private fun writeCatalog(document: PackageCatalogDocument) {
        qsRoot.mkdirs()
        val stream = catalogFile.startWrite()
        try {
            stream.write(JsonCodec.value.encodeToString(document).encodeToByteArray())
            catalogFile.finishWrite(stream)
        } catch (error: Throwable) {
            catalogFile.failWrite(stream)
            throw error
        }
    }

    private fun summaryFromArchive(archive: File): InstalledPackageSummary? = runCatching {
        ZipFile(archive).use { zip ->
            val manifest = decode<PackageManifest>(
                zip.readEntry("manifest.json", MAXIMUM_PACKAGE_METADATA_BYTES),
            )
            val parts = decode<PartDocument>(
                zip.readEntry(manifest.files.parts, MAXIMUM_PACKAGE_METADATA_BYTES),
            )
            InstalledPackageSummary(
                packageId = manifest.packageId,
                createdAt = manifest.createdAt,
                producer = manifest.producer,
                project = manifest.project,
                assembly = manifest.assembly,
                partCount = parts.parts.size,
                archiveBytes = archive.length(),
            )
        }
    }.getOrNull()

    private fun summaryFromLegacy(root: File): InstalledPackageSummary? = runCatching {
        val manifest = decode<PackageManifest>(File(root, "manifest.json").readSmallJson())
        val parts = decode<PartDocument>(File(root, manifest.files.parts).readSmallJson())
        InstalledPackageSummary(
            packageId = manifest.packageId,
            createdAt = manifest.createdAt,
            producer = manifest.producer,
            project = manifest.project,
            assembly = manifest.assembly,
            partCount = parts.parts.size,
            archiveBytes = root.treeSize(),
        )
    }.getOrNull()

    private fun recoverArchiveTransactions(): Int {
        qsRoot.mkdirs()
        val transactions = qsRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".archive-transaction-") }
            ?: emptyList()
        transactions.forEach { transaction ->
            if (File(transaction, TRANSACTION_COMMITTED).isFile) {
                transaction.deleteRecursively()
            } else {
                rollbackArchiveTransaction(transaction)
            }
        }
        return transactions.size
    }

    private fun rollbackArchiveTransaction(transaction: File) {
        if (File(transaction, TRANSACTION_PUBLISHING).isFile) {
            File(transaction, TRANSACTION_JOURNAL).takeIf(File::isFile)
                ?.readLines()?.filter(String::isNotBlank)
                ?.forEach { archiveFile(it).delete() }
        }
        File(transaction, "backup-archives").listFiles()?.forEach { backup ->
            archivesRoot.mkdirs()
            archiveFile(backup.name.removeSuffix(".qsxr")).delete()
            backup.renameTo(File(archivesRoot, backup.name))
        }
        File(transaction, "backup-legacy").listFiles()?.forEach { backup ->
            val destination = File(legacyPackagesRoot, backup.name)
            destination.deleteRecursively()
            backup.renameTo(destination)
        }
        File(transaction, OLD_CATALOG).takeIf(File::isFile)?.let { backup ->
            runCatching {
                JsonCodec.value.decodeFromString<PackageCatalogDocument>(backup.readText())
            }.getOrNull()?.let(::writeCatalog)
        }
        transaction.deleteRecursively()
    }

    private fun recoverLegacyTransactions(): Int {
        legacyPackagesRoot.mkdirs()
        val transactions = legacyPackagesRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".transaction-") }
            ?: emptyList()
        transactions.forEach { transaction ->
            val publishing = File(transaction, "publishing.marker").isFile
            if (publishing) {
                File(transaction, "package_ids.txt").takeIf(File::isFile)
                    ?.readLines()?.filter(String::isNotBlank)
                    ?.forEach { packageId -> File(legacyPackagesRoot, packageId).deleteRecursively() }
            }
            File(transaction, "backup").listFiles()?.filter(File::isDirectory)?.forEach { backup ->
                val originalName = backup.name.substringAfter('-', missingDelimiterValue = "")
                if (originalName.isNotBlank()) {
                    val destination = File(legacyPackagesRoot, originalName)
                    destination.deleteRecursively()
                    backup.renameTo(destination)
                }
            }
            transaction.deleteRecursively()
        }
        return transactions.size
    }

    private fun writeTransactionJournal(transaction: File, packageIds: List<String>) {
        File(transaction, TRANSACTION_JOURNAL).writeText(packageIds.joinToString("\n"))
    }

    private fun validateBundle(bundle: ProjectBundleManifest, additionalRequiredBytes: Long) {
        require(bundle.schemaVersion.major() == 1) {
            "Nicht unterstützte Projektversion: ${bundle.schemaVersion}"
        }
        runCatching { UUID.fromString(bundle.bundleId) }
            .getOrElse { error("bundleId ist keine gültige UUID.") }
        runCatching { UUID.fromString(bundle.project.projectId) }
            .getOrElse { error("projectId ist keine gültige UUID.") }
        require(bundle.exportMode in setOf("COMPLETE", "DRAWINGS", "ASSEMBLIES")) {
            "Unbekannter Exportmodus."
        }
        require(bundle.packages.isNotEmpty()) { "Das Projekt enthält keine Pakete." }
        val availableBytes = appContext.allocatableStorageBytes()
        require(additionalRequiredBytes <= availableBytes) {
            "Zu wenig Speicherplatz: Nach dem Kopieren des Projekts werden etwa ${formatBytes(additionalRequiredBytes)} zusätzlich benötigt, Android kann ${formatBytes(availableBytes)} bereitstellen."
        }
        require(bundle.packages.map { it.packageId }.toSet().size == bundle.packages.size) {
            "Das Projekt enthält doppelte packageIds."
        }
        bundle.packages.forEach { item ->
            runCatching { UUID.fromString(item.packageId) }
                .getOrElse { error("Ungültige packageId: ${item.packageId}") }
            require(item.path == "packages/${item.packageId}.qsxr") {
                "Unzulässiger Paketpfad: ${item.path}"
            }
            require(item.byteLength in 1..MAXIMUM_PACKAGE_BYTES.toLong()) {
                "Unzulässige Paketgröße."
            }
            require(item.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Ungültiger SHA-256-Wert für ${item.path}."
            }
        }
    }

    private fun validateManifest(manifest: PackageManifest) {
        require(manifest.schemaVersion.major() == 2) {
            "Nicht unterstützte Paketversion: ${manifest.schemaVersion}"
        }
        runCatching { UUID.fromString(manifest.packageId) }
            .getOrElse { error("packageId ist keine gültige UUID.") }
        require(
            manifest.coordinateSystem == CoordinateSystem(
                handedness = "RIGHT_HANDED",
                upAxis = "Y",
                linearUnit = "m",
                matrixStorage = "COLUMN_MAJOR",
                vectorConvention = "COLUMN_VECTOR",
                transformNotation = "PARENT_FROM_CHILD",
            ),
        ) { "Das Koordinatensystem entspricht nicht dem QS-XR-2.x-Vertrag." }
        require(
            manifest.files.model == "assembly.glb" &&
                manifest.files.registrationModel == "registration_model.bin" &&
                manifest.files.parts == "parts.json" &&
                manifest.files.thumbnail == "thumbnail.png" &&
                manifest.files.checksums == "checksums.json",
        ) { "manifest.json deklariert unerwartete Dateien." }
    }

    private fun packageEntries(manifest: PackageManifest): Set<String> =
        BASE_PACKAGE_ENTRIES

    private fun validateArchiveChecksums(
        zip: ZipFile,
        manifest: PackageManifest,
        document: ChecksumDocument,
        manifestBytes: ByteArray,
    ) {
        require(document.schemaVersion.major() == 1 && document.algorithm == "SHA-256") {
            "checksums.json verwendet kein SHA-256 oder keine unterstützte Version."
        }
        val expected = packageEntries(manifest) - "checksums.json"
        require(
            document.files.size == expected.size && document.files.map { it.path }.toSet() == expected,
        ) {
            "checksums.json deckt das Paket nicht exakt ab."
        }
        require(document.files.sumOf { it.byteLength } <= MAXIMUM_PACKAGE_BYTES) {
            "Der entpackte Inhalt ist größer als 512 MB."
        }
        document.files.forEach { item ->
            require(item.path == File(item.path).name) { "Unzulässiger Pfad: ${item.path}" }
            require(item.byteLength in 1..MAXIMUM_PACKAGE_BYTES.toLong()) {
                "Unzulässige Dateigröße für ${item.path}."
            }
            require(item.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Ungültiger SHA-256-Wert für ${item.path}."
            }
            val entry = zip.getEntry(item.path) ?: error("${item.path} fehlt.")
            require(entry.size < 0 || entry.size == item.byteLength) {
                "Falsche Dateigröße für ${item.path}."
            }
            val result = if (item.path == "manifest.json") {
                StreamDigest(
                    byteLength = manifestBytes.size.toLong(),
                    sha256 = manifestBytes.sha256Hex(),
                    prefix = manifestBytes.copyOfRange(0, minOf(4, manifestBytes.size)),
                )
            } else {
                zip.getInputStream(entry).use { input ->
                    input.copyLimitedAndDigest(null, MAXIMUM_PACKAGE_BYTES)
                }
            }
            require(result.byteLength == item.byteLength) {
                "Falsche Dateigröße für ${item.path}."
            }
            require(result.sha256.equals(item.sha256, ignoreCase = true)) {
                "Falsche Prüfsumme für ${item.path}."
            }
            if (item.path == "assembly.glb") {
                require(result.prefix.contentEquals("glTF".encodeToByteArray())) {
                    "assembly.glb ist keine gültige GLB-Datei."
                }
            }
            if (item.path == manifest.files.registrationModel) {
                require(result.prefix.contentEquals("QSRG".encodeToByteArray())) {
                    "registration_model.bin ist kein gültiges QS-Modell."
                }
            }
        }
    }

    private fun validateInstalledFiles(
        files: Map<String, File>,
        manifest: PackageManifest,
        document: ChecksumDocument,
    ) {
        require(document.schemaVersion.major() == 1 && document.algorithm == "SHA-256") {
            "checksums.json verwendet kein SHA-256 oder keine unterstützte Version."
        }
        val expected = packageEntries(manifest) - "checksums.json"
        require(document.files.map { it.path }.toSet() == expected) {
            "checksums.json deckt das Paket nicht exakt ab."
        }
        document.files.forEach { item ->
            val file = files[item.path] ?: error("${item.path} fehlt.")
            require(file.length() == item.byteLength) { "Falsche Dateigröße für ${item.path}." }
        }
        val glb = files.getValue("assembly.glb").inputStream().use { input ->
            ByteArray(4).also { require(input.read(it) == 4) { "assembly.glb ist leer." } }
        }
        require(glb.contentEquals("glTF".encodeToByteArray())) { "assembly.glb ist keine gültige GLB-Datei." }
        val registrationName = manifest.files.registrationModel
        val magic = files.getValue(registrationName).inputStream().use { input ->
            ByteArray(4).also { require(input.read(it) == 4) { "$registrationName ist leer." } }
        }
        require(magic.contentEquals("QSRG".encodeToByteArray())) {
            "$registrationName ist kein gültiges QS-Modell."
        }
    }

    private fun validateReferences(
        manifest: PackageManifest,
        parts: PartDocument,
    ) {
        require(parts.schemaVersion.major() == 1) { "parts.json verwendet nicht den Vertrag 1.x." }
        require(parts.assemblyGuid == manifest.assembly.guid) {
            "parts.json gehört nicht zur Baugruppe des Pakets."
        }
        require(parts.parts.isNotEmpty()) { "Die Baugruppe enthält keine Bauteile." }
    }

    private fun estimateRequiredBytes(archiveBytes: Long, bundle: ProjectBundleManifest): Long {
        val packageBytes = bundle.packages.sumOf(ProjectBundleEntry::byteLength)
        val fileOverhead = bundle.packages.size.toLong() * FILESYSTEM_BLOCK_BYTES
        return archiveBytes + packageBytes + fileOverhead + IMPORT_STORAGE_MARGIN_BYTES
    }

    private fun archiveFile(packageId: String) = File(archivesRoot, "$packageId.qsxr")

    private fun File.readSmallJson(): ByteArray {
        require(length() in 1..MAXIMUM_PACKAGE_METADATA_BYTES.toLong()) {
            "$name überschreitet die zulässige Größe für Metadaten."
        }
        return readBytes()
    }

    private fun ZipFile.readEntry(name: String, limit: Int): ByteArray {
        val entry = getEntry(name) ?: error("$name fehlt.")
        return getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "$name überschreitet die zulässige Größe." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private suspend fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Eine Projektdatei überschreitet die zulässige Größe." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private inline fun <reified T> decode(bytes: ByteArray): T =
        JsonCodec.value.decodeFromString(bytes.decodeToString())

    private fun String.major() = substringBefore('.').toIntOrNull()

    private data class StreamDigest(
        val byteLength: Long,
        val sha256: String,
        val prefix: ByteArray,
    )

    private fun InputStream.copyLimitedAndDigest(
        output: OutputStream?,
        limit: Int,
        onChunk: (() -> Unit)? = null,
    ): StreamDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(4)
        var prefixLength = 0
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            onChunk?.invoke()
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Eine Datei überschreitet die zulässige Größe." }
            if (prefixLength < prefix.size) {
                val copied = minOf(prefix.size - prefixLength, count)
                buffer.copyInto(prefix, prefixLength, 0, copied)
                prefixLength += copied
            }
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        return StreamDigest(
            byteLength = total,
            sha256 = digest.digest().toHex(),
            prefix = prefix.copyOf(prefixLength),
        )
    }

    private fun ByteArray.sha256Hex() = MessageDigest.getInstance("SHA-256")
        .digest(this).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun File.treeSize(): Long = if (!exists()) {
        0L
    } else {
        walkTopDown().filter(File::isFile).sumOf(File::length)
    }

    private fun deleteTreeIncrementally(root: File, onFileDeleted: () -> Unit) {
        if (root.isDirectory) {
            root.listFiles()?.forEach { child -> deleteTreeIncrementally(child, onFileDeleted) }
        }
        val wasFile = root.isFile
        if (root.delete() && wasFile) onFileDeleted()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    private fun InstalledPackageSummary.positionLabel() =
        "${assembly.positionNumber} · Rev. ${assembly.revision}"

    companion object {
        private const val BUNDLE_MANIFEST = "project_bundle_manifest.json"
        // Assets exist only in src/debug. IDs remain here to remove samples seeded by older releases.
        private val BUNDLED_SAMPLES = listOf(
            BundledSample(
                asset = "bundled/heb140-300.qsxr",
                packageId = "767bc082-0a02-5d83-b00d-178dd58447eb",
                marker = "bundled-heb140-300-v2.installed",
                previousMarkers = listOf("bundled-heb140-300-v1.installed"),
            ),
            BundledSample(
                asset = "bundled/ipe270-200.qsxr",
                packageId = "aa7fa1d2-9b55-570d-bb8a-0526fe6e0661",
                marker = "bundled-ipe270-200-v1.installed",
            ),
            BundledSample(
                asset = "bundled/tv-bank-148x47x57.qsxr",
                packageId = "533159e0-7cc8-5b01-a8fb-dd3f53e4e992",
                marker = "bundled-tv-bank-148-v3.installed",
                previousMarkers = listOf("bundled-tv-bank-148-v1.installed", "bundled-tv-bank-148-v2.installed"),
            ),
        )
        private const val MAXIMUM_PACKAGE_BYTES = 512 * 1024 * 1024
        private const val MAXIMUM_BUNDLE_MANIFEST_BYTES = 64 * 1024 * 1024
        private const val MAXIMUM_PACKAGE_METADATA_BYTES = 16 * 1024 * 1024
        private const val MAXIMUM_PROJECT_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAXIMUM_PROGRESS_UPDATES = 1_000
        private const val MAXIMUM_CATALOG_PROGRESS_UPDATES = 200
        private const val TRANSACTION_PROGRESS_INTERVAL = 250
        private const val CLEANUP_PROGRESS_INTERVAL = 1_000
        private const val FILESYSTEM_BLOCK_BYTES = 4_096L
        private const val IMPORT_STORAGE_MARGIN_BYTES = 64L * 1024L * 1024L
        private const val TRANSACTION_JOURNAL = "package_ids.txt"
        private const val TRANSACTION_PUBLISHING = "publishing.marker"
        private const val TRANSACTION_COMMITTED = "committed.marker"
        private const val OLD_CATALOG = "old_catalog.json"
        private val BASE_PACKAGE_ENTRIES = setOf(
            "manifest.json",
            "assembly.glb",
            "registration_model.bin",
            "parts.json",
            "thumbnail.png",
            "checksums.json",
        )
        private val summaryComparator = compareBy<InstalledPackageSummary> {
            it.project.name.lowercase()
        }.thenBy { it.assembly.positionNumber.lowercase() }
            .thenByDescending { it.assembly.revision }
    }
}
