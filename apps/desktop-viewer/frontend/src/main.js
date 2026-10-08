import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { CSS2DObject, CSS2DRenderer } from 'three/addons/renderers/CSS2DRenderer.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { GTAOPass } from 'three/addons/postprocessing/GTAOPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import {
  buildFeatureEdges,
  findNearestFeatureEdge,
  findNearestFeatureVertex,
} from './geometry-snap.js';
import './style.css';

const dom = Object.fromEntries(
  [
    'project-select', 'project-summary', 'catalog-search', 'drawing-tree',
    'new-project-button', 'update-project-button', 'history-project-button',
    'delete-project-button',
    'crumb-project', 'crumb-drawing', 'crumb-position', 'occurrence-select',
    'revision-select', 'view-style-select',
    'fit-button', 'wireframe-button', 'cavity-button', 'fasteners-button',
    'fabrication-marks-button',
    'mobile-export-button', 'viewport',
    'viewer-canvas', 'orientation-canvas', 'empty-state',
    'loading-state', 'viewer-meta', 'part-count', 'model-size', 'measure-hint',
    'part-tooltip', 'part-tooltip-close', 'part-tooltip-position',
    'part-tooltip-name', 'part-tooltip-details',
    'measure-hint-text', 'measure-button', 'measurement-count', 'measurement-list',
    'measure-snap-vertex-button', 'measure-snap-edge-button', 'measure-snap-status',
    'delete-all-button', 'measurement-tools',
    'toast',
    'import-dialog', 'import-form', 'import-kicker', 'import-title',
    'import-description', 'project-name-field', 'new-project-name', 'ifc-file',
    'file-name', 'import-progress', 'import-error', 'import-close-button',
    'import-cancel-button', 'import-submit-button',
    'history-dialog', 'history-list', 'history-close-button', 'history-done-button',
    'mobile-export-dialog', 'mobile-export-form', 'mobile-export-complete',
    'mobile-export-drawings', 'mobile-export-assemblies', 'mobile-export-selection-list',
    'mobile-export-progress', 'mobile-export-progress-title',
    'mobile-export-progress-detail', 'mobile-export-progress-bar',
    'mobile-export-error', 'mobile-export-close-button', 'mobile-export-cancel-button',
    'mobile-export-submit-button',
  ].map((id) => [id.replaceAll('-', '_'), document.getElementById(id)]),
);

const state = {
  catalog: null,
  project: null,
  source: null,
  sourceDetail: null,
  occurrence: null,
  revision: 1,
  modelRoot: null,
  modelSize: 1,
  measurements: [],
  measureMode: false,
  measurementSnapMode: 'NONE',
  pendingPoint: null,
  wireframe: false,
  cavity: true,
  visualStyle: 'original',
  fastenersVisible: false,
  fabricationMarksVisible: false,
  totalPartCount: 0,
  fastenerCount: 0,
  fabricationMarkCount: 0,
  externalFastenerCount: 0,
  selectedPart: null,
  selectionOutline: null,
  requestToken: 0,
  importMode: null,
};

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0b1117);
scene.fog = new THREE.FogExp2(0x0b1117, 0.02);

const camera = new THREE.PerspectiveCamera(42, 1, 0.001, 10000);
camera.position.set(2, 1.4, 2.5);

const renderer = new THREE.WebGLRenderer({ canvas: dom.viewer_canvas, antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.12;

const labelRenderer = new CSS2DRenderer();
labelRenderer.domElement.className = 'measurement-label-layer';
dom.viewport.appendChild(labelRenderer.domElement);

const composer = new EffectComposer(renderer);
const renderPass = new RenderPass(scene, camera);
const gtaoPass = new GTAOPass(scene, camera, 512, 512);
gtaoPass.output = GTAOPass.OUTPUT.Default;
gtaoPass.blendIntensity = 0.82;
gtaoPass.updateGtaoMaterial({
  radius: 0.18,
  distanceExponent: 1.35,
  thickness: 1.4,
  distanceFallOff: 1,
  scale: 1,
  samples: 8,
  screenSpaceRadius: true,
});
gtaoPass.updatePdMaterial({ radius: 6, rings: 2, samples: 12, lumaPhi: 10, depthPhi: 2, normalPhi: 3 });
composer.addPass(renderPass);
composer.addPass(gtaoPass);
composer.addPass(new OutputPass());

const orientationScene = new THREE.Scene();
const orientationCamera = new THREE.OrthographicCamera(-1.25, 1.25, 1.25, -1.25, 0.1, 10);
const orientationDirection = new THREE.Vector3();
const orientationRenderer = new THREE.WebGLRenderer({
  canvas: dom.orientation_canvas,
  alpha: true,
  antialias: true,
});
orientationRenderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
orientationRenderer.setClearColor(0x000000, 0);

function makeAxisLabel(label, color) {
  const canvas = document.createElement('canvas');
  canvas.width = 128;
  canvas.height = 128;
  const context = canvas.getContext('2d');
  context.fillStyle = '#0b1117';
  context.beginPath();
  context.arc(64, 64, 42, 0, Math.PI * 2);
  context.fill();
  context.strokeStyle = `#${new THREE.Color(color).getHexString()}`;
  context.lineWidth = 8;
  context.stroke();
  context.fillStyle = '#f5f8fa';
  context.font = '700 60px Inter, Arial, sans-serif';
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  context.fillText(label, 64, 67);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, depthTest: false }));
  sprite.scale.set(0.38, 0.38, 1);
  return sprite;
}

function addOrientationAxis(direction, color, label) {
  const arrow = new THREE.ArrowHelper(direction, new THREE.Vector3(), 0.86, color, 0.22, 0.12);
  orientationScene.add(arrow);
  const sprite = makeAxisLabel(label, color);
  sprite.position.copy(direction).multiplyScalar(1.08);
  orientationScene.add(sprite);
}

orientationScene.add(new THREE.AmbientLight(0xffffff, 1.5));
orientationScene.add(new THREE.Mesh(
  new THREE.SphereGeometry(0.075, 16, 10),
  new THREE.MeshBasicMaterial({ color: 0xd7e1e6 }),
));
addOrientationAxis(new THREE.Vector3(1, 0, 0), 0xea6b64, 'X');
addOrientationAxis(new THREE.Vector3(0, 1, 0), 0x73c48f, 'Y');
addOrientationAxis(new THREE.Vector3(0, 0, 1), 0x70b5e3, 'Z');

const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.075;
controls.screenSpacePanning = true;

scene.add(new THREE.HemisphereLight(0xdceeff, 0x1b252d, 2.25));
const keyLight = new THREE.DirectionalLight(0xffffff, 3.4);
keyLight.position.set(4, 7, 5);
scene.add(keyLight);
const fillLight = new THREE.DirectionalLight(0x6db8df, 1.2);
fillLight.position.set(-5, 2, -3);
scene.add(fillLight);

let grid = null;
const measurementGroup = new THREE.Group();
measurementGroup.name = 'Cotas';
scene.add(measurementGroup);
const measurementSnapPreviewGroup = new THREE.Group();
measurementSnapPreviewGroup.name = 'Previsualización de ajuste de cota';
scene.add(measurementSnapPreviewGroup);
const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();
const loader = new GLTFLoader();
let pointerStart = null;
let measurementSnapPreviewFrame = null;
let measurementSnapPreviewPointer = null;
let measurementSnapPreviewMode = null;
let measurementSnapPreviewTarget = null;
let measurementSnapPreviewEdge = null;
let toastTimer = null;
let cavityRuntimeAvailable = true;

function apiPath(path) {
  return path.split('/').map((part, index) => (index === 0 ? part : encodeURIComponent(part))).join('/');
}

function apiErrorMessage(detail, fallback) {
  if (!detail) return fallback;
  if (typeof detail === 'string') return detail;
  const lines = [];
  if (detail.title) lines.push(detail.title);
  if (detail.message) lines.push(detail.message);
  if (detail.problems?.length) {
    lines.push('', 'Problemas detectados:');
    detail.problems.forEach((item) => lines.push(`• ${item}`));
  }
  if (detail.recommendations?.length) {
    lines.push('', 'Cómo corregirlo:');
    detail.recommendations.forEach((item) => lines.push(`• ${item}`));
  }
  return lines.join('\n') || fallback;
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: options.body ? { 'Content-Type': 'application/json', ...options.headers } : options.headers,
    ...options,
  });
  if (!response.ok) {
    let message = `Error ${response.status}`;
    try {
      const body = await response.json();
      message = apiErrorMessage(body.detail, message);
    } catch { /* The response is not JSON. */ }
    throw new Error(message);
  }
  if (response.status === 204) return null;
  return response.json();
}

function notify(message, kind = 'info') {
  clearTimeout(toastTimer);
  dom.toast.textContent = message;
  dom.toast.dataset.kind = kind;
  dom.toast.classList.add('visible');
  toastTimer = setTimeout(() => dom.toast.classList.remove('visible'), 3200);
}

