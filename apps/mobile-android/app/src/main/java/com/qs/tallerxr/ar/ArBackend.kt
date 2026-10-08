package com.qs.tallerxr.ar

internal enum class ArBackend(val label: String) {
    GOOGLE("Google ARCore"), HUAWEI("Huawei AR Engine"),
}

internal enum class ArServiceState { READY, MISSING, UPDATE_REQUIRED, UNSUPPORTED, CHECKING, ERROR, DISABLED }

internal data class ArAvailability(
    val google: ArServiceState,
    val huawei: ArServiceState,
) {
    val candidates: List<ArBackend>
        get() = buildList {
            if (google == ArServiceState.READY) add(ArBackend.GOOGLE)
            if (huawei == ArServiceState.READY) add(ArBackend.HUAWEI)
        }

    fun unavailableMessage(): String =
        "Kein einsatzbereites AR-System verfügbar. Der 3D-Modus bleibt nutzbar.\n\n" +
            "Google ARCore: ${google.description()}\nHuawei AR Engine: ${huawei.description()}"
}

private fun ArServiceState.description(): String = when (this) {
    ArServiceState.READY -> "installiert; Geräteprüfung beim Start"
    ArServiceState.MISSING -> "nicht installiert"
    ArServiceState.UPDATE_REQUIRED -> "Aktualisierung erforderlich"
    ArServiceState.UNSUPPORTED -> "Gerät nicht unterstützt"
    ArServiceState.CHECKING -> "Prüfung noch offen; bitte erneut versuchen"
    ArServiceState.ERROR -> "Verfügbarkeit konnte nicht geprüft werden"
    ArServiceState.DISABLED -> "in dieser Version deaktiviert"
}
