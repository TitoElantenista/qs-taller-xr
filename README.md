# QS Taller XR

Android-App für die visuelle Qualitätsprüfung von Stahlbau-Baugruppen: das CAD-Modell in 3D ansehen, per
Augmented Reality über das reale Bauteil legen und die Prüfung mit Status, Fotos und PDF-Bericht
dokumentieren. Die App arbeitet vollständig offline – sie hat keine Internet-Berechtigung, alle Modelle und
Prüfakten bleiben auf dem Gerät.

*English: Android app for visual quality inspection of steel assemblies. It shows the CAD model in 3D,
overlays it on the real part in AR (aligned through corners the operator picks in the model and marks in the
camera) and records the inspection with status, photos and PDF reports. Fully offline, no network permission.*

## Inhalt des Repositorys

| Ordner | Inhalt |
|---|---|
| `apps/mobile-android` | Die Android-App (Kotlin, Jetpack Compose, SceneView/Filament, Google ARCore). IFC-Dateien werden auf dem Telefon mit web-ifc in GLB umgewandelt. |
| `apps/desktop-viewer` | PC-Viewer (Python/FastAPI und Web-Frontend): IFC-Projekte einlesen, Baugruppen ansehen und vermessen, `.qsproject`-Dateien für die App exportieren. |
| `packages/xr-contracts` | JSON-Schemas und Beispiele des Paketformats, das PC-Viewer und App teilen. |

Nicht enthalten sind Kundenmodelle, Fotos, Signaturschlüssel, Anbieterdaten, die interne
Entwicklungsdokumentation und das Huawei AR Engine SDK.

## Android-App bauen

Voraussetzungen: JDK 21 und Android SDK (Plattform 36).

```bash
cd apps/mobile-android
./gradlew testDebugUnitTest assembleDebug
```

- Debug-Builds enthalten Demo-Modelle und die experimentelle Huawei-AR-Unterstützung; das Huawei AR Engine
  SDK lädt Gradle dafür aus dem Maven-Repository von Huawei.
- Release-Builds (`./build-release-bundle.sh`) brauchen `keystore.properties` und `legal.properties`; Vorlagen:
  `keystore.properties.example`, `legal.properties.example`.
- Einige Tests nutzen Dateien aus Kundenmodellen, die hier nicht veröffentlicht sind; sie werden ohne diese
  Dateien übersprungen.

## Lizenz

Der Quellcode ist zur Einsicht veröffentlicht; für den eigenen Code wird derzeit keine Open-Source-Lizenz
erteilt, alle Rechte bleiben vorbehalten. Bibliotheken von Drittanbietern stehen unter ihren eigenen
Lizenzen; die Texte liegen in `apps/mobile-android/app/src/main/assets/legal/` und sind in der App unter
«Über die App → Open-Source-Lizenzen» einsehbar.