function sourceBase() {
  return apiPath(`/api/projects/${state.project.id}/sources/${state.source.id}`);
}

function occurrenceBase() {
  return `${sourceBase()}/occurrences/${encodeURIComponent(state.occurrence.sourceGlobalId)}`;
}

function revisionUrl(path) {
  const separator = path.includes('?') ? '&' : '?';
  return `${path}${separator}revision=${state.revision}`;
}

function renderProjectOptions() {
  dom.project_select.replaceChildren();
  for (const project of state.catalog.projects) {
    const option = document.createElement('option');
    option.value = project.id;
    option.textContent = project.name;
    dom.project_select.append(option);
  }
}

function setProject(projectId, preserveSelection = false) {
  state.project = state.catalog.projects.find((project) => project.id === projectId) || null;
  if (!state.project) return;
  dom.project_select.value = state.project.id;
  const audit = state.project.sourceGeometryAudit || {};
  const fabricationSummary = Number.isFinite(Number(audit.physicalFastenerCount))
    ? ` · ${audit.physicalFastenerCount} grupos VM · ${audit.fabricationHoleCount || 0} grupos Bohrung · ${audit.fabricationMarkCount || 0} marcas filtradas`
    : '';
  dom.project_summary.textContent = `${state.project.drawingCount} dibujos · ${state.project.positionCount} posiciones${fabricationSummary}`;
  dom.update_project_button.disabled = false;
  dom.history_project_button.disabled = false;
  dom.delete_project_button.disabled = false;
  dom.mobile_export_button.disabled = false;
  dom.crumb_project.textContent = state.project.name;
  renderTree();
  const available = state.project.drawings.flatMap((drawing) => drawing.positions);
  if (!preserveSelection || !available.some((source) => source.id === state.source?.id)) {
    if (available.length) selectSource(available[0]);
    else clearModel();
  }
}

function matchesSearch(drawing, source, query) {
  if (!query) return true;
  const haystack = `${drawing.number} ${source.position} ${source.positionNumber}`.toLocaleLowerCase('es');
  return haystack.includes(query);
}

function renderTree() {
  dom.drawing_tree.replaceChildren();
  if (!state.project) return;
  const query = dom.catalog_search.value.trim().toLocaleLowerCase('es');

  for (const drawing of state.project.drawings) {
    const positions = drawing.positions.filter((source) => matchesSearch(drawing, source, query));
    if (!positions.length) continue;
    const section = document.createElement('section');
    section.className = 'drawing-group';
    const heading = document.createElement('button');
    heading.className = 'drawing-heading';
    const headingKind = drawing.number === 'PENDIENTES' ? 'TEMPORAL' : 'ZEICHNUNG';
    heading.innerHTML = `<span class="chevron">⌄</span><span><small>${headingKind}</small><strong>${drawing.number}</strong></span><em>${positions.length}</em>`;
    heading.addEventListener('click', () => section.classList.toggle('collapsed'));
    section.append(heading);

    const list = document.createElement('div');
    list.className = 'position-list';
    for (const source of positions) {
      const button = document.createElement('button');
      button.className = `position-item${source.id === state.source?.id ? ' active' : ''}`;
      button.dataset.sourceId = source.id;
      const warning = source.numberingStatus === 'UNASSIGNED'
        ? '<span class="warning-dot unassigned" title="Sin Positionsnummer">!</span>'
        : source.numberingStatus === 'QUESTIONABLE'
          ? '<span class="warning-dot" title="Numeración dudosa">?</span>'
          : '';
      const positionIndex = source.isUnassigned ? '—' : source.position;
      const positionLabel = source.isUnassigned ? source.position : `Pos. ${source.position}`;
      button.innerHTML = `
        <span class="position-index">${positionIndex}</span>
        <span class="position-copy"><strong>${positionLabel}</strong><small>${source.occurrenceCount} ocurrencias · ${source.partCount} piezas · Rev. ${source.revision || 1}</small></span>
        ${warning}<span class="item-arrow">›</span>`;
      button.addEventListener('click', () => selectSource(source));
      list.append(button);
    }
    section.append(list);
    dom.drawing_tree.append(section);
  }

  if (!dom.drawing_tree.children.length) {
    dom.drawing_tree.innerHTML = '<p class="tree-empty">No hay coincidencias.</p>';
  }
}

async function selectSource(source) {
  if (!state.project || (state.source?.id === source.id && state.sourceDetail)) return;
  state.source = source;
  state.sourceDetail = null;
  state.occurrence = null;
  state.revision = source.revision || 1;
  cancelInteraction();
  renderTree();
  dom.crumb_drawing.textContent = source.drawingNumber;
  dom.crumb_position.textContent = source.isUnassigned ? source.position : `Pos. ${source.position}`;
  await loadSourceRevision(null);
}

function revisionLabel(revision) {
  const date = revision.importedAt
    ? new Intl.DateTimeFormat('es-ES', { dateStyle: 'short' }).format(new Date(revision.importedAt))
    : null;
  return `Rev. ${revision.revision}${date ? ` · ${date}` : ''}${revision.sourceFile ? ` · ${revision.sourceFile}` : ''}`;
}

async function loadSourceRevision(revision) {
  dom.occurrence_select.disabled = true;
  dom.occurrence_select.innerHTML = '<option>Cargando…</option>';
  dom.revision_select.disabled = true;
  setLoading(true);
  const token = ++state.requestToken;
  try {
    const detail = await request(
      revision == null ? sourceBase() : `${sourceBase()}?revision=${revision}`,
    );
    if (token !== state.requestToken) return;
    state.sourceDetail = detail;
    state.revision = detail.source.revision || 1;
    dom.revision_select.replaceChildren();
    [...detail.source.revisions]
      .sort((left, right) => right.revision - left.revision)
      .forEach((item) => {
        const option = document.createElement('option');
        option.value = item.revision;
        option.textContent = `${revisionLabel(item)}${detail.source.revisions.length === 1 ? ' · única' : ''}`;
        dom.revision_select.append(option);
      });
    dom.revision_select.value = String(state.revision);
    dom.revision_select.disabled = false;
    dom.revision_select.title = detail.source.revisions.length < 2
      ? 'Esta Baugruppe sólo tiene una revisión'
      : `${detail.source.revisions.length} revisiones disponibles`;
    dom.occurrence_select.replaceChildren();
    detail.occurrences.forEach((occurrence) => {
      const option = document.createElement('option');
      option.value = occurrence.sourceGlobalId;
      const gridPosition = occurrence.gridPosition ? ` · ${occurrence.gridPosition}` : '';
      option.textContent = occurrence.provisionalPositionNumber
        ? `${occurrence.provisionalPositionNumber} · ${occurrence.name} · #${occurrence.index}${gridPosition}`
        : `${occurrence.index}. ${occurrence.name}${gridPosition}`;
      if (detail.occurrences.length === 1) option.textContent += ' · única';
      dom.occurrence_select.append(option);
    });
    dom.occurrence_select.disabled = false;
    dom.occurrence_select.title = detail.occurrences.length < 2
      ? 'Esta posición sólo tiene una ocurrencia'
      : `${detail.occurrences.length} ocurrencias disponibles`;
    if (!detail.occurrences.length) throw new Error('Esta posición no contiene ocurrencias');
    await selectOccurrence(detail.occurrences[0]);
  } catch (error) {
    if (token !== state.requestToken) return;
    setLoading(false);
    showEmpty('No se pudo abrir la Baugruppe', error.message);
    notify(error.message, 'error');
  }
}

async function selectOccurrence(occurrence) {
  clearPartSelection();
  state.occurrence = occurrence;
  dom.occurrence_select.value = occurrence.sourceGlobalId;
  cancelInteraction();
  state.measurements = [];
  renderMeasurements();
  setLoading(true);
  const token = ++state.requestToken;
  try {
    const [gltf, measurementDocument] = await Promise.all([
      loader.loadAsync(revisionUrl(`${occurrenceBase()}/model.glb`)),
      request(revisionUrl(`${occurrenceBase()}/measurements`)),
    ]);
    if (token !== state.requestToken) {
      disposeObject(gltf.scene);
      return;
    }
    installModel(gltf.scene);
    state.measurements = measurementDocument.measurements;
    renderMeasurements();
    setLoading(false);
  } catch (error) {
    if (token !== state.requestToken) return;
    setLoading(false);
    showEmpty('No se pudo generar el modelo 3D', error.message);
    notify(error.message, 'error');
  }
}

function disposeMaterial(material) {
  for (const value of Object.values(material)) {
    if (value?.isTexture) value.dispose();
  }
  material.dispose();
}

function eachMaterial(material, callback) {
  if (Array.isArray(material)) material.forEach(callback);
  else if (material) callback(material);
}

function disposeViewMaterial(material) {
  eachMaterial(material, (item) => item.dispose());
}

