import * as THREE from 'three';

const DEFAULT_CREASE_DEGREES = 18;
const FACE_NORMAL_DOT = Math.cos(THREE.MathUtils.degToRad(10));

function positionKey(point, tolerance) {
  return `${Math.round(point.x / tolerance)},${Math.round(point.y / tolerance)},${Math.round(point.z / tolerance)}`;
}

export function buildFeatureEdges(geometry, creaseDegrees = DEFAULT_CREASE_DEGREES) {
  const positions = geometry.getAttribute('position');
  if (!positions || positions.count < 3) return [];
  geometry.computeBoundingBox();
  const diagonal = geometry.boundingBox.getSize(new THREE.Vector3()).length();
  const tolerance = Math.max(diagonal * 1e-7, 1e-8);
  const edgeMap = new Map();
  const triangleCount = geometry.index
    ? Math.floor(geometry.index.count / 3)
    : Math.floor(positions.count / 3);
  const point = (index) => new THREE.Vector3().fromBufferAttribute(positions, index);
  const indexAt = (offset) => geometry.index ? geometry.index.getX(offset) : offset;

  for (let triangle = 0; triangle < triangleCount; triangle += 1) {
    const indices = [indexAt(triangle * 3), indexAt(triangle * 3 + 1), indexAt(triangle * 3 + 2)];
    const vertices = indices.map(point);
    const normal = new THREE.Vector3()
      .subVectors(vertices[1], vertices[0])
      .cross(new THREE.Vector3().subVectors(vertices[2], vertices[0]));
    if (normal.lengthSq() < tolerance * tolerance) continue;
    normal.normalize();
    for (const [left, right] of [[0, 1], [1, 2], [2, 0]]) {
      const startKey = positionKey(vertices[left], tolerance);
      const endKey = positionKey(vertices[right], tolerance);
      if (startKey === endKey) continue;
      const key = startKey < endKey ? `${startKey}|${endKey}` : `${endKey}|${startKey}`;
      let edge = edgeMap.get(key);
      if (!edge) {
        edge = { start: vertices[left].clone(), end: vertices[right].clone(), normals: [] };
        edgeMap.set(key, edge);
      }
      edge.normals.push(normal.clone());
    }
  }

  const creaseDot = Math.cos(THREE.MathUtils.degToRad(creaseDegrees));
  return [...edgeMap.values()].filter((edge) => {
    if (edge.normals.length === 1) return true;
    for (let left = 0; left < edge.normals.length; left += 1) {
      for (let right = left + 1; right < edge.normals.length; right += 1) {
        if (Math.abs(edge.normals[left].dot(edge.normals[right])) < creaseDot) return true;
      }
    }
    return false;
  });
}

export function findFeatureEdgesNearPoint(edges, point, faceNormal, maxDistance = Infinity) {
  const segment = new THREE.Line3();
  const closest = new THREE.Vector3();
  const results = [];
  for (const edge of edges) {
    if (!edge.normals.some((normal) => Math.abs(normal.dot(faceNormal)) >= FACE_NORMAL_DOT)) continue;
    segment.set(edge.start, edge.end);
    segment.closestPointToPoint(point, true, closest);
    const distance = closest.distanceTo(point);
    if (distance > maxDistance) continue;
    results.push({
      start: edge.start.clone(),
      end: edge.end.clone(),
      point: closest.clone(),
      direction: new THREE.Vector3().subVectors(edge.end, edge.start).normalize(),
      distance,
    });
  }
  return results.sort((left, right) => left.distance - right.distance);
}

export function findNearestFeatureEdge(edges, point, faceNormal, maxDistance = Infinity) {
  return findFeatureEdgesNearPoint(edges, point, faceNormal, maxDistance)[0] || null;
}

export function findNearestFeatureVertex(edges, point, faceNormal, maxDistance = Infinity) {
  let result = null;
  for (const edge of edges) {
    if (!edge.normals.some((normal) => Math.abs(normal.dot(faceNormal)) >= FACE_NORMAL_DOT)) continue;
    for (const vertex of [edge.start, edge.end]) {
      const distance = vertex.distanceTo(point);
      if (distance > maxDistance || (result && distance >= result.distance)) continue;
      result = { point: vertex.clone(), distance };
    }
  }
  return result;
}
