import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import {
  buildFeatureEdges,
  findNearestFeatureEdge,
  findNearestFeatureVertex,
} from '../src/geometry-snap.js';

function triangleGeometry() {
  return new THREE.BufferGeometry().setAttribute(
    'position',
    new THREE.Float32BufferAttribute([0, 0, 0, 1, 0, 0, 0, 1, 0], 3),
  );
}

test('extracts the three physical edges of a triangle', () => {
  assert.equal(buildFeatureEdges(triangleGeometry()).length, 3);
});

test('snaps to the nearest point on an edge', () => {
  const edges = buildFeatureEdges(triangleGeometry());
  const snap = findNearestFeatureEdge(
    edges,
    new THREE.Vector3(0.45, 0.06, 0),
    new THREE.Vector3(0, 0, 1),
  );
  assert.ok(snap);
  assert.ok(snap.point.distanceTo(new THREE.Vector3(0.45, 0, 0)) < 1e-6);
});

test('snaps to the nearest physical vertex', () => {
  const edges = buildFeatureEdges(triangleGeometry());
  const snap = findNearestFeatureVertex(
    edges,
    new THREE.Vector3(0.92, 0.03, 0),
    new THREE.Vector3(0, 0, 1),
  );
  assert.ok(snap);
  assert.ok(snap.point.distanceTo(new THREE.Vector3(1, 0, 0)) < 1e-6);
});