function disposeObject(root) {
  root?.traverse((object) => {
    object.geometry?.dispose();
    const original = object.userData?.originalMaterial;
    const generated = object.userData?.viewMaterial;
    if (generated) disposeViewMaterial(generated);
    if (original) eachMaterial(original, disposeMaterial);
    else eachMaterial(object.material, disposeMaterial);
  });
}

function clearPartSelection() {
  state.selectedPart = null;
  if (state.selectionOutline) {
    scene.remove(state.selectionOutline);
    disposeObject(state.selectionOutline);
    state.selectionOutline = null;
  }
  dom.part_tooltip.hidden = true;
}

function partMetadata(object) {
  const hierarchy = [];
  let current = object;
  while (current && current !== state.modelRoot?.parent) {
    hierarchy.unshift(current.userData || {});
    if (current === state.modelRoot) break;
    current = current.parent;
  }
  return Object.assign({}, ...hierarchy);
}

function addPartDetail(label, value) {
  if (value == null || String(value).trim() === '') return;
  const term = document.createElement('dt');
  term.textContent = label;
  const description = document.createElement('dd');
  description.textContent = String(value);
  dom.part_tooltip_details.append(term, description);
}

function samePartAttribute(left, right) {
  if (left == null || right == null) return false;
  return String(left).trim().toLocaleLowerCase() === String(right).trim().toLocaleLowerCase();
}

function formatPartMetric(value, unit, maximumFractionDigits = 3) {
  if (value == null || String(value).trim() === '') return null;
  const number = Number(String(value).replace(',', '.'));
  if (!Number.isFinite(number)) return String(value);
  return `${new Intl.NumberFormat('es-ES', { maximumFractionDigits }).format(number)} ${unit}`;
}

function positionPartTooltip(event) {
  const viewport = dom.viewport.getBoundingClientRect();
  const tooltip = dom.part_tooltip;
  const clickX = event.clientX - viewport.left;
  const clickY = event.clientY - viewport.top;
  const gap = 16;
  const margin = 12;
  let left = clickX + gap;
  let top = clickY + gap;
  if (left + tooltip.offsetWidth > viewport.width - margin) {
    left = clickX - tooltip.offsetWidth - gap;
  }
  if (top + tooltip.offsetHeight > viewport.height - margin) {
    top = clickY - tooltip.offsetHeight - gap;
  }
  tooltip.style.left = `${Math.max(margin, Math.min(left, viewport.width - tooltip.offsetWidth - margin))}px`;
  tooltip.style.top = `${Math.max(margin, Math.min(top, viewport.height - tooltip.offsetHeight - margin))}px`;
}

function selectPart(intersection, event) {
  const object = intersection.object;
  if (!object?.isMesh) return;
  clearPartSelection();
  state.selectedPart = object;

  const highlight = new THREE.Group();
  const fill = new THREE.Mesh(
    object.geometry.clone(),
    new THREE.MeshBasicMaterial({
      color: 0x36c8f4,
      transparent: true,
      opacity: 0.18,
      depthTest: true,
      depthWrite: false,
      side: THREE.DoubleSide,
      polygonOffset: true,
      polygonOffsetFactor: -2,
      polygonOffsetUnits: -2,
    }),
  );
  fill.renderOrder = 29;
  highlight.add(fill);
  const edges = new THREE.EdgesGeometry(object.geometry, 12);
  const outline = new THREE.LineSegments(
    edges,
    new THREE.LineBasicMaterial({
      color: 0x75dcff,
      transparent: true,
      opacity: 0.95,
      depthTest: false,
      depthWrite: false,
    }),
  );
  outline.renderOrder = 30;
  highlight.add(outline);
  object.updateWorldMatrix(true, false);
  highlight.matrix.copy(object.matrixWorld);
  highlight.matrixAutoUpdate = false;
  scene.add(highlight);
  state.selectionOutline = highlight;

  const data = partMetadata(object);
  const hasPartPosition = Boolean(data.partPositionNumber);
  dom.part_tooltip_position.textContent = data.partPositionNumber || 'Sin Part position number';
  dom.part_tooltip_position.classList.toggle('missing', !hasPartPosition);
  const titleParts = [data.name || object.name, data.description || data.objectType]
    .filter((value, index, values) => value && values.indexOf(value) === index);
  dom.part_tooltip_name.textContent = titleParts.join(' · ') || 'Elemento IFC';
  dom.part_tooltip_details.replaceChildren();
  addPartDetail('Zeichnung', data.partDrawingNumber);
  addPartDetail('Partnummer', data.partNumber);
  addPartDetail('Baugruppe', data.assemblyPositionNumber);
  if (data.externalFastener === 'true' || data.externalFastener === true) {
    addPartDetail('Conexión', 'Perno conectado desde otra Baugruppe');
    addPartDetail('Baugruppe propietaria', data.ownerAssemblyPositionNumber);
    addPartDetail('Nombre Baugruppe propietaria', data.ownerAssemblyName);
    if (data.connectedFastenerItemCount && data.fastenerItemCount) {
      addPartDetail(
        'Pernos mostrados del grupo',
        `${data.connectedFastenerItemCount} de ${data.fastenerItemCount}`,
      );
    }
  }
  addPartDetail('Clase IFC', data.ifcClass);
  addPartDetail('Perfil', data.profile);
  addPartDetail('Material', data.material);
  addPartDetail('Peso', formatPartMetric(data.weightKg, 'kg'));
  addPartDetail('Superficie', formatPartMetric(data.surfaceAreaM2, 'm²'));
  const type = data.typeName || data.objectType;
  if (!samePartAttribute(data.profile, type)) addPartDetail('Tipo', type);
  addPartDetail('Fase', data.phase);
  addPartDetail('Clase Tekla', data.classAttribute);
  addPartDetail('Comentario', data.comment);
  addPartDetail('GlobalId IFC', data.sourceGlobalId);
  dom.part_tooltip.hidden = false;
  positionPartTooltip(event);
}

function clearModel() {
  clearPartSelection();
  stopMeasurementSnapPreview();
  if (state.modelRoot) {
    scene.remove(state.modelRoot);
    disposeObject(state.modelRoot);
    state.modelRoot = null;
  }
  clearMeasurementGraphics();
  if (grid) {
    scene.remove(grid);
    grid.geometry.dispose();
    grid.material.dispose();
    grid = null;
  }
  dom.viewer_meta.hidden = true;
  dom.measure_button.disabled = true;
  dom.measure_snap_vertex_button.disabled = true;
  dom.measure_snap_edge_button.disabled = true;
  dom.mobile_export_button.disabled = !state.project;
}

function isWorkshopFastener(object) {
  const category = object.userData?.fastenerCategory;
  if (category) return category === 'PHYSICAL_FASTENER';
  const ifcClass = object.userData?.ifcClass || '';
  const name = object.name.toLocaleLowerCase('de');
  if (ifcClass === 'IfcMechanicalFastener') return true;
  if (ifcClass !== 'IfcFastener') return false;
  if (name.includes('weld') || name.includes('schweiß') || name.includes('schweiss')) return false;
  return /(bolt|screw|nut|washer|schraub|mutter|bolzen|anker|duebel|dübel)/i.test(name);
}

function isFabricationMark(object) {
  return object.userData?.fastenerCategory === 'FABRICATION_MARK';
}

function isExternalConnectedFastener(object) {
  return object.userData?.externalFastener === 'true'
    || object.userData?.externalFastener === true
    || object.userData?.fastenerScope === 'EXTERNAL_CONNECTED';
}

const partPalette = [
  0x7fc8e8, 0xf1b56b, 0x8ed19a, 0xd59acb,
  0xe4d278, 0x8da9e8, 0xe58b83, 0x9fd4cd,
];

