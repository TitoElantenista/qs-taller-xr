# Dependencias y condiciones de terceros

Este documento es informativo y no sustituye una revisión jurídica de la empresa.

- **SceneView 4.18.0**: Apache License 2.0. Código y licencia:
  <https://github.com/SceneView/sceneview>.
- **Google Filament 1.71.5**: Apache License 2.0. Código y licencia:
  <https://github.com/google/filament>.
- **Jetpack Compose y AndroidX**: componentes de Android Open Source Project, en su
  mayoría Apache License 2.0; deben conservarse los avisos publicados con cada artefacto.
- **ARCore 1.54.0 / Google Play Services for AR**: software y servicio de Google sujeto
  a los Google APIs Terms y a los ARCore Additional Terms. La app debe informar a sus
  usuarios de que incluye ARCore y enlazar los términos y la política de privacidad de
  Google. El texto exigido aparece en la pantalla de inicio, en «Über die App», en la
  Datenschutzerklärung y en «Open-Source-Lizenzen».
- **Android SDK**: sujeto al Android Software Development Kit License Agreement. La
  licencia publicada permite usar el SDK para desarrollar aplicaciones destinadas a
  implementaciones Android compatibles, bajo sus condiciones.
Este MVP no activa Cloud Anchors, Geospatial API ni otros servicios cloud de ARCore.

## web-ifc

- Uso: lectura de archivos IFC en el teléfono (geometría, cortes booleanos, propiedades).
- Versión: 0.0.77, sin modificar (`app/src/main/assets/ifc/web-ifc-api-iife.js`, `web-ifc.wasm`).
- Licencia: Mozilla Public License 2.0. Copia en `app/src/main/assets/legal/mpl-2.0.txt`, visible en la app en «Open-Source-Lizenzen».
- Origen: https://github.com/ThatOpen/engine_web-ifc
