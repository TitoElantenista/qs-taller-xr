# QS Taller XR – Android-App

Kotlin, Jetpack Compose, SceneView 4.18 (Filament) und Google ARCore. Android 9+ (API 28), ARM64, targetSdk 36.
Die App hat keine Internet-Berechtigung.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `app/src/main/java/com/qs/tallerxr/projects` | Projekte aus verknüpften Ordnern (Storage Access Framework), Abgleich, Ebenen |
| `app/src/main/java/com/qs/tallerxr/importer` | GLB-Leser, IFC-Umwandlung auf dem Telefon (web-ifc in einer WebView), Referenzmodell für die Ausrichtung |
| `app/src/main/java/com/qs/tallerxr/ar` | Punktausrichtung: wählbare Ecken und Bohrungsmitten, Sichtbarkeit, Löser über mehrere Ansichten |
| `app/src/main/java/com/qs/tallerxr/quality` | Qualitätsakten, Verlauf, archivierte PDF-Prüfberichte |
| `app/src/main/java/com/qs/tallerxr/ui` | Bildschirme: Start, Projekt, 3D-Viewer, AR, Qualitätssicherung, Hilfe |
| `app/src/debug` | Nur Debug: Demo-Modelle und experimentelle Huawei-AR-Unterstützung |
| `tools` | Hilfsskripte (IFC-Konverter-Tests, Layoutvorschau der Berichte, Paketprüfung) |

## Bauen und testen

```bash
./gradlew testDebugUnitTest assembleDebug
```

JDK 21 und Android SDK (Plattform 36) werden benötigt. Das Huawei AR Engine SDK der Debug-Variante lädt Gradle
aus dem Maven-Repository von Huawei. Einige Tests verwenden Dateien aus Kundenmodellen, die nicht veröffentlicht
sind; ohne sie werden diese Tests übersprungen.

Release-Builds (`./build-release-bundle.sh`) benötigen `keystore.properties` und `legal.properties`, siehe
`keystore.properties.example` und `legal.properties.example`.

Lizenzhinweise der verwendeten Bibliotheken: `THIRD_PARTY_NOTICES.md` und `app/src/main/assets/legal/`.