function stringHash(value) {
  let hash = 2166136261;
  for (const character of value) {
    hash ^= character.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function styledMaterial(original, object) {
  const material = original.clone();
  const identity = `${object.userData?.sourceGlobalId || ''}:${object.name}:${object.id}`;
  if (isFabricationMark(object)) {
    material.color?.setHex(0x38c7eb);
    if ('metalness' in material) material.metalness = 0.02;
    if ('roughness' in material) material.roughness = 0.55;
    if (material.emissive) material.emissive.setHex(0x06232b);
  } else if (isExternalConnectedFastener(object)) {
    material.color?.setHex(0xba48eb);
    if ('metalness' in material) material.metalness = 0.12;
    if ('roughness' in material) material.roughness = 0.5;
    if (material.emissive) material.emissive.setHex(0x21072d);
  } else if (state.visualStyle === 'steel') {
    material.color?.setHex(0xaebbc1);
    if ('metalness' in material) material.metalness = 0.22;
    if ('roughness' in material) material.roughness = 0.64;
  } else if (state.visualStyle === 'parts') {
    material.color?.setHex(partPalette[stringHash(identity) % partPalette.length]);
    if ('metalness' in material) material.metalness = 0.08;
    if ('roughness' in material) material.roughness = 0.58;
  } else if (state.visualStyle === 'inspection') {
    material.color?.setHex(object.userData.workshopFastener ? 0x6d7880 : 0xe4ae55);
    if ('metalness' in material) material.metalness = 0.12;
    if ('roughness' in material) material.roughness = 0.7;
    if (material.emissive) material.emissive.setHex(0x1b1004);
  }
  material.side = THREE.DoubleSide;
  material.wireframe = state.wireframe;
  material.transparent = false;
  material.opacity = 1;
  material.depthWrite = true;
  return material;
}

function applyVisualStyle() {
  const backgrounds = {
    original: 0x0b1117,
    steel: 0x0d151b,
    parts: 0x081217,
    inspection: 0x17130d,
  };
  const background = backgrounds[state.visualStyle] ?? backgrounds.original;
  scene.background.setHex(background);
  scene.fog.color.setHex(background);
  if (!state.modelRoot) return;
  state.modelRoot.traverse((object) => {
    if (!object.isMesh || !object.userData.originalMaterial) return;
    if (object.userData.viewMaterial) {
      disposeViewMaterial(object.userData.viewMaterial);
      object.userData.viewMaterial = null;
    }
    if (state.visualStyle === 'original') {
      object.material = object.userData.originalMaterial;
      eachMaterial(object.material, (material) => {
        material.side = THREE.DoubleSide;
        material.wireframe = state.wireframe;
      });
      return;
    }
    const original = object.userData.originalMaterial;
    const generated = Array.isArray(original)
      ? original.map((material) => styledMaterial(material, object))
      : styledMaterial(original, object);
    object.userData.viewMaterial = generated;
    object.material = generated;
  });
}

function visibleModelBox() {
  const box = new THREE.Box3();
  state.modelRoot?.updateWorldMatrix(true, true);
  state.modelRoot?.traverse((object) => {
    if (object.isMesh && object.visible) box.expandByObject(object, true);
  });
  return box;
}

function updateModelMeta() {
  if (!state.modelRoot) return;
  const hiddenCount = state.fastenersVisible ? 0 : state.fastenerCount;
  const hiddenMarks = state.fabricationMarksVisible ? 0 : state.fabricationMarkCount;
  const visibleCount = state.totalPartCount - hiddenCount - hiddenMarks;
  const externalLabel = state.externalFastenerCount
    ? ` · ${state.externalFastenerCount} ${state.externalFastenerCount === 1 ? 'perno externo' : 'pernos externos'}`
    : '';
  const hiddenLabels = [
    hiddenCount ? `${hiddenCount} pernos ocultos` : '',
    hiddenMarks ? `${hiddenMarks} marcas ocultas` : '',
  ].filter(Boolean).join(' · ');
  dom.part_count.textContent = hiddenLabels
    ? `${visibleCount} piezas · ${hiddenLabels}`
    : `${state.totalPartCount} ${state.totalPartCount === 1 ? 'pieza' : 'piezas'}${externalLabel}`;
  const box = visibleModelBox();
  if (!box.isEmpty()) {
    const size = box.getSize(new THREE.Vector3());
    state.modelSize = Math.max(size.x, size.y, size.z, 0.01);
    dom.model_size.textContent = `${formatMm(size.x * 1000)} × ${formatMm(size.y * 1000)} × ${formatMm(size.z * 1000)}`;
  }
}

function applyFastenerVisibility({ refit = false } = {}) {
  if (!state.modelRoot) return;
  state.modelRoot.traverse((object) => {
    if (object.isMesh && object.userData.workshopFastener) {
      object.visible = state.fastenersVisible;
    }
  });
  if (state.selectedPart && !state.selectedPart.visible) clearPartSelection();
  dom.fasteners_button.classList.toggle('active', !state.fastenersVisible);
  dom.fasteners_button.setAttribute('aria-pressed', String(!state.fastenersVisible));
  dom.fasteners_button.title = state.fastenersVisible ? 'Ocultar pernos' : 'Mostrar pernos';
  dom.fasteners_button.setAttribute('aria-label', dom.fasteners_button.title);
  updateModelMeta();
  if (refit) fitCamera();
}

function applyFabricationMarkVisibility({ refit = false } = {}) {
  if (!state.modelRoot) return;
  state.modelRoot.traverse((object) => {
    if (object.isMesh && object.userData.fabricationMark) {
      object.visible = state.fabricationMarksVisible;
    }
  });
  if (state.selectedPart && !state.selectedPart.visible) clearPartSelection();
  dom.fabrication_marks_button.classList.toggle('active', !state.fabricationMarksVisible);
  dom.fabrication_marks_button.setAttribute('aria-pressed', String(!state.fabricationMarksVisible));
  dom.fabrication_marks_button.title = state.fabricationMarksVisible
    ? 'Ocultar marcas de fabricación'
    : 'Mostrar marcas de fabricación';
  dom.fabrication_marks_button.setAttribute('aria-label', dom.fabrication_marks_button.title);
  updateModelMeta();
  if (refit) fitCamera();
}

function installModel(root) {
  clearModel();
  state.modelRoot = root;
  root.name = 'Baugruppe';
  state.totalPartCount = 0;
  state.fastenerCount = 0;
  state.fabricationMarkCount = 0;
  state.externalFastenerCount = 0;
  root.traverse((object) => {
    if (!object.isMesh) return;
    state.totalPartCount += 1;
    object.userData.workshopFastener = isWorkshopFastener(object);
    object.userData.fabricationMark = isFabricationMark(object);
    object.userData.originalMaterial = object.material;
    object.userData.viewMaterial = null;
    if (object.userData.workshopFastener) state.fastenerCount += 1;
    if (object.userData.fabricationMark) state.fabricationMarkCount += 1;
    if (isExternalConnectedFastener(object)) state.externalFastenerCount += 1;
    eachMaterial(object.material, (material) => {
      material.side = THREE.DoubleSide;
      material.wireframe = state.wireframe;
    });
  });
  scene.add(root);
  applyVisualStyle();
  applyFastenerVisibility();
  applyFabricationMarkVisibility();
  fitCamera();
  updateModelMeta();
  dom.viewer_meta.hidden = false;
  dom.empty_state.hidden = true;
  dom.measure_button.disabled = false;
  dom.measure_snap_vertex_button.disabled = false;
  dom.measure_snap_edge_button.disabled = false;
  renderMeasurements();
}

function niceGridSize(size) {
  const raw = Math.max(size * 2.4, 0.2);
  const power = 10 ** Math.floor(Math.log10(raw));
  const normalized = raw / power;
  const nice = normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return nice * power;
}

function fitCamera() {
  if (!state.modelRoot) return;
  const box = visibleModelBox();
  if (box.isEmpty()) return;
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const maxSize = Math.max(size.x, size.y, size.z, 0.01);
  const distance = maxSize / (2 * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2))) * 1.35;
  camera.near = Math.max(maxSize / 10000, 0.0001);
  camera.far = Math.max(maxSize * 1000, 100);
  camera.position.copy(center).add(new THREE.Vector3(distance * 0.8, distance * 0.52, distance));
  camera.updateProjectionMatrix();
  controls.target.copy(center);
  controls.minDistance = maxSize * 0.02;
  controls.maxDistance = maxSize * 40;
  controls.update();
  scene.fog.density = 0.018 / maxSize;

  if (grid) {
    scene.remove(grid);
    grid.geometry.dispose();
    grid.material.dispose();
  }
  const gridSize = niceGridSize(maxSize);
  grid = new THREE.GridHelper(gridSize, 20, 0x36576a, 0x1b2b35);
  grid.position.y = box.min.y - maxSize * 0.025;
  grid.material.transparent = true;
  grid.material.opacity = 0.7;
  scene.add(grid);
}

function setLoading(loading) {
  dom.loading_state.hidden = !loading;
  if (loading) {
    dom.empty_state.hidden = true;
    dom.viewer_meta.hidden = true;
  }
}

function showEmpty(title, detail) {
  clearModel();
  dom.empty_state.querySelector('h1').textContent = title;
  dom.empty_state.querySelector('p').textContent = detail;
  dom.empty_state.hidden = false;
}

function formatMm(value) {
  return `${new Intl.NumberFormat('es-ES', { maximumFractionDigits: 1 }).format(value)} mm`;
}

function clearMeasurementGraphics() {
  while (measurementGroup.children.length) {
    const child = measurementGroup.children.at(-1);
    measurementGroup.remove(child);
    disposeObject(child);
  }
}

function clearMeasurementSnapPreview() {
  while (measurementSnapPreviewGroup.children.length) {
    const child = measurementSnapPreviewGroup.children.at(-1);
    measurementSnapPreviewGroup.remove(child);
    disposeObject(child);
  }
  measurementSnapPreviewMode = null;
  measurementSnapPreviewTarget = null;
  measurementSnapPreviewEdge = null;
}

function stopMeasurementSnapPreview() {
  measurementSnapPreviewPointer = null;
  if (measurementSnapPreviewFrame !== null) {
    cancelAnimationFrame(measurementSnapPreviewFrame);
    measurementSnapPreviewFrame = null;
  }
  clearMeasurementSnapPreview();
}

