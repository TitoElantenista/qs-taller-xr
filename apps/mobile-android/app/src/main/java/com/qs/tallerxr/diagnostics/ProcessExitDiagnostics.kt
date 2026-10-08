package com.qs.tallerxr.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.qs.tallerxr.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

internal data class PreviousExitDiagnostic(
    val summary: String,
    val details: String,
    val exitInfo: ApplicationExitInfo,
    val identity: String,
)

internal data class ArchivedExitDiagnostic(val id: String, val title: String, val details: String)

/**
 * Keeps the last two coarse application transitions in Android's process-exit record and archives
 * any system ANR/native trace on the following launch. This intentionally does not guess which
 * native library crashed: only a tombstone backtrace can distinguish Filament, ARCore, a graphics
 * driver or another native dependency.
 */
internal object ProcessExitDiagnostics {
    @Volatile
    private var currentState = "process:start"

    @Volatile
    private var previousState: String? = null

    @Volatile
    private var javaHandlerInstalled = false

    fun installJavaCrashHandler(context: Context) {
        if (javaHandlerInstalled) return
        synchronized(this) {
            if (javaHandlerInstalled) return
            val applicationContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    archiveJavaCrash(
                        context = applicationContext,
                        thread = thread,
                        error = error,
                    )
                }
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, error)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
            }
            javaHandlerInstalled = true
        }
    }

    @Synchronized
    fun recordState(context: Context, state: String) {
        val normalized = normalizeState(state)
        if (normalized == currentState) return
        previousState = currentState
        currentState = normalized
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val summary = compactProcessState(previousState, currentState)
        // Android can throttle excessive calls. State updates happen only at coarse transitions,
        // and diagnostics must never make a healthy process fail.
        runCatching { activityManager.setProcessStateSummary(summary) }
    }

    fun readPreviousExit(application: Application): PreviousExitDiagnostic? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = application.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching {
            activityManager.getHistoricalProcessExitReasons(application.packageName, 0, 1)
                .firstOrNull()
        }.getOrNull() ?: return null
        val reason = classifyExitReason(exit.reason, exit.status) ?: return null
        val identity = processExitIdentity(
            timestamp = exit.timestamp,
            pid = exit.pid,
            reason = exit.reason,
            status = exit.status,
        )
        val handledIdentity = diagnosticsPreferences(application)
            .getString(LAST_HANDLED_EXIT_ID, null)
        if (!shouldReportProcessExit(identity, handledIdentity)) return null
        if (isRoutineBackgroundExit(exit.reason, exit.status, exit.importance)) {
            // Android reclaiming memory from an app in the background is normal, not a fault.
            diagnosticsPreferences(application).edit()
                .putString(LAST_HANDLED_EXIT_ID, identity)
                .putLong(LAST_HANDLED_EXIT_TIMESTAMP, exit.timestamp)
                .apply()
            return null
        }
        val time = Instant.ofEpochMilli(exit.timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z"))
        val state = decodeProcessState(exit.processStateSummary)?.let { " · Zustand: $it" }.orEmpty()
        val signal = exit.status.takeIf { it != 0 }?.let { " · Status/Signal $it" }.orEmpty()
        return PreviousExitDiagnostic(
            summary = processExitSummary(exit.reason, exit.status, time),
            details = "$reason · $time$signal$state · ID ${exit.timestamp}.",
            exitInfo = exit,
            identity = identity,
        )
    }

    fun archivePreviousExit(application: Application, diagnostic: PreviousExitDiagnostic): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val exit = diagnostic.exitInfo
        val folder = diagnosticsFolder(application)
        val baseName = "exit-${exit.timestamp}"
        val traceFile = runCatching {
            exit.traceInputStream?.use { input ->
                val suffix = when {
                    exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> ".tombstone.pb"
                    exit.reason == ApplicationExitInfo.REASON_ANR -> ".anr.txt"
                    else -> ".trace.bin"
                }
                File(folder, baseName + suffix).also { output ->
                    output.outputStream().buffered().use { stream ->
                        copyLimited(input, stream, MAXIMUM_TRACE_BYTES)
                    }
                }
            }
        }.getOrNull()
        val metadata = File(folder, "$baseName.txt")
        runCatching {
            metadata.writeText(
                buildString {
                    appendLine("QS Taller XR process-exit diagnostic")
                    // The historical exit may predate an update. This is the version reading it.
                    appendLine("archivedByApp=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}")
                    appendLine("timestamp=${exit.timestamp}")
                    appendLine("reason=${exit.reason}; label=${classifyExitReason(exit.reason, exit.status)}")
                    appendLine("status=${exit.status}; pid=${exit.pid}; process=${exit.processName}")
                    appendLine("importance=${exit.importance}; pssKb=${exit.pss}; rssKb=${exit.rss}")
                    appendLine("description=${exit.description.orEmpty()}")
                    appendLine("state=${decodeProcessState(exit.processStateSummary).orEmpty()}")
                    appendLine("systemTrace=${traceFile?.name.orEmpty()}")
                },
            )
            pruneDiagnostics(folder)
        }.getOrElse { return null }
        return metadata
    }

    /**
     * Archives the previous process exit and marks it handled only after its metadata was written.
     * A failed archive deliberately remains pending so the next launch can retry it.
     */
    fun archivePreviousExitAndMarkHandled(
        application: Application,
        diagnostic: PreviousExitDiagnostic,
    ): File? {
        val archived = archivePreviousExit(application, diagnostic) ?: return null
        markPreviousExitHandled(application, diagnostic)
        return archived
    }

    @Synchronized
    fun markPreviousExitHandled(
        context: Context,
        diagnostic: PreviousExitDiagnostic,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return diagnosticsPreferences(context).edit()
            .putString(LAST_HANDLED_EXIT_ID, diagnostic.identity)
            .putLong(LAST_HANDLED_EXIT_TIMESTAMP, diagnostic.exitInfo.timestamp)
            .commit()
    }

    internal fun diagnosticsFolder(context: Context): File =
        File(context.filesDir, DIAGNOSTICS_FOLDER).apply { mkdirs() }

    /** Read only our small metadata files; native traces stay in the archive for diagnosis. */
    fun readArchivedExits(context: Context): List<ArchivedExitDiagnostic> =
        diagnosticsFolder(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("exit-\\d+\\.txt")) && it.length() <= 64 * 1024 }
            .sortedByDescending { it.name.removePrefix("exit-").removeSuffix(".txt").toLongOrNull() ?: 0L }
            .take(MAXIMUM_DIAGNOSTIC_FILES)
            .mapNotNull { file ->
                runCatching {
                    val timestamp = file.name.removePrefix("exit-").removeSuffix(".txt").toLong()
                    val title = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z"))
                    ArchivedExitDiagnostic(file.name, title, file.readText())
                }.getOrNull()
            }

    private fun diagnosticsPreferences(context: Context) = context.getSharedPreferences(
        DIAGNOSTICS_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun archiveJavaCrash(context: Context, thread: Thread, error: Throwable) {
        val folder = diagnosticsFolder(context)
        val timestamp = System.currentTimeMillis()
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(folder, "java-crash-$timestamp.txt").writeText(
            buildString {
                appendLine("QS Taller XR uncaught Java/Kotlin exception")
                appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}")
                appendLine("timestamp=$timestamp; thread=${thread.name}")
                appendLine("state=${compactProcessState(previousState, currentState).toString(Charsets.UTF_8)}")
                append(stack)
            },
        )
        pruneDiagnostics(folder)
    }

    private fun pruneDiagnostics(folder: File) {
        folder.listFiles()?.sortedByDescending(File::lastModified)?.drop(MAXIMUM_DIAGNOSTIC_FILES)
            ?.forEach { it.delete() }
    }
}

