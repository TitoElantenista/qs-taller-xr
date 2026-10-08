# Contratos XR compartidos

Fuente de verdad de los formatos intercambiados entre Tekla, el pipeline, el backend y
la app móvil.

## Contenido

- `schemas`: JSON Schema para el manifiesto markerless 2.x, `parts.json`,
  `checksums.json`, protocolos de inspección y paquetes fuente producidos por IFC/Tekla.
- `examples`: paquetes y payloads mínimos válidos usados por pruebas de contrato.
- `tools/build_example_package.py`: genera los binarios mínimos y actualiza checksums.
- `tools/validate_examples.py`: valida schemas, ejemplos y referencias entre archivos.
- `tests`: pruebas automatizadas de los contratos.

Cada formato declara su versión para poder evolucionar los componentes de forma
independiente. Las convenciones espaciales están documentadas en
`docs/adr/0001-coordinate-systems-units-and-transforms.md`.

Cada paquete contiene obligatoriamente `registration_model.bin`: una nube de superficie
QSRG sin Verbindungsmittel, preparada en PC para el autosnap mediante Raw Depth.

## Validación local

Desde la raíz del repositorio:

```bash
python3 -m venv .venv
.venv/bin/pip install -r packages/xr-contracts/requirements-dev.txt
.venv/bin/python packages/xr-contracts/tools/validate_examples.py
.venv/bin/pytest packages/xr-contracts/tests
```

Para reconstruir los archivos binarios y checksums del ejemplo:

```bash
.venv/bin/python packages/xr-contracts/tools/build_example_package.py
```