function showMeasurementSnapPreview(snap) {
  if (!snap || snap.mode === 'NONE') {
    clearMeasurementSnapPreview();
    return;
  }
  if (measurementSnapPreviewMode !== snap.mode) {
    clearMeasurementSnapPreview();
    measurementSnapPreviewMode = snap.mode;
    if (snap.mode === 'EDGE') {
      measurementSnapPreviewEdge = new THREE.Line(
        new THREE.BufferGeometry().setFromPoints([snap.edgeStart, snap.edgeEnd]),
        new THREE.LineBasicMaterial({
          color: 0x75d7ff,
          transparent: true,
          opacity: 0.98,
          depthTest: false,
          depthWrite: false,
        }),
      );
      measurementSnapPreviewEdge.renderOrder = 23;
      measurementSnapPreviewGroup.add(measurementSnapPreviewEdge);
    }
    const element = document.createElement('span');
    element.className = `measurement-snap-target ${snap.mode === 'VERTEX' ? 'vertex' : 'edge'}`;
    measurementSnapPreviewTarget = new CSS2DObject(element);
    measurementSnapPreviewTarget.renderOrder = 24;
    measurementSnapPreviewGroup.add(measurementSnapPreviewTarget);
  }
  measurementSnapPreviewTarget.position.copy(snap.point);
  if (measurementSnapPreviewEdge) {
    const positions = measurementSnapPreviewEdge.geometry.getAttribute('position');
    positions.setXYZ(0, snap.edgeStart.x, snap.edgeStart.y, snap.edgeStart.z);
    positions.setXYZ(1, snap.edgeEnd.x, snap.edgeEnd.y, snap.edgeEnd.z);
    positions.needsUpdate = true;
    measurementSnapPreviewEdge.geometry.computeBoundingSphere();
  }
}

function makePoint(position, color = 0xffb44a) {
  const radius = Math.max(state.modelSize * 0.008, 0.0015);
  const point = new THREE.Mesh(
    new THREE.SphereGeometry(radius, 18, 12),
    new THREE.MeshBasicMaterial({ color, depthTest: false }),
  );
  point.position.copy(position);
  point.renderOrder = 20;
  return point;
}

function makeMeasurementLabel(text) {
  const element = document.createElement('span');
  element.className = 'measurement-label';
  element.textContent = text;
  const label = new CSS2DObject(element);
  label.renderOrder = 22;
  return label;
}

function addMeasurementGraphic(measurement) {
  const start = new THREE.Vector3(...measurement.start);
  const end = new THREE.Vector3(...measurement.end);
  const group = new THREE.Group();
  group.userData.measurementId = measurement.id;
  group.add(makePoint(start), makePoint(end));
  const line = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([start, end]),
    new THREE.LineBasicMaterial({ color: 0xffb44a, depthTest: false }),
  );
  line.renderOrder = 19;
  group.add(line);
  const label = makeMeasurementLabel(formatMm(measurement.distanceMm));
  label.position.copy(start).lerp(end, 0.5);
  label.position.y += Math.max(state.modelSize * 0.025, 0.005);
  group.add(label);
  measurementGroup.add(group);
}

function renderMeasurements() {
  clearMeasurementGraphics();
  for (const measurement of state.measurements) addMeasurementGraphic(measurement);
  if (state.pendingPoint) measurementGroup.add(makePoint(state.pendingPoint, 0x75d7ff));

  dom.measurement_count.textContent = state.measurements.length;
  dom.delete_all_button.disabled = state.measurements.length === 0;
  dom.measurement_list.replaceChildren();
  if (!state.measurements.length) {
    dom.measurement_list.innerHTML = `
      <div class="no-measurements"><span>↗</span><strong>Sin cotas</strong>
      <small>Activa “Nueva cota” para medir sobre la pieza.</small></div>`;
    return;
  }
  state.measurements.forEach((measurement, index) => {
    const item = document.createElement('article');
    item.className = 'measurement-item';
    item.innerHTML = `
      <span class="measurement-number">${String(index + 1).padStart(2, '0')}</span>
      <span class="measurement-copy"><small>${measurement.label || 'COTA'}</small><strong>${formatMm(measurement.distanceMm)}</strong></span>
      <button title="Borrar esta cota" aria-label="Borrar cota">
        <svg viewBox="0 0 24 24"><path d="M5 5l14 14M19 5 5 19"/></svg>
      </button>`;
    item.querySelector('button').addEventListener('click', () => deleteMeasurement(measurement.id));
    dom.measurement_list.append(item);
  });
}

function measurementSnapName(mode = state.measurementSnapMode) {
  if (mode === 'VERTEX') return 'vértices';
  if (mode === 'EDGE') return 'aristas';
  return null;
}

function updateMeasurementSnapUi() {
  const vertexActive = state.measurementSnapMode === 'VERTEX';
  const edgeActive = state.measurementSnapMode === 'EDGE';
  dom.measure_snap_vertex_button.classList.toggle('active', vertexActive);
  dom.measure_snap_edge_button.classList.toggle('active', edgeActive);
  dom.measure_snap_vertex_button.setAttribute('aria-pressed', String(vertexActive));
  dom.measure_snap_edge_button.setAttribute('aria-pressed', String(edgeActive));
  dom.measure_snap_status.textContent = measurementSnapName()
    ? `Ajuste a ${measurementSnapName()} activado`
    : 'Ajuste desactivado';
}

function setMeasurementSnapMode(mode) {
  state.measurementSnapMode = state.measurementSnapMode === mode ? 'NONE' : mode;
  stopMeasurementSnapPreview();
  updateMeasurementSnapUi();
  updateInteractionUi();
}

function setMeasureMode(enabled) {
  if (!state.modelRoot || !state.occurrence) enabled = false;
  if (enabled) clearPartSelection();
  state.measureMode = enabled;
  dom.measure_button.classList.toggle('active', enabled);
  if (!enabled) {
    state.pendingPoint = null;
    stopMeasurementSnapPreview();
  }
  updateInteractionUi();
  renderMeasurements();
}

function cancelMeasurement() {
  setMeasureMode(false);
}

function updateInteractionUi() {
  dom.measure_button.classList.toggle('active', state.measureMode);
  dom.viewport.classList.toggle('measuring', state.measureMode);
  dom.measure_hint.hidden = !state.measureMode;
  if (state.measureMode) {
    const instruction = state.pendingPoint
      ? 'Selecciona el segundo punto'
      : 'Selecciona el primer punto';
    const snapName = measurementSnapName();
    dom.measure_hint_text.textContent = snapName
      ? `${instruction} · ajuste a ${snapName}`
      : instruction;
  }
}

function cancelInteraction() {
  stopMeasurementSnapPreview();
  state.measureMode = false;
  state.pendingPoint = null;
  updateInteractionUi();
  renderMeasurements();
}

function pickModelIntersection(event) {
  if (!state.modelRoot) return null;
  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const intersections = raycaster
    .intersectObject(state.modelRoot, true)
    .filter((intersection) => intersection.object.visible);
  return intersections[0] || null;
}

function selectedFaceNormals(intersection) {
  const local = intersection.face.normal.clone().normalize();
  const world = local.clone().transformDirection(intersection.object.matrixWorld);
  if (world.dot(raycaster.ray.direction) > 0) {
    local.negate();
    world.negate();
  }
  return { local, world };
}

function projectedPixelDistance(first, second) {
  const firstProjected = first.clone().project(camera);
  const secondProjected = second.clone().project(camera);
  if (
    firstProjected.z < -1 || firstProjected.z > 1
    || secondProjected.z < -1 || secondProjected.z > 1
  ) return Infinity;
  const rect = renderer.domElement.getBoundingClientRect();
  return Math.hypot(
    (firstProjected.x - secondProjected.x) * rect.width / 2,
    (firstProjected.y - secondProjected.y) * rect.height / 2,
  );
}

function measurementSnapFromIntersection(intersection) {
  if (state.measurementSnapMode === 'NONE') {
    return { mode: 'NONE', point: intersection.point.clone() };
  }
  const object = intersection.object;
  if (!intersection.face || !object?.geometry) return null;
  if (!object.userData.featureEdges) {
    object.userData.featureEdges = buildFeatureEdges(object.geometry);
  }
  object.updateWorldMatrix(true, false);
  const localClick = object.worldToLocal(intersection.point.clone());
  const { local: localNormal } = selectedFaceNormals(intersection);
  const snap = state.measurementSnapMode === 'VERTEX'
    ? findNearestFeatureVertex(object.userData.featureEdges, localClick, localNormal)
    : findNearestFeatureEdge(object.userData.featureEdges, localClick, localNormal);
  if (!snap) return null;
  const snappedPoint = object.localToWorld(snap.point.clone());
  if (projectedPixelDistance(intersection.point, snappedPoint) > 30) return null;
  const result = { mode: state.measurementSnapMode, point: snappedPoint };
  if (state.measurementSnapMode === 'EDGE') {
    result.edgeStart = object.localToWorld(snap.start.clone());
    result.edgeEnd = object.localToWorld(snap.end.clone());
  }
  return result;
}

