# Pruebas del lector IFC del teléfono

La app lee IFC con [web-ifc](https://github.com/ThatOpen/engine_web-ifc) (MPL-2.0) dentro de un
WebView offline. El código propio está en `app/src/main/assets/ifc/`:

- `qs-ifc-converter.js`: IFC → GLB (metros, Y arriba, un nodo por producto IFC) + lista de piezas;
- `converter.html`: página que ejecuta el trabajo y devuelve el resultado a Kotlin (`IfcConverter`);
- `web-ifc-api-iife.js`, `web-ifc.wasm`: web-ifc 0.0.77 sin modificar (licencia en `assets/legal/mpl-2.0.txt`).

Las mismas fuentes se pueden probar en el PC, sin teléfono:

```bash
npm install web-ifc@0.0.77            # sólo para las pruebas; la APK usa su copia de assets
# 1. Núcleo del conversor bajo Node: escribe <nombre>.glb y <nombre>.parts.json
node run-converter.cjs pieza.ifc otra.ifc
# 2. Página real en Chromium sin interfaz, servida como lo hace IfcConverter.shouldInterceptRequest
node browser-test.cjs pieza.ifc
# 3. Generar IFC de una sola Baugruppe a partir de un IFC de obra completo (requiere ifcopenshell)
python extract-assembly.py /ruta/a/obra-completa.ifc
```

`app/src/test/resources/importer/traeger.*` se generó así a partir de un IFC de obra real y alimenta
`ModelPreparationTest`. Por proceder de un modelo de cliente no está en el repositorio público: sin
esos archivos, las pruebas que los usan se saltan.
