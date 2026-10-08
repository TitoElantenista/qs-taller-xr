# QS Desktop Viewer

Visor local para consultar las Baugruppen extraídas desde IFC, agrupadas como:

```text
proyecto -> Zeichnungsnummer -> Positionsnummer -> ocurrencia
```

La aplicación acepta el formato canónico `123456.7` y el formato legado de los IFC
de muestra `123456/7(?)`. En ambos casos muestra el dibujo `123456` y la posición `7`.

## Funciones actuales

- catálogo de proyectos y búsqueda por dibujo o posición;
- creación de proyectos e importación de IFC desde el propio frontend;
- actualización de proyectos con detección automática de cambios por Baugruppe;
- historial y selector de revisiones por Positionsnummer;
- orden numérico de dibujos y posiciones;
- selector de ocurrencia física cuando una posición aparece varias veces;
- conversión IFC a GLB bajo demanda y caché local;
- teselación de alta calidad con normales IFC y cantos duros conservados;
- visor 3D con rotación, desplazamiento, zoom, encuadre y vista de aristas;
- gizmo de orientación 3D que identifica X, Y y Z y sigue la cámara;
- estilos `IFC original`, `Acero neutro`, `Contraste por pieza` e `Inspección ámbar`;
- realce `Cavity` mediante oclusión ambiental GTAO, activable independientemente;
- vista de taller con pernos y otros `IfcMechanicalFastener` ocultables;
- clasificación por propiedades Tekla para separar Verbindungsmittel físicos, objetos
  `LOCH`/`ZINC-HOLES`, Pulverlinien y elementos desconocidos;
- reconstrucción offline de Bohrungen redondos y alargados mediante cortes booleanos en
  las mallas, usando `HOLE.DIAMETER_X/Y` antes de cualquier regla normativa;
- normales duras en el borde de las placas y suaves sólo en las paredes de los
  Bohrungen, evitando artefactos triangulares después del corte booleano;
- marcas de fabricación ocultas por defecto y conmutables independientemente;
- inclusión geométrica únicamente de los pernos físicos de otro grupo que conectan una
  pieza de la Baugruppe abierta, confirmada mediante intersección sólida y no sólo por
  cajas envolventes; se diferencian en violeta y muestran su origen en la ficha;
- selección de cada pieza por clic, con resaltado y ficha de atributos IFC;
- lectura de `Part position number` como `Zeichnungsnummer.Partnummer` por nodo GLB;
- medición entre dos puntos de la superficie en milímetros;
- persistencia de cotas por ocurrencia;
- separación de modelos y cotas por revisión;
- borrado de una cota o de todas las cotas de la ocurrencia;
- exportación de cada Baugruppe con un modelo QSRG dedicado al autosnap AR;
- exclusión de Verbindungsmittel del modelo de registro, manteniéndolos en el GLB visual.

`qs-tracking-target` emite conjuntamente `*-tracking.obj`, `*.qsrg`, `*.glb` y
`*-tracking.json`. El JSON schema 2 enlaza esos bytes con el `packageId`, el hash/GUID de la
assembly fuente y el frame/origen `QSXR_ASSEMBLY`; es la única entrada aceptada después por
`qs-pose-target`. El export falla si el QSRG derivado no coincide con el incluido en el QSXR.

Los archivos `.qsproject` se descargan en la carpeta configurada por el navegador
(normalmente `Descargas`). El servidor guarda además la copia de trabajo en
`artifacts/<proyecto>/desktop/mobile-projects/<id>.qsproject`; para transferirla al móvil
debe usarse la descarga del navegador.

La misma operación genera un `.qsgputargets` con OBJ, QSRG, GLB y manifiesto verificado para
cada paquete Android. Si el visor se inició mediante los scripts del PC después de configurar
`secure-single-phone`, esos targets se registran automáticamente en el almacén local del servidor
GPU. La APK y el servidor usan el mismo `packageId` y SHA-256 de `registration_model.bin`, por lo
que el servicio selecciona exactamente la Baugruppe abierta y rechaza revisiones desconocidas.

Los modelos generados y las cotas se guardan en:

```text
artifacts/<proyecto>/desktop/
├── cache/
├── imports/<hash-del-ifc>/
├── index-history/<import-id>.before.json
├── revisions/<positionsnummer>/rev-0001/
├── revisions/<positionsnummer>/rev-0002/
├── project-index.json
└── measurements.json
```

## Proyectos y revisiones desde el frontend

La barra lateral contiene:

- **Nuevo proyecto**: solicita un nombre y el primer IFC.
- **Importar IFC**: añade un IFC nuevo al proyecto seleccionado.
- **Revisiones / historial**: permite deshacer la última importación IFC y volver al
  estado anterior.
- **Borrar proyecto**: elimina definitivamente todos los IFC, revisiones, modelos,
  cotas y exportaciones del proyecto después de confirmar su nombre.

Cada importación calcula una huella estable por Baugruppe usando representación IFC,
propiedades, composición, distancias y orientaciones relativas. La posición global del
modelo y el número de ocurrencias no intervienen, por lo que mover el proyecto o añadir
otra unidad idéntica no crea una revisión falsa.

- Una Positionsnummer nueva comienza en `Rev. 1`.
- Una Positionsnummer sin cambios conserva su revisión actual.
- Una Baugruppe modificada crea `Rev. N + 1` y mantiene las anteriores.
- Una posición eliminada deja de estar activa, pero su historial se conserva.
- El selector **REVISIÓN** permite abrir geometría, ocurrencias y cotas antiguas.