function updateMeasurementSnapPreview(pointerEvent) {
  if (
    !state.measureMode
    || state.measurementSnapMode === 'NONE'
    || pointerEvent.buttons !== 0
  ) {
    clearMeasurementSnapPreview();
    return;
  }
  const intersection = pickModelIntersection(pointerEvent);
  showMeasurementSnapPreview(intersection ? measurementSnapFromIntersection(intersection) : null);
}

function scheduleMeasurementSnapPreview(event) {
  measurementSnapPreviewPointer = {
    clientX: event.clientX,
    clientY: event.clientY,
    buttons: event.buttons,
  };
  if (measurementSnapPreviewFrame !== null) return;
  measurementSnapPreviewFrame = requestAnimationFrame(() => {
    measurementSnapPreviewFrame = null;
    const previewPointer = measurementSnapPreviewPointer;
    measurementSnapPreviewPointer = null;
    if (previewPointer) updateMeasurementSnapPreview(previewPointer);
  });
}

function rejectMeasurementSnap() {
  const target = state.measurementSnapMode === 'VERTEX' ? 'un vértice' : 'una arista';
  notify(`No hay ${target} cerca del cursor. Haz clic más cerca o desactiva el ajuste.`, 'error');
}

async function acceptPoint(point) {
  if (!state.pendingPoint) {
    state.pendingPoint = point;
    dom.measure_hint_text.textContent = 'Selecciona el segundo punto';
    renderMeasurements();
    return;
  }
  const start = state.pendingPoint;
  state.pendingPoint = null;
  setMeasureMode(false);
  try {
    const measurement = await request(revisionUrl(`${occurrenceBase()}/measurements`), {
      method: 'POST',
      body: JSON.stringify({ start: start.toArray(), end: point.toArray() }),
    });
    state.measurements.push(measurement);
    renderMeasurements();
    notify(`Cota guardada: ${formatMm(measurement.distanceMm)}`);
  } catch (error) {
    notify(error.message, 'error');
  }
}

async function deleteMeasurement(id) {
  try {
    await request(
      revisionUrl(`${occurrenceBase()}/measurements/${encodeURIComponent(id)}`),
      { method: 'DELETE' },
    );
    state.measurements = state.measurements.filter((item) => item.id !== id);
    renderMeasurements();
    notify('Cota eliminada');
  } catch (error) {
    notify(error.message, 'error');
  }
}

async function deleteAllMeasurements() {
  if (!state.measurements.length) return;
  if (!window.confirm(`¿Borrar las ${state.measurements.length} cotas de esta ocurrencia?`)) return;
  try {
    await request(revisionUrl(`${occurrenceBase()}/measurements`), { method: 'DELETE' });
    state.measurements = [];
    renderMeasurements();
    notify('Se han borrado todas las cotas');
  } catch (error) {
    notify(error.message, 'error');
  }
}


function setMobileExportMode() {
  renderMobileExportSelections();
}

function mobileExportMode() {
  if (dom.mobile_export_drawings.checked) return 'DRAWINGS';
  if (dom.mobile_export_assemblies.checked) return 'ASSEMBLIES';
  return 'COMPLETE';
}

function appendMobileExportSelection({ value, title, detail, kind, checked }) {
  const label = document.createElement('label');
  label.className = 'mobile-export-selection';
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.dataset.kind = kind;
  input.dataset.value = value;
  input.checked = checked;
  const copy = document.createElement('span');
  const strong = document.createElement('strong');
  strong.textContent = title;
  const small = document.createElement('small');
  small.textContent = detail;
  copy.append(strong, small);
  label.append(input, copy);
  dom.mobile_export_selection_list.append(label);
}

function renderMobileExportSelections() {
  dom.mobile_export_selection_list.replaceChildren();
  const mode = mobileExportMode();
  if (mode === 'COMPLETE') {
    dom.mobile_export_selection_list.innerHTML = '<p class="mobile-export-selection-empty">Se exportará todo el proyecto.</p>';
    return;
  }
  if (mode === 'DRAWINGS') {
    for (const drawing of state.project.drawings) {
      appendMobileExportSelection({
        value: drawing.number,
        title: `Zeichnung ${drawing.number}`,
        detail: `${drawing.positions.length} Baugruppe${drawing.positions.length === 1 ? '' : 'n'}`,
        kind: 'drawing',
        checked: drawing.number === state.source?.drawingNumber,
      });
    }
    return;
  }
  for (const drawing of state.project.drawings) {
    for (const source of drawing.positions) {
      appendMobileExportSelection({
        value: source.id,
        title: source.positionNumber,
        detail: `Zeichnung ${drawing.number} · ${source.revisionCount || 1} revisión${(source.revisionCount || 1) === 1 ? '' : 'es'}`,
        kind: 'assembly',
        checked: source.id === state.source?.id,
      });
    }
  }
}

function openMobileExportDialog() {
  if (!state.project) return;
  dom.mobile_export_complete.checked = true;
  dom.mobile_export_error.hidden = true;
  dom.mobile_export_progress.hidden = true;
  dom.mobile_export_progress_bar.style.width = '0%';
  setMobileExportMode();
  dom.mobile_export_dialog.showModal();
}

function closeMobileExportDialog() {
  if (!dom.mobile_export_submit_button.disabled) dom.mobile_export_dialog.close();
}

function updateMobileExportProgress(job) {
  const percentage = Number(job.percentage || 0);
  dom.mobile_export_progress_bar.style.width = `${percentage}%`;
  const total = Number(job.total || 0);
  const completed = Number(job.completed || 0);
  const stageCompleted = Number(job.stageCompleted || 0);
  const stageTotal = Number(job.stageTotal || total);
  const skipped = Number(job.skippedCount || 0);
  const skippedText = skipped ? ` · omitidas ${skipped}` : '';
  if (job.phase === 'GEOMETRY' && stageTotal) {
    dom.mobile_export_progress_title.textContent = `${percentage}% · ${stageCompleted} de ${stageTotal} geometrías · quedan ${Math.max(0, stageTotal - stageCompleted)}${skippedText}`;
  } else if (total) {
    dom.mobile_export_progress_title.textContent = `${percentage}% · ${completed} de ${total} paquetes · quedan ${Math.max(0, total - completed)}${skippedText}`;
  } else {
    dom.mobile_export_progress_title.textContent = 'Preparando la lista de geometrías…';
  }
  const phaseLabels = {
    QUEUED: 'Exportación en cola',
    PREPARING: 'Calculando Baugruppen y revisiones incluidas',
    GEOMETRY: `Generando geometría${job.workers > 1 ? ` con ${job.workers} procesos` : ''}`,
    PACKAGES: 'Construyendo paquetes móviles',
    GPU_TARGETS: 'Generando y registrando targets para el servidor GPU',
    PACKAGING: 'Cerrando y verificando el proyecto',
    COMPLETED: 'Exportación terminada',
  };
  const current = job.current;
  dom.mobile_export_progress_detail.textContent = current
    ? `${phaseLabels[job.phase] || job.phase} · ${current.positionNumber} · Rev. ${current.revision} · ocurrencia ${current.occurrence}`
    : (phaseLabels[job.phase] || 'Preparando la exportación…');
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function downloadExport(url, fallbackFilename) {
  const response = await fetch(url);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(apiErrorMessage(body.detail, `Error ${response.status}`));
  }
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition') || '';
  const match = disposition.match(/filename="?([^";]+)"?/i);
  const filename = match?.[1] || fallbackFilename;
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  return filename;
}

