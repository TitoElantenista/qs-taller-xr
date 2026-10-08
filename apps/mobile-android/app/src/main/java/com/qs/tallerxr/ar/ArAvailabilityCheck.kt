package com.qs.tallerxr.ar

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.qs.tallerxr.BuildConfig
import kotlinx.coroutines.delay

internal data class ArBackendProbe(val backend: ArBackend?, val error: String? = null)

/** Google can fall back to Huawei. Huawei validates its actual rendering session on entry. */
internal fun probeArBackend(context: Context, candidates: List<ArBackend>): ArBackendProbe {
    val failures = mutableListOf<String>()
    for (backend in candidates) {
        try {
            when (backend) {
                ArBackend.GOOGLE -> com.google.ar.core.Session(context).close()
                ArBackend.HUAWEI -> {
                    check(BuildConfig.HUAWEI_AR_ENABLED) { "Huawei AR ist in dieser Ausgabe nicht enthalten." }
                    // Creating, configuring and stopping a throwaway Huawei session here made
                    // entry initialise the native tracker twice. Validate the real session in
                    // HuaweiArController instead; failure still returns a visible AR error.
                    Log.i("QsAr", "Selected Huawei; validating configuration in the rendering session")
                    return ArBackendProbe(backend)
                }
            }
            Log.i("QsAr", "Selected ${backend.label}; native session probe succeeded")
            return ArBackendProbe(backend)
        } catch (error: Exception) {
            Log.w("QsAr", "${backend.label} session probe failed", error)
            failures += arFailureMessage(backend, error)
        } catch (error: LinkageError) {
            Log.w("QsAr", "${backend.label} native library failed", error)
            failures += arFailureMessage(backend, error)
        }
    }
    return ArBackendProbe(null, "AR kann auf diesem Gerät nicht gestartet werden.\n\n" +
        failures.joinToString("\n\n") + "\n\nDer 3D-Modus bleibt nutzbar.")
}

internal fun arFailureMessage(backend: ArBackend, error: Throwable): String {
    val kind = error.javaClass.simpleName
    val reason = when {
        kind.contains("NotSupported", true) || kind.contains("NotCompatible", true) ||
            kind.contains("Unsupported", true) || kind.contains("NotCapable", true) ->
            "Installiert, aber räumliches AR-Tracking wird auf diesem Gerät nicht unterstützt."
        kind.contains("TooOld", true) -> "Der AR-Dienst muss aktualisiert werden."
        kind.contains("NotInstalled", true) -> "Der AR-Dienst ist nicht installiert."
        kind.contains("CameraNotAvailable", true) -> "Die Kamera ist nicht verfügbar. Andere Kamera-Apps schließen und erneut versuchen."
        error is SecurityException -> "Der Kamerazugriff wurde verweigert."
        error is LinkageError -> "Die native AR-Bibliothek kann auf diesem System nicht geladen werden."
        else -> error.message?.take(240) ?: "Initialisierung fehlgeschlagen."
    }
    return "${backend.label}: $reason"
}

/** Called only when the operator requests AR; never initialises Huawei on ordinary app startup. */
internal suspend fun checkArAvailability(context: Context): ArAvailability {
    var google = googleState(context)
    // ARCore can initially return UNKNOWN_CHECKING. Do not mislabel that as unsupported.
    repeat(8) {
        if (google == ArServiceState.CHECKING) {
            delay(250)
            google = googleState(context)
        }
    }
    val huawei = if (!BuildConfig.HUAWEI_AR_ENABLED) ArServiceState.DISABLED else huaweiServiceState(context)
    return ArAvailability(google, huawei).also { Log.i("QsAr", "AR availability: $it") }
}

private fun googleState(context: Context): ArServiceState = try {
    // Avoid waiting for Google availability checks on tablets with no ARCore package at all.
    context.packageManager.getPackageInfo("com.google.ar.core", 0)
    when (ArCoreApk.getInstance().checkAvailability(context)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArServiceState.READY
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArServiceState.UPDATE_REQUIRED
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArServiceState.MISSING
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArServiceState.UNSUPPORTED
        ArCoreApk.Availability.UNKNOWN_CHECKING -> ArServiceState.CHECKING
        else -> ArServiceState.ERROR
    }
} catch (_: PackageManager.NameNotFoundException) {
    ArServiceState.MISSING
} catch (error: Exception) {
    Log.w("QsAr", "Google availability failed", error)
    ArServiceState.ERROR
} catch (error: LinkageError) {
    Log.w("QsAr", "Google native SDK unavailable", error)
    ArServiceState.ERROR
}
