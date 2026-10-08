"""Build the compact CAD reference used by markerless mobile registration.

The source GLB remains the rendering asset.  This module derives a deterministic,
uniformly sampled surface cloud and a reduced set of geometric feature edges so the
Android app does not have to decode and preprocess a manufacturing mesh at runtime.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass
from typing import Any

import numpy as np


MAGIC = b"QSRG"
VERSION_MAJOR = 1
VERSION_MINOR = 0
HEADER_FORMAT = "<4sHHII7fI"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)
FLAG_NORMALS = 1
FLAG_FEATURE_EDGES = 2
MINIMUM_POINT_COUNT = 1_024
MAXIMUM_POINT_COUNT = 12_000
MAXIMUM_EDGE_COUNT = 6_000
MINIMUM_SPACING_M = 0.004
MAXIMUM_SPACING_M = 0.025
FEATURE_EDGE_DEGREES = 32.0


@dataclass(frozen=True)
class RegistrationModelInfo:
    point_count: int
    edge_count: int
    bounds_min: tuple[float, float, float]
    bounds_max: tuple[float, float, float]
    sample_spacing_m: float


def _read_glb(payload: bytes) -> tuple[dict[str, Any], bytes]:
    if len(payload) < 20:
        raise ValueError("GLB is too short for markerless registration")
    magic, version, declared_length = struct.unpack_from("<4sII", payload, 0)
    if magic != b"glTF" or version != 2 or declared_length != len(payload):
        raise ValueError("Invalid GLB header")

    document: dict[str, Any] | None = None
    binary = b""
    offset = 12
    while offset + 8 <= len(payload):
        length, kind = struct.unpack_from("<I4s", payload, offset)
        start = offset + 8
        end = start + length
        if end > len(payload):
            raise ValueError("GLB chunk exceeds declared size")
        if kind == b"JSON":
            import json

            document = json.loads(payload[start:end].rstrip(b" \0").decode("utf-8"))
        elif kind == b"BIN\0":
            binary = payload[start:end]
        offset = end
    if document is None or not binary:
        raise ValueError("Registration requires a GLB with JSON and BIN chunks")
    return document, binary


_COMPONENT_DTYPES = {
    5121: np.dtype("<u1"),
    5123: np.dtype("<u2"),
    5125: np.dtype("<u4"),
    5126: np.dtype("<f4"),
}
_TYPE_WIDTHS = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def _accessor_array(
    document: dict[str, Any],
    binary: bytes,
    accessor_index: int,
) -> np.ndarray:
    accessor = document["accessors"][int(accessor_index)]
    if "sparse" in accessor:
        raise ValueError("Sparse GLB accessors are not supported for registration")
    view = document["bufferViews"][int(accessor["bufferView"])]
    component_type = int(accessor["componentType"])
    dtype = _COMPONENT_DTYPES.get(component_type)
    width = _TYPE_WIDTHS.get(str(accessor["type"]))
    if dtype is None or width is None:
        raise ValueError("Unsupported GLB accessor for registration")
    count = int(accessor["count"])
    start = int(view.get("byteOffset", 0)) + int(accessor.get("byteOffset", 0))
    packed_stride = dtype.itemsize * width
    stride = int(view.get("byteStride", packed_stride))
    if stride < packed_stride:
        raise ValueError("Invalid GLB byte stride")
    required = start + max(count - 1, 0) * stride + packed_stride
    if start < 0 or required > len(binary):
        raise ValueError("GLB accessor exceeds BIN chunk")
    if stride == packed_stride:
        return np.frombuffer(binary, dtype=dtype, count=count * width, offset=start).reshape(
            (count, width)
        )
    result = np.empty((count, width), dtype=dtype)
    for row in range(count):
        result[row] = np.frombuffer(
            binary,
            dtype=dtype,
            count=width,
            offset=start + row * stride,
        )
    return result


def _quaternion_matrix(values: Any) -> np.ndarray:
    quaternion = np.asarray(values, dtype=np.float64)
    if quaternion.shape != (4,) or not np.isfinite(quaternion).all():
        raise ValueError("GLB node has an invalid rotation quaternion")
    length = float(np.linalg.norm(quaternion))
    if length <= 1e-12:
        raise ValueError("GLB node has a zero rotation quaternion")
    x, y, z, w = quaternion / length
    return np.asarray(
        [
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
        ],
        dtype=np.float64,
    )


def _node_matrix(node: dict[str, Any]) -> np.ndarray:
    if "matrix" in node:
        if any(key in node for key in ("translation", "rotation", "scale")):
            raise ValueError("GLB node mixes matrix and TRS transforms")
        values = np.asarray(node["matrix"], dtype=np.float64)
        if values.shape != (16,) or not np.isfinite(values).all():
            raise ValueError("GLB node has an invalid matrix")
        result = values.reshape((4, 4), order="F")
    else:
        translation = np.asarray(node.get("translation", (0, 0, 0)), dtype=np.float64)
        scale = np.asarray(node.get("scale", (1, 1, 1)), dtype=np.float64)
        if (
            translation.shape != (3,)
            or scale.shape != (3,)
            or not np.isfinite(translation).all()
            or not np.isfinite(scale).all()
            or np.any(np.abs(scale) <= 1e-12)
        ):
            raise ValueError("GLB node has an invalid translation or scale")
        result = np.eye(4, dtype=np.float64)
        result[:3, :3] = _quaternion_matrix(node.get("rotation", (0, 0, 0, 1)))
        result[:3, :3] *= scale[np.newaxis, :]
        result[:3, 3] = translation
    if not np.allclose(result[3], (0, 0, 0, 1), atol=1e-9):
        raise ValueError("GLB node transform is not affine")
    return result


def _scene_roots(document: dict[str, Any]) -> list[int]:
    scenes = document.get("scenes", [])
    if scenes:
        scene_index = int(document.get("scene", 0))
        if not 0 <= scene_index < len(scenes):
            raise ValueError("GLB active scene index is out of range")
        return [int(value) for value in scenes[scene_index].get("nodes", [])]
    children = {
        int(child)
        for node in document.get("nodes", [])
        for child in node.get("children", [])
    }
    return [index for index in range(len(document.get("nodes", []))) if index not in children]


def _triangles_from_glb(payload: bytes) -> np.ndarray:
    document, binary = _read_glb(payload)
    structural: list[np.ndarray] = []
    nodes = document.get("nodes", [])
    meshes = document.get("meshes", [])
    visited: set[int] = set()

    def visit(node_index: int, parent_from_node_parent: np.ndarray, active: set[int]) -> None:
        if not 0 <= node_index < len(nodes):
            raise ValueError("GLB node index is out of range")
        if node_index in active:
            raise ValueError("GLB node hierarchy contains a cycle")
        if node_index in visited:
            raise ValueError("GLB node is referenced by more than one parent")
        visited.add(node_index)
        node = nodes[node_index]
        assembly_from_node = parent_from_node_parent @ _node_matrix(node)
        if "mesh" not in node:
            pass
        else:
            mesh_index = int(node["mesh"])
            if not 0 <= mesh_index < len(meshes):
                raise ValueError("GLB mesh index is out of range")
            mesh = meshes[mesh_index]
            extras = {**mesh.get("extras", {}), **node.get("extras", {})}
            ifc_class = str(extras.get("ifcClass") or "").casefold()
            # Tracking geometry must describe what physically exists before assembly.
            # A non-empty fastenerCategory also covers fabrication marks and uncertain
            # mechanical-fastener proxies that must never steer registration.
            excluded = ifc_class in {"ifcmechanicalfastener", "ifcfastener"} or bool(
                str(extras.get("fastenerCategory") or "").strip()
            )
            if not excluded:
                for primitive in mesh.get("primitives", []):
                    if int(primitive.get("mode", 4)) != 4:
                        continue
                    attributes = primitive.get("attributes", {})
                    if "POSITION" not in attributes:
                        continue
                    positions = _accessor_array(
                        document,
                        binary,
                        int(attributes["POSITION"]),
                    ).astype(np.float64, copy=False)
                    if positions.shape[1] != 3:
                        continue
                    homogeneous = np.column_stack((positions, np.ones(len(positions))))
                    positions = (homogeneous @ assembly_from_node.T)[:, :3]
                    if "indices" in primitive:
                        indices = _accessor_array(
                            document,
                            binary,
                            int(primitive["indices"]),
                        ).reshape((-1,)).astype(np.int64, copy=False)
                    else:
                        indices = np.arange(len(positions), dtype=np.int64)
                    indices = indices[: len(indices) // 3 * 3].reshape((-1, 3))
                    indices = indices[((indices >= 0) & (indices < len(positions))).all(axis=1)]
                    if len(indices):
                        structural.append(positions[indices])

        next_active = active | {node_index}
        for child in node.get("children", []):
            visit(int(child), assembly_from_node, next_active)

    for root in _scene_roots(document):
        visit(root, np.eye(4, dtype=np.float64), set())
    if not structural:
        raise ValueError("GLB contains no structural triangle geometry for registration")
    triangles = np.concatenate(structural, axis=0)
    vectors = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
    return triangles[np.linalg.norm(vectors, axis=1) > 1e-12]


def _radical_inverse(values: np.ndarray, base: int) -> np.ndarray:
    source = values.astype(np.int64, copy=True)
    result = np.zeros(len(source), dtype=np.float64)
    factor = 1.0 / base
    while np.any(source):
        result += (source % base) * factor
        source //= base
        factor /= base
    return result


def _sample_surface(
    triangles: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, float, np.ndarray, np.ndarray]:
    lower = triangles.reshape((-1, 3)).min(axis=0)
    upper = triangles.reshape((-1, 3)).max(axis=0)
    diameter = float(np.linalg.norm(upper - lower))
    if not math.isfinite(diameter) or diameter <= 1e-6:
        raise ValueError("Registration model has invalid physical dimensions")
    spacing = float(np.clip(diameter / 120.0, MINIMUM_SPACING_M, MAXIMUM_SPACING_M))

    cross = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
    double_area = np.linalg.norm(cross, axis=1)
    valid = double_area > 1e-12
    triangles = triangles[valid]
    double_area = double_area[valid]
    face_normals = cross[valid] / double_area[:, None]
    total_area = float(double_area.sum() * 0.5)
    wanted = int(round(total_area * 0.62 / (spacing * spacing)))
    count = int(np.clip(wanted, MINIMUM_POINT_COUNT, MAXIMUM_POINT_COUNT))

    cumulative = np.cumsum(double_area)
    targets = (np.arange(count, dtype=np.float64) + 0.5) * cumulative[-1] / count
    face_indices = np.searchsorted(cumulative, targets, side="left")
    sequence = np.arange(1, count + 1, dtype=np.int64)
    first = np.sqrt(_radical_inverse(sequence, 2))
    second = _radical_inverse(sequence, 3)
    chosen = triangles[face_indices]
    points = (
        (1.0 - first)[:, None] * chosen[:, 0]
        + (first * (1.0 - second))[:, None] * chosen[:, 1]
        + (first * second)[:, None] * chosen[:, 2]
    )
    return points, face_normals[face_indices], spacing, lower, upper


def _feature_edges(triangles: np.ndarray, spacing: float, diameter: float) -> np.ndarray:
    vectors = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
    lengths = np.linalg.norm(vectors, axis=1)
    valid = lengths > 1e-12
    triangles = triangles[valid]
    normals = vectors[valid] / lengths[valid, None]
    quantization = max(diameter * 1e-7, 1e-7)
    records: dict[
        tuple[tuple[int, int, int], tuple[int, int, int]],
        tuple[np.ndarray, np.ndarray, list[np.ndarray]],
    ] = {}
    for triangle, normal in zip(triangles, normals, strict=True):
        for first_index, second_index in ((0, 1), (1, 2), (2, 0)):
            first = triangle[first_index]
            second = triangle[second_index]
            first_key = tuple(np.rint(first / quantization).astype(np.int64).tolist())
            second_key = tuple(np.rint(second / quantization).astype(np.int64).tolist())
            if first_key <= second_key:
                key = (first_key, second_key)
                start, end = first, second
            else:
                key = (second_key, first_key)
                start, end = second, first
            record = records.get(key)
            if record is None:
                records[key] = (start.copy(), end.copy(), [normal])
            else:
                record[2].append(normal)

    cosine_limit = math.cos(math.radians(FEATURE_EDGE_DEGREES))
    candidates: list[tuple[float, tuple[int, ...], np.ndarray]] = []
    for key, (start, end, edge_normals) in records.items():
        length = float(np.linalg.norm(end - start))
        if length < spacing * 0.45:
            continue
        is_feature = len(edge_normals) == 1
        if not is_feature:
            for index, first in enumerate(edge_normals):
                if any(abs(float(np.dot(first, second))) < cosine_limit for second in edge_normals[index + 1 :]):
                    is_feature = True
                    break
        if is_feature:
            flat_key = key[0] + key[1]
            candidates.append((length, flat_key, np.concatenate((start, end))))
    candidates.sort(key=lambda item: (-item[0], item[1]))
    if not candidates:
        return np.empty((0, 6), dtype=np.float32)
    return np.asarray([item[2] for item in candidates[:MAXIMUM_EDGE_COUNT]], dtype=np.float32)


def build_registration_model(glb_payload: bytes) -> tuple[bytes, RegistrationModelInfo]:
    """Return a deterministic QSRG model and its inspectable metadata."""

    triangles = _triangles_from_glb(glb_payload)
    points, normals, spacing, lower, upper = _sample_surface(triangles)
    diameter = float(np.linalg.norm(upper - lower))
    edges = _feature_edges(triangles, spacing, diameter)
    point_records = np.column_stack((points, normals)).astype("<f4", copy=False)
    edge_records = edges.astype("<f4", copy=False)
    stored_lower = lower.astype("<f4")
    stored_upper = upper.astype("<f4")
    stored_spacing = float(np.float32(spacing))
    flags = FLAG_NORMALS | (FLAG_FEATURE_EDGES if len(edge_records) else 0)
    header = struct.pack(
        HEADER_FORMAT,
        MAGIC,
        VERSION_MAJOR,
        VERSION_MINOR,
        len(point_records),
        len(edge_records),
        *[float(value) for value in stored_lower],
        *[float(value) for value in stored_upper],
        stored_spacing,
        flags,
    )
    payload = header + point_records.tobytes(order="C") + edge_records.tobytes(order="C")
    return payload, RegistrationModelInfo(
        point_count=len(point_records),
        edge_count=len(edge_records),
        bounds_min=tuple(float(value) for value in stored_lower),
        bounds_max=tuple(float(value) for value in stored_upper),
        sample_spacing_m=stored_spacing,
    )


def inspect_registration_model(payload: bytes) -> RegistrationModelInfo:
    """Validate a QSRG payload without allocating its point arrays."""

    if len(payload) < HEADER_SIZE:
        raise ValueError("Registration model is shorter than its header")
    unpacked = struct.unpack_from(HEADER_FORMAT, payload, 0)
    magic, major, minor, point_count, edge_count = unpacked[:5]
    if magic != MAGIC or major != VERSION_MAJOR or minor > VERSION_MINOR:
        raise ValueError("Unsupported registration model format")
    if not 1 <= point_count <= MAXIMUM_POINT_COUNT or not 0 <= edge_count <= MAXIMUM_EDGE_COUNT:
        raise ValueError("Registration model contains an invalid record count")
    expected = HEADER_SIZE + (point_count + edge_count) * 6 * 4
    if len(payload) != expected:
        raise ValueError("Registration model byte length does not match its header")
    values = [float(value) for value in unpacked[5:12]]
    if not all(math.isfinite(value) for value in values) or values[6] <= 0:
        raise ValueError("Registration model contains invalid metric values")
    return RegistrationModelInfo(
        point_count=point_count,
        edge_count=edge_count,
        bounds_min=tuple(values[0:3]),
        bounds_max=tuple(values[3:6]),
        sample_spacing_m=values[6],
    )