Un IFC de obra completo (unos 60 MB) tarda aproximadamente minuto y medio en una
primera importación en el equipo de desarrollo. El diálogo permanece abierto indicando
que la comparación sigue en curso.

### Validación preventiva

Antes de copiar el IFC o escribir revisiones, el servidor comprueba:

- que existan `IfcElementAssembly`;
- que las Baugruppen conserven `Tag`, `Assembly position number` o `ASSEMBLY_POS`;
- que las posiciones cumplan el formato de negocio;
- que la actualización no haga desaparecer de forma anómala más de la mitad del
  catálogo existente.

Si falla una comprobación, la importación se cancela sin modificar el proyecto. El
diálogo muestra el problema, las cantidades afectadas y la configuración de Tekla que
debe revisarse.

### Deshacer una importación

El botón **Revisiones / historial** muestra los lotes importados. Sólo puede deshacerse el más
reciente, porque un IFC puede crear o retirar revisiones en cientos de posiciones y
eliminar una revisión aislada rompería la coherencia del proyecto.

Al deshacer se restaura el índice anterior y se eliminan los paquetes, cachés y cotas
pertenecientes únicamente a las revisiones descartadas. Para importaciones
nuevas se usa una instantánea exacta; los proyectos antiguos disponen de una migración
compatible basada en su historial conservado.

El primer IFC es el estado inicial y no tiene un estado anterior al que volver. Para
eliminarlo y volver a cargar el proyecto desde cero se debe usar **Borrar proyecto**.

## Indexar un IFC completo

Para que el visor muestre todas las posiciones sin crear cientos de copias IFC
independientes:

```bash
.venv/bin/qs-viewer-index 'docs/IFC/Modul1-4(1).ifc' \
  --project-name Modul1-4 \
  --output artifacts \
  --overwrite
```

El índice conserva una copia de cada IFC diferente por su hash y genera los metadatos
de posiciones, ocurrencias y revisiones. `--overwrite` permite actualizar un proyecto
existente, pero ya no elimina su historial.

Los assemblies cuya marca no cumple `######.n` no se descartan. Se guardan dentro de
`PENDIENTES -> Sin Positionsnummer`, conservando su GUID y su marca provisional de
Tekla (`A.123`, `Q.0(?)`, etc.) en el selector de ocurrencias. Al volver a indexar un
IFC después de numerarlo, pasarán automáticamente a su dibujo y posición definitivos.

## Instalación

Desde la raíz del repositorio:

```bash
python3 -m venv .venv
.venv/bin/pip install -e 'apps/desktop-viewer[dev]'
cd apps/desktop-viewer/frontend
npm install
npm run build
cd ../../..
```

## Ejecución

```bash
.venv/bin/qs-viewer
```

Se abre `http://127.0.0.1:8765` en el navegador predeterminado. Para no abrirlo
automáticamente:

```bash
.venv/bin/qs-viewer --no-browser
```

La primera vez que se abre una ocurrencia se triangula su IFC. Las aperturas siguientes
usan el GLB de caché. La geometría se expresa en metros internamente y las cotas se
calculan y muestran en milímetros.

Cada pieza renderizada conserva en su nodo GLB el `Part position number`, dibujo,
Partnummer, Baugruppe, clase IFC, perfil, material, tipo, fase, comentario y GlobalId
disponibles. Un clic normal abre la ficha; arrastrar sigue rotando el modelo y las
herramientas de cota o marcador tienen prioridad. El property set de Tekla es la fuente
principal: `Tag` sólo se usa como respaldo si cumple exactamente `######.n`, porque en
algunas placas contiene un identificador interno. Varias piezas físicas iguales pueden
compartir el mismo Partnummer; su `GlobalId` permite distinguir cada ocurrencia.

Los selectores de ocurrencia y revisión permanecen desplegables aunque sólo contengan
una opción; en ese caso se muestra explícitamente `única`. Una posición sólo recibe
otra revisión cuando cambia su huella geométrica, por lo que es normal que muchas
posiciones mantengan únicamente `Rev. 1` después de actualizar el proyecto.

La caché está versionada. Cuando mejora el conversor de geometría se crea una nueva
versión automáticamente, por lo que no es necesario borrar manualmente los GLB viejos.

El indexador audita además bolts, soldaduras y openings. Si el IFC se exportó con
`Welds:Off` o contiene bolts sin vacíos de agujero, muestra un aviso en la terminal.
Los objetos que Tekla agrupa bajo `IfcMechanicalFastener` no se aceptan ya como pernos
por su clase: `LOCH` y `ZINC-HOLES` generan cortes, `DSTV/PULVERLINIE` se separa como
marca y sólo estándares físicos reconocidos se muestran como Verbindungsmittel. Si las
dimensiones explícitas no existen, el conversor informa del caso en vez de inventar una
serie de holgura DIN.

## Desarrollo del frontend

En dos terminales:

```bash
.venv/bin/qs-viewer --no-browser
cd apps/desktop-viewer/frontend && npm run dev
```

Vite sirve la interfaz en `http://127.0.0.1:5173` y reenvía `/api` al servidor local.

## Pruebas

```bash
.venv/bin/pytest apps/desktop-viewer/tests
cd apps/desktop-viewer/frontend && npm test
```