async function exportMobileProject(event) {
  event.preventDefault();
  const mode = mobileExportMode();
  const selected = [...dom.mobile_export_selection_list.querySelectorAll('input:checked')];
  const drawings = selected.filter((input) => input.dataset.kind === 'drawing')
    .map((input) => input.dataset.value);
  const assemblies = selected.filter((input) => input.dataset.kind === 'assembly')
    .map((input) => input.dataset.value);
  if (mode !== 'COMPLETE' && !selected.length) {
    dom.mobile_export_error.textContent = mode === 'DRAWINGS'
      ? 'Selecciona al menos una Zeichnung.'
      : 'Selecciona al menos una Baugruppe.';
    dom.mobile_export_error.hidden = false;
    return;
  }
  dom.mobile_export_submit_button.disabled = true;
  dom.mobile_export_cancel_button.disabled = true;
  dom.mobile_export_close_button.disabled = true;
  dom.mobile_export_progress.hidden = false;
  dom.mobile_export_error.hidden = true;
  dom.mobile_export_error.dataset.kind = 'error';
  dom.mobile_export_progress_bar.style.width = '0%';
  try {
    let job = await request(
      apiPath(`/api/projects/${state.project.id}/mobile-project-exports`),
      {
        method: 'POST',
        body: JSON.stringify({ mode, drawings, assemblies }),
      },
    );
    updateMobileExportProgress(job);
    while (!['COMPLETED', 'FAILED'].includes(job.status)) {
      await wait(400);
      job = await request(
        apiPath(`/api/projects/${state.project.id}/mobile-project-exports/${job.jobId}`),
      );
      updateMobileExportProgress(job);
    }
    if (job.status === 'FAILED') throw new Error(job.error || 'La exportación no se pudo completar.');
    const filename = await downloadExport(job.downloadUrl, `${state.project.name}.qsproject`);
    if (!job.gpuTargetsProvisioned) {
      await downloadExport(
        job.gpuTargetsDownloadUrl,
        `${state.project.name}.qsgputargets`,
      );
    }
    if (job.skippedCount) {
      const details = (job.skipped || []).slice(0, 12)
        .map((item) => `• ${item.positionNumber} · Rev. ${item.revision}: ${item.reason}`)
        .join('\n');
      dom.mobile_export_error.dataset.kind = 'warning';
      dom.mobile_export_error.textContent = `Exportación completada con ${job.skippedCount} Baugruppe${job.skippedCount === 1 ? '' : 'n'} omitida${job.skippedCount === 1 ? '' : 's'}.\n${details}`;
      dom.mobile_export_error.hidden = false;
      notify(`Proyecto exportado con ${job.skippedCount} omisión${job.skippedCount === 1 ? '' : 'es'}`);
    } else {
      dom.mobile_export_dialog.close();
      notify(job.gpuTargetsProvisioned
        ? `Proyecto Android exportado y ${job.gpuTargetCount} targets GPU registrados: ${filename}`
        : `Proyecto Android y targets GPU exportados: ${filename}`);
    }
  } catch (error) {
    dom.mobile_export_error.dataset.kind = 'error';
    dom.mobile_export_error.textContent = error.message;
    dom.mobile_export_error.hidden = false;
  } finally {
    dom.mobile_export_submit_button.disabled = false;
    dom.mobile_export_cancel_button.disabled = false;
    dom.mobile_export_close_button.disabled = false;
    dom.mobile_export_progress.hidden = true;
  }
}

function resize() {
  const width = dom.viewport.clientWidth;
  const height = dom.viewport.clientHeight;
  renderer.setSize(width, height, false);
  labelRenderer.setSize(width, height);
  composer.setSize(width, height);
  camera.aspect = width / Math.max(height, 1);
  camera.updateProjectionMatrix();
  orientationRenderer.setSize(
    Math.max(dom.orientation_canvas.clientWidth, 1),
    Math.max(dom.orientation_canvas.clientHeight, 1),
    false,
  );
}

function animate() {
  requestAnimationFrame(animate);
  controls.update();
  if (state.cavity && state.modelRoot && cavityRuntimeAvailable) {
    try {
      composer.render();
    } catch (error) {
      cavityRuntimeAvailable = false;
      state.cavity = false;
      dom.cavity_button.classList.remove('active');
      dom.cavity_button.setAttribute('aria-pressed', 'false');
      dom.cavity_button.title = 'Cavity no está disponible en este navegador';
      console.warn('Cavity disabled after a rendering error', error);
      renderer.render(scene, camera);
    }
  } else {
    renderer.render(scene, camera);
  }
  labelRenderer.render(scene, camera);
  orientationDirection.copy(camera.position).sub(controls.target);
  if (orientationDirection.lengthSq() > 1e-10) {
    orientationCamera.position.copy(orientationDirection.normalize().multiplyScalar(3));
  }
  orientationCamera.up.copy(camera.up);
  orientationCamera.lookAt(0, 0, 0);
  orientationRenderer.render(orientationScene, orientationCamera);
}

function setCavity(enabled) {
  if (enabled && !cavityRuntimeAvailable) {
    notify('Cavity no está disponible en este navegador', 'error');
    return;
  }
  state.cavity = enabled;
  dom.cavity_button.classList.toggle('active', enabled);
  dom.cavity_button.setAttribute('aria-pressed', String(enabled));
  dom.cavity_button.title = enabled ? 'Desactivar realce Cavity' : 'Activar realce Cavity';
  dom.cavity_button.setAttribute('aria-label', dom.cavity_button.title);
  notify(enabled ? 'Cavity activado' : 'Cavity desactivado');
}

function openImportDialog(mode) {
  state.importMode = mode;
  dom.import_form.reset();
  dom.file_name.textContent = 'Hasta 2 GB';
  dom.import_error.hidden = true;
  dom.import_progress.hidden = true;
  const creating = mode === 'create';
  dom.import_kicker.textContent = creating ? 'PROYECTO NUEVO' : 'NUEVA VERSIÓN IFC';
  dom.import_title.textContent = creating ? 'Crear proyecto' : `Actualizar ${state.project.name}`;
  dom.import_description.textContent = creating
    ? 'El primer IFC creará el catálogo y la revisión inicial de cada Baugruppe.'
    : 'Sólo las Baugruppen cuya geometría haya cambiado recibirán una nueva revisión.';
  dom.project_name_field.hidden = !creating;
  dom.new_project_name.required = creating;
  dom.import_submit_button.textContent = creating ? 'Crear proyecto' : 'Importar y comparar';
  dom.import_dialog.showModal();
  if (creating) dom.new_project_name.focus();
}

function closeImportDialog() {
  if (!dom.import_submit_button.disabled) dom.import_dialog.close();
}

function setImportBusy(busy) {
  dom.import_progress.hidden = !busy;
  dom.import_submit_button.disabled = busy;
  dom.import_cancel_button.disabled = busy;
  dom.import_close_button.disabled = busy;
  dom.ifc_file.disabled = busy;
  dom.new_project_name.disabled = busy;
}

async function loadCatalog(projectId = null) {
  state.catalog = await request('/api/catalog');
  renderProjectOptions();
  if (!state.catalog.projects.length) {
    state.project = null;
    state.source = null;
    state.sourceDetail = null;
    state.occurrence = null;
    state.requestToken += 1;
    dom.update_project_button.disabled = true;
    dom.history_project_button.disabled = true;
    dom.delete_project_button.disabled = true;
    dom.mobile_export_button.disabled = true;
    dom.project_summary.textContent = 'Sin proyectos';
    dom.drawing_tree.replaceChildren();
    dom.crumb_project.textContent = 'Proyecto';
    dom.crumb_drawing.textContent = 'Dibujo';
    dom.crumb_position.textContent = 'Posición';
    showEmpty('Todavía no hay proyectos', 'Crea un proyecto e importa su primer IFC.');
    return;
  }
  state.source = null;
  state.sourceDetail = null;
  const selected = state.catalog.projects.find((item) => item.id === projectId)
    || state.catalog.projects[0];
  setProject(selected.id);
}

async function submitImport(event) {
  event.preventDefault();
  const file = dom.ifc_file.files[0];
  if (!file) {
    dom.import_error.textContent = 'Selecciona un archivo IFC.';
    dom.import_error.hidden = false;
    return;
  }
  const formData = new FormData();
  formData.append('ifc', file);
  let endpoint;
  if (state.importMode === 'create') {
    const projectName = dom.new_project_name.value.trim();
    if (!projectName) {
      dom.import_error.textContent = 'Introduce el nombre del proyecto.';
      dom.import_error.hidden = false;
      return;
    }
    formData.append('project_name', projectName);
    endpoint = '/api/projects';
  } else {
    endpoint = `/api/projects/${encodeURIComponent(state.project.id)}/imports`;
  }
  dom.import_error.hidden = true;
  setImportBusy(true);
  try {
    const response = await fetch(endpoint, { method: 'POST', body: formData });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(apiErrorMessage(body.detail, `Error ${response.status}`));
    const summary = body.summary || {};
    dom.import_dialog.close();
    await loadCatalog(body.projectId);
    const audit = body.sourceGeometryAudit || {};
    const fabrication = Number.isFinite(Number(audit.physicalFastenerCount))
      ? ` · ${audit.physicalFastenerCount} grupos VM · ${audit.fabricationHoleCount || 0} grupos Bohrung · ${audit.fabricationMarkCount || 0} marcas filtradas`
      : '';
    notify(
      `Importación terminada: ${summary.createdPositions || 0} nuevas · ${summary.revisedPositions || 0} revisadas · ${summary.unchangedPositions || 0} sin cambios${fabrication}`,
    );
  } catch (error) {
    dom.import_error.textContent = error.message;
    dom.import_error.hidden = false;
  } finally {
    setImportBusy(false);
  }
}

function historyDate(value) {
  if (!value) return 'Fecha no disponible';
  return new Intl.DateTimeFormat('es-ES', { dateStyle: 'medium', timeStyle: 'short' })
    .format(new Date(value));
}