internal fun processExitIdentity(
    timestamp: Long,
    pid: Int,
    reason: Int,
    status: Int,
): String = "v1:$timestamp:$pid:$reason:$status"

internal fun shouldReportProcessExit(candidateIdentity: String, handledIdentity: String?): Boolean =
    candidateIdentity != handledIdentity

/**
 * The system ended the app while it was in the background (cached, not visible) to free memory: a
 * low-memory kill or a plain SIGKILL.  Normal on Android and not worth a notice or an archive entry.
 */
internal fun isRoutineBackgroundExit(reason: Int, status: Int, importance: Int): Boolean =
    importance >= BACKGROUND_IMPORTANCE && (reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
        (reason == ApplicationExitInfo.REASON_SIGNALED && status == 9))

/** ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED: no activity visible, kept for reuse. */
private const val BACKGROUND_IMPORTANCE = 400

internal fun processExitSummary(reason: Int, status: Int, time: String): String = when (reason) {
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
        "Frühere Sitzung am $time: Android hat die App wegen hoher Ressourcennutzung beendet."
    ApplicationExitInfo.REASON_LOW_MEMORY ->
        "Frühere Sitzung am $time: Android hat die App wegen zu wenig Arbeitsspeicher beendet."
    else -> "Frühere Sitzung am $time: ${classifyExitReason(reason, status) ?: "Beendigung durch das System"}."
}