function renderImportHistory(historyDocument) {
  dom.history_list.replaceChildren();
  if (!historyDocument.imports.length) {
    dom.history_list.innerHTML = '<div class="history-empty">No hay importaciones reversibles registradas.</div>';
    return;
  }
  for (const imported of historyDocument.imports) {
    const item = document.createElement('article');
    item.className = 'history-item';
    const copy = document.createElement('span');
    copy.className = 'history-copy';
    const title = document.createElement('strong');
    title.textContent = imported.sourceFile || imported.id;
    if (imported.isLatest) {
      const latest = document.createElement('span');
      latest.className = 'history-latest';
      latest.textContent = 'ÚLTIMA';
      title.append(latest);
    }
    const meta = document.createElement('small');
    meta.textContent = `${historyDate(imported.importedAt)} · ${imported.createdPositions || 0} nuevas · ${imported.revisedPositions || 0} revisadas · ${imported.retiredPositions || 0} retiradas`;
    copy.append(title, meta);
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'history-delete';
    button.textContent = imported.canDelete
      ? 'Deshacer importación'
      : imported.isLatest ? 'Estado inicial' : 'Deshaz primero la última';
    if (imported.isLatest && !imported.canDelete) {
      button.title = 'Para eliminar el IFC inicial, borra el proyecto completo.';
    }
    button.disabled = !imported.canDelete;
    button.addEventListener('click', () => deleteProjectImport(imported, button));
    item.append(copy, button);
    dom.history_list.append(item);
  }
}

async function openImportHistory() {
  if (!state.project) return;
  dom.history_list.innerHTML = '<div class="history-empty">Cargando historial…</div>';
  dom.history_dialog.showModal();
  try {
    const historyDocument = await request(`/api/projects/${encodeURIComponent(state.project.id)}/imports`);
    renderImportHistory(historyDocument);
  } catch (error) {
    dom.history_list.innerHTML = '';
    const message = document.createElement('div');
    message.className = 'history-empty';
    message.textContent = error.message;
    dom.history_list.append(message);
  }
}

async function deleteProjectImport(imported, button) {
  const message = `¿Deshacer la importación “${imported.sourceFile}”?\n\nSe eliminarán juntas todas las revisiones creadas por ese IFC y se restaurará el catálogo anterior.`;
  if (!window.confirm(message)) return;
  const projectId = state.project.id;
  button.disabled = true;
  button.textContent = 'Restaurando…';
  try {
    const result = await request(
      `/api/projects/${encodeURIComponent(projectId)}/imports/${encodeURIComponent(imported.id)}`,
      { method: 'DELETE' },
    );
    dom.history_dialog.close();
    await loadCatalog(projectId);
    notify(`Importación eliminada · ${result.restoredPositionCount} posiciones restauradas`);
  } catch (error) {
    button.disabled = false;
    button.textContent = 'Deshacer importación';
    notify(error.message, 'error');
  }
}

async function deleteCurrentProject() {
  if (!state.project) return;
  const project = state.project;
  const confirmation = window.prompt(
    `Se borrará por completo “${project.name}”: todos sus IFC, revisiones, modelos, cotas y exportaciones. Esta acción no se puede deshacer.\n\nEscribe exactamente “${project.name}” para confirmar:`,
  );
  if (confirmation === null) return;
  if (confirmation !== project.name) {
    notify('El nombre no coincide. No se ha borrado el proyecto.', 'error');
    return;
  }

  dom.delete_project_button.disabled = true;
  try {
    await request(`/api/projects/${encodeURIComponent(project.id)}`, { method: 'DELETE' });
    state.requestToken += 1;
    state.project = null;
    state.source = null;
    state.sourceDetail = null;
    state.occurrence = null;
    await loadCatalog();
    notify(`Proyecto “${project.name}” eliminado por completo`);
  } catch (error) {
    notify(error.message, 'error');
  } finally {
    dom.delete_project_button.disabled = !state.project;
  }
}

dom.project_select.addEventListener('change', () => setProject(dom.project_select.value));
dom.catalog_search.addEventListener('input', renderTree);
dom.occurrence_select.addEventListener('change', () => {
  const occurrence = state.sourceDetail?.occurrences.find(
    (item) => item.sourceGlobalId === dom.occurrence_select.value,
  );
  if (occurrence) selectOccurrence(occurrence);
});
dom.revision_select.addEventListener('change', () => {
  loadSourceRevision(Number(dom.revision_select.value));
});
dom.new_project_button.addEventListener('click', () => openImportDialog('create'));
dom.update_project_button.addEventListener('click', () => openImportDialog('update'));
dom.history_project_button.addEventListener('click', openImportHistory);
dom.delete_project_button.addEventListener('click', deleteCurrentProject);
dom.import_close_button.addEventListener('click', closeImportDialog);
dom.import_cancel_button.addEventListener('click', closeImportDialog);
dom.import_form.addEventListener('submit', submitImport);
dom.ifc_file.addEventListener('change', () => {
  dom.file_name.textContent = dom.ifc_file.files[0]?.name || 'Hasta 2 GB';
});
dom.import_dialog.addEventListener('cancel', (event) => {
  if (dom.import_submit_button.disabled) event.preventDefault();
});
dom.history_close_button.addEventListener('click', () => dom.history_dialog.close());
dom.history_done_button.addEventListener('click', () => dom.history_dialog.close());
dom.fit_button.addEventListener('click', fitCamera);
dom.view_style_select.addEventListener('change', () => {
  state.visualStyle = dom.view_style_select.value;
  applyVisualStyle();
  notify(`Estilo: ${dom.view_style_select.options[dom.view_style_select.selectedIndex].text}`);
});
dom.wireframe_button.addEventListener('click', () => {
  state.wireframe = !state.wireframe;
  dom.wireframe_button.classList.toggle('active', state.wireframe);
  state.modelRoot?.traverse((object) => {
    if (object.isMesh) eachMaterial(object.material, (material) => {
      material.wireframe = state.wireframe;
    });
  });
});
dom.cavity_button.addEventListener('click', () => setCavity(!state.cavity));
dom.fasteners_button.addEventListener('click', () => {
  state.fastenersVisible = !state.fastenersVisible;
  applyFastenerVisibility({ refit: true });
  notify(state.fastenersVisible ? 'Pernos visibles' : 'Pernos ocultos para vista de taller');
});
dom.fabrication_marks_button.addEventListener('click', () => {
  state.fabricationMarksVisible = !state.fabricationMarksVisible;
  applyFabricationMarkVisibility({ refit: true });
  notify(
    state.fabricationMarksVisible
      ? 'Marcas de fabricación visibles'
      : 'Marcas de fabricación ocultas',
  );
});
dom.mobile_export_button.addEventListener('click', openMobileExportDialog);
dom.mobile_export_form.addEventListener('submit', exportMobileProject);
dom.mobile_export_complete.addEventListener('change', setMobileExportMode);
dom.mobile_export_drawings.addEventListener('change', setMobileExportMode);
dom.mobile_export_assemblies.addEventListener('change', setMobileExportMode);
dom.mobile_export_close_button.addEventListener('click', closeMobileExportDialog);
dom.mobile_export_cancel_button.addEventListener('click', closeMobileExportDialog);
dom.mobile_export_dialog.addEventListener('cancel', (event) => {
  if (dom.mobile_export_submit_button.disabled) event.preventDefault();
});
dom.measure_button.addEventListener('click', () => setMeasureMode(!state.measureMode));
dom.measure_snap_vertex_button.addEventListener('click', () => setMeasurementSnapMode('VERTEX'));
dom.measure_snap_edge_button.addEventListener('click', () => setMeasurementSnapMode('EDGE'));
dom.delete_all_button.addEventListener('click', deleteAllMeasurements);
dom.part_tooltip_close.addEventListener('click', clearPartSelection);
renderer.domElement.addEventListener('pointerdown', (event) => {
  stopMeasurementSnapPreview();
  pointerStart = { x: event.clientX, y: event.clientY };
});
renderer.domElement.addEventListener('pointermove', scheduleMeasurementSnapPreview);
renderer.domElement.addEventListener('pointerleave', stopMeasurementSnapPreview);
renderer.domElement.addEventListener('pointerup', (event) => {
  if (!pointerStart) return;
  const movement = Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y);
  pointerStart = null;
  if (movement > 5) return;
  const intersection = pickModelIntersection(event);
  if (!intersection) {
    if (!state.measureMode) clearPartSelection();
    return;
  }
  if (state.measureMode) {
    const snap = measurementSnapFromIntersection(intersection);
    if (snap) acceptPoint(snap.point);
    else rejectMeasurementSnap();
  }
  else selectPart(intersection, event);
});
renderer.domElement.addEventListener('pointercancel', () => {
  pointerStart = null;
  stopMeasurementSnapPreview();
});
window.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  if (state.measureMode) cancelInteraction();
  else clearPartSelection();
});
window.addEventListener('resize', resize);
new ResizeObserver(resize).observe(dom.viewport);

async function start() {
  resize();
  updateMeasurementSnapUi();
  animate();
  try {
    await loadCatalog();
  } catch (error) {
    showEmpty('No se pudo cargar el catálogo', error.message);
    notify(error.message, 'error');
  }
}

start();