internal fun classifyExitReason(reason: Int, status: Int): String? = when (reason) {
    ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin-Absturz"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "nativer Absturz (Ursache noch unbekannt)"
    ApplicationExitInfo.REASON_ANR -> "App reagierte nicht (ANR)"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "zu wenig Arbeitsspeicher"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "übermäßige Ressourcennutzung"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Fehler beim Start des Prozesses"
    ApplicationExitInfo.REASON_SIGNALED -> when (status) {
        9 -> "Prozess durch SIGKILL beendet (vermutlich Speicher oder System)"
        6 -> "Prozess durch SIGABRT beendet"
        11 -> "Prozess durch SIGSEGV beendet"
        else -> "Prozess durch Signal $status beendet"
    }
    else -> null
}

internal fun compactProcessState(previous: String?, current: String): ByteArray {
    val normalizedCurrent = normalizeState(current)
    val normalizedPrevious = previous?.let(::normalizeState)
    val full = if (normalizedPrevious == null) {
        "v1|now=$normalizedCurrent"
    } else {
        "v1|prev=$normalizedPrevious|now=$normalizedCurrent"
    }
    if (full.toByteArray(Charsets.UTF_8).size <= MAXIMUM_PROCESS_STATE_BYTES) {
        return full.toByteArray(Charsets.UTF_8)
    }
    val currentOnly = "v1|now=$normalizedCurrent"
    return utf8Prefix(currentOnly, MAXIMUM_PROCESS_STATE_BYTES)
}

private fun normalizeState(value: String): String = value.trim()
    .replace(Regex("[\\r\\n\\t|]+"), " ")
    .replace(Regex("\\s{2,}"), " ")
    .ifEmpty { "unknown" }

private fun decodeProcessState(value: ByteArray?): String? = value
    ?.takeIf(ByteArray::isNotEmpty)
    ?.toString(Charsets.UTF_8)
    ?.replace(Regex("[\\r\\n\\t]+"), " ")
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun utf8Prefix(value: String, maximumBytes: Int): ByteArray {
    var end = value.length
    while (end > 0) {
        val bytes = value.substring(0, end).toByteArray(Charsets.UTF_8)
        if (bytes.size <= maximumBytes) return bytes
        end -= 1
    }
    return ByteArray(0)
}

private fun copyLimited(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maximumBytes: Long,
) {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (copied < maximumBytes) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maximumBytes - copied).toInt())
        if (count < 0) return
        output.write(buffer, 0, count)
        copied += count
    }
}

private const val DIAGNOSTICS_FOLDER = "diagnostics"
private const val DIAGNOSTICS_PREFERENCES = "process-exit-diagnostics"
private const val LAST_HANDLED_EXIT_ID = "last-handled-exit-id"
private const val LAST_HANDLED_EXIT_TIMESTAMP = "last-handled-exit-timestamp"
private const val MAXIMUM_PROCESS_STATE_BYTES = 128
private const val MAXIMUM_TRACE_BYTES = 16L * 1024L * 1024L
private const val MAXIMUM_DIAGNOSTIC_FILES = 12
