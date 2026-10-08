"""Export a vendor-neutral CAD tracking target from an existing ``.qsxr``.

This is deliberately an offline PoC tool.  It does not add files to the QSXR
contract and it does not alter the rendering model used by desktop or Android.
The emitted GLB and OBJ contain only triangle geometry that is physically structural
at the inspection stage; fasteners and fabrication marks are removed. The OBJ is the
exact GPU raster target and QSRG is an auxiliary deterministic set of samples,
normals and feature edges. The exporter performs no network operation and marks
every result as forbidden for cloud or external processing.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import re
import struct
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any

import numpy as np

from .registration_model import (
    _accessor_array,
    _read_glb,
    build_registration_model,
)


SCHEMA_VERSION = "2.0.0"
PROVISIONING_BINDING_VERSION = "1.0.0"
TARGET_FRAME = "QSXR_ASSEMBLY"
TARGET_ORIGIN = "QSXR_ASSEMBLY_LOCAL_ORIGIN"
FASTENER_IFC_CLASSES = frozenset({"ifcmechanicalfastener", "ifcfastener"})
FASTENER_PART_KINDS = frozenset({"BOLT_GROUP", "FABRICATION_MARK"})
IDENTITY_COLUMN_MAJOR = [
    1.0,
    0.0,
    0.0,
    0.0,
    0.0,
    1.0,
    0.0,
    0.0,
    0.0,
    0.0,
    1.0,
    0.0,
    0.0,
    0.0,
    0.0,
    1.0,
]


@dataclass(frozen=True)
class TrackingTargetArtifact:
    glb: bytes
    obj: bytes
    registration: bytes
    metadata: dict[str, Any]


@dataclass(frozen=True)
class TrackingTargetFiles:
    glb_path: Path
    obj_path: Path
    registration_path: Path
    metadata_path: Path
    metadata: dict[str, Any]


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _json_bytes(document: dict[str, Any]) -> bytes:
    return (
        json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def _safe_archive_member(value: Any, label: str) -> str:
    name = str(value or "")
    path = PurePosixPath(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError(f"QSXR {label} path is unsafe")
    return name


def _json_member(archive: zipfile.ZipFile, name: str) -> dict[str, Any]:
    try:
        document = json.loads(archive.read(name))
    except KeyError as exc:
        raise ValueError(f"QSXR has no {name}") from exc
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"QSXR {name} is not valid JSON") from exc
    if not isinstance(document, dict):
        raise ValueError(f"QSXR {name} must contain a JSON object")
    return document


def _package_inputs(
    payload: bytes,
) -> tuple[dict[str, Any], dict[str, Any], bytes, bytes]:
    try:
        archive_context = zipfile.ZipFile(io.BytesIO(payload))
    except zipfile.BadZipFile as exc:
        raise ValueError("Input is not a valid QSXR ZIP archive") from exc
    with archive_context as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("QSXR contains duplicate archive members")
        manifest = _json_member(archive, "manifest.json")
        files = manifest.get("files")
        if not isinstance(files, dict):
            raise ValueError("QSXR manifest has no files object")
        model_name = _safe_archive_member(
            files.get("model", "assembly.glb"),
            "model",
        )
        registration_name = _safe_archive_member(
            files.get("registrationModel"),
            "registration model",
        )
        try:
            model = archive.read(model_name)
        except KeyError as exc:
            raise ValueError(f"QSXR has no declared model {model_name}") from exc
        try:
            registration = archive.read(registration_name)
        except KeyError as exc:
            raise ValueError(
                f"QSXR has no declared registration model {registration_name}"
            ) from exc
        parts_name = str(files.get("parts", "parts.json"))
        parts = _json_member(archive, _safe_archive_member(parts_name, "parts"))

        checksums_name = files.get("checksums")
        if checksums_name:
            checksums = _json_member(
                archive,
                _safe_archive_member(checksums_name, "checksums"),
            )
            checksum_files = checksums.get("files")
            if not isinstance(checksum_files, list):
                raise ValueError("QSXR checksums.json has no files array")
            for member_name, member_payload, label in (
                (model_name, model, "model"),
                (registration_name, registration, "registration model"),
            ):
                records = [
                    record
                    for record in checksum_files
                    if isinstance(record, dict) and record.get("path") == member_name
                ]
                if len(records) != 1:
                    raise ValueError(
                        f"QSXR checksums.json must identify the declared {label} exactly once"
                    )
                record = records[0]
                if int(record.get("byteLength", -1)) != len(member_payload):
                    raise ValueError(
                        f"QSXR {label} length does not match checksums.json"
                    )
                if str(record.get("sha256", "")).casefold() != _sha256(member_payload):
                    raise ValueError(
                        f"QSXR {label} SHA-256 does not match checksums.json"
                    )
    return manifest, parts, model, registration


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


def _primitive_indices(
    document: dict[str, Any],
    binary: bytes,
    primitive: dict[str, Any],
    vertex_count: int,
) -> np.ndarray:
    if "indices" in primitive:
        sequence = _accessor_array(document, binary, int(primitive["indices"]))
        sequence = sequence.reshape((-1,)).astype(np.int64, copy=False)
    else:
        sequence = np.arange(vertex_count, dtype=np.int64)
    if len(sequence) and ((sequence < 0).any() or (sequence >= vertex_count).any()):
        raise ValueError("GLB primitive contains an out-of-range vertex index")

    mode = int(primitive.get("mode", 4))
    if mode == 4:
        if len(sequence) % 3:
            raise ValueError("GLB triangle primitive index count is not divisible by three")
        return sequence.reshape((-1, 3))
    if mode == 5:
        result = []
        for offset in range(max(0, len(sequence) - 2)):
            triangle = [sequence[offset], sequence[offset + 1], sequence[offset + 2]]
            if offset % 2:
                triangle[0], triangle[1] = triangle[1], triangle[0]
            result.append(triangle)
        return np.asarray(result, dtype=np.int64).reshape((-1, 3))
    if mode == 6:
        return np.asarray(
            [[sequence[0], sequence[index], sequence[index + 1]] for index in range(1, len(sequence) - 1)],
            dtype=np.int64,
        ).reshape((-1, 3))
    return np.empty((0, 3), dtype=np.int64)


def _part_index(parts: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        str(part["gltfNodeName"]): part
        for part in parts.get("parts", [])
        if isinstance(part, dict) and part.get("gltfNodeName")
    }


def _exclusion_reason(
    node: dict[str, Any],
    mesh: dict[str, Any],
    part: dict[str, Any] | None,
) -> str | None:
    extras = {**mesh.get("extras", {}), **node.get("extras", {})}
    if str(extras.get("ifcClass", "")).casefold() in FASTENER_IFC_CLASSES:
        return "ifc-fastener-class"
    if str(extras.get("fastenerCategory", "")).strip():
        return "fastener-category"
    if part and str(part.get("kind", "")).upper() in FASTENER_PART_KINDS:
        return "parts-fastener-kind"
    return None


def _active_scene_roots(document: dict[str, Any]) -> list[int]:
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


def _structural_triangles(
    payload: bytes,
    parts: dict[str, Any],
) -> tuple[np.ndarray, dict[str, Any]]:
    document, binary = _read_glb(payload)
    nodes = document.get("nodes", [])
    meshes = document.get("meshes", [])
    parts_by_node = _part_index(parts)
    structural: list[np.ndarray] = []
    included_classes: Counter[str] = Counter()
    excluded_reasons: Counter[str] = Counter()
    included_nodes = 0
    excluded_nodes = 0
    ignored_non_triangles = 0
    source_triangle_count = 0

    def visit(node_index: int, parent_from_node: np.ndarray, ancestors: frozenset[int]) -> None:
        nonlocal included_nodes, excluded_nodes, ignored_non_triangles, source_triangle_count
        if node_index in ancestors:
            raise ValueError("GLB node graph contains a cycle")
        if not 0 <= node_index < len(nodes):
            raise ValueError("GLB scene references an out-of-range node")
        node = nodes[node_index]
        world_from_node = parent_from_node @ _node_matrix(node)
        if "mesh" in node:
            mesh_index = int(node["mesh"])
            if not 0 <= mesh_index < len(meshes):
                raise ValueError("GLB node references an out-of-range mesh")
            mesh = meshes[mesh_index]
            part = parts_by_node.get(str(node.get("name", "")))
            reason = _exclusion_reason(node, mesh, part)
            extras = {**mesh.get("extras", {}), **node.get("extras", {})}
            node_triangles: list[np.ndarray] = []
            for primitive in mesh.get("primitives", []):
                attributes = primitive.get("attributes", {})
                if "POSITION" not in attributes:
                    continue
                positions = _accessor_array(
                    document,
                    binary,
                    int(attributes["POSITION"]),
                ).astype(np.float64, copy=False)
                if positions.ndim != 2 or positions.shape[1] != 3:
                    raise ValueError("GLB POSITION accessor is not VEC3")
                indices = _primitive_indices(
                    document,
                    binary,
                    primitive,
                    len(positions),
                )
                if int(primitive.get("mode", 4)) not in {4, 5, 6}:
                    ignored_non_triangles += 1
                    continue
                source_triangle_count += len(indices)
                if reason or not len(indices):
                    continue
                homogeneous = np.column_stack((positions, np.ones(len(positions))))
                world_positions = (world_from_node @ homogeneous.T).T[:, :3]
                triangles = world_positions[indices]
                if np.linalg.det(world_from_node[:3, :3]) < 0:
                    triangles = triangles[:, (0, 2, 1)]
                node_triangles.append(triangles)
            if reason:
                excluded_nodes += 1
                excluded_reasons[reason] += 1
            elif node_triangles:
                joined = np.concatenate(node_triangles, axis=0)
                vectors = np.cross(joined[:, 1] - joined[:, 0], joined[:, 2] - joined[:, 0])
                joined = joined[np.linalg.norm(vectors, axis=1) > 1e-12]
                if len(joined):
                    structural.append(joined)
                    included_nodes += 1
                    included_classes[str(extras.get("ifcClass") or "Unclassified")] += 1
        next_ancestors = ancestors | {node_index}
        for child in node.get("children", []):
            visit(int(child), world_from_node, next_ancestors)

    for root in _active_scene_roots(document):
        visit(root, np.eye(4, dtype=np.float64), frozenset())
    if not structural:
        raise ValueError("QSXR model contains no structural triangle geometry")
    triangles = np.concatenate(structural, axis=0)
    if not np.isfinite(triangles).all():
        raise ValueError("QSXR structural geometry contains non-finite positions")
    return triangles, {
        "sourceTriangleCount": source_triangle_count,
        "includedTriangleCount": len(triangles),
        "includedNodeCount": included_nodes,
        "excludedNodeCount": excluded_nodes,
        "ignoredNonTrianglePrimitiveCount": ignored_non_triangles,
        "includedIfcClasses": dict(sorted(included_classes.items())),
        "excludedByReason": dict(sorted(excluded_reasons.items())),
    }


def _build_glb(triangles: np.ndarray, source: dict[str, str]) -> bytes:
    positions = triangles.reshape((-1, 3)).astype("<f4", copy=False)
    cross = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
    face_normals = cross / np.linalg.norm(cross, axis=1)[:, None]
    normals = np.repeat(face_normals, 3, axis=0).astype("<f4", copy=False)
    position_bytes = positions.tobytes(order="C")
    normal_bytes = normals.tobytes(order="C")
    binary = position_bytes + normal_bytes
    lower = positions.min(axis=0)
    upper = positions.max(axis=0)
    document = {
        "asset": {
            "version": "2.0",
            "generator": "QS vendor-neutral CAD tracking target 1.0",
        },
        "scene": 0,
        "scenes": [{"name": "CAD tracking target", "nodes": [0]}],
        "nodes": [{"name": "tracking-geometry", "mesh": 0}],
        "meshes": [
            {
                "name": "tracking-geometry",
                "primitives": [
                    {
                        "attributes": {"POSITION": 0, "NORMAL": 1},
                        "material": 0,
                        "mode": 4,
                    }
                ],
            }
        ],
        "materials": [
            {
                "name": "neutral-structural-surface",
                "pbrMetallicRoughness": {
                    "baseColorFactor": [0.55, 0.58, 0.62, 1.0],
                    "metallicFactor": 0.0,
                    "roughnessFactor": 1.0,
                },
                "doubleSided": True,
            }
        ],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": [
            {
                "buffer": 0,
                "byteOffset": 0,
                "byteLength": len(position_bytes),
                "target": 34962,
            },
            {
                "buffer": 0,
                "byteOffset": len(position_bytes),
                "byteLength": len(normal_bytes),
                "target": 34962,
            },
        ],
        "accessors": [
            {
                "bufferView": 0,
                "componentType": 5126,
                "count": len(positions),
                "type": "VEC3",
                "min": [float(value) for value in lower],
                "max": [float(value) for value in upper],
            },
            {
                "bufferView": 1,
                "componentType": 5126,
                "count": len(normals),
                "type": "VEC3",
            },
        ],
        "extras": {
            "trackingTarget": {
                "schemaVersion": SCHEMA_VERSION,
                "linearUnit": "m",
                "targetFrame": TARGET_FRAME,
                "targetOrigin": TARGET_ORIGIN,
                "assemblyFromTarget": IDENTITY_COLUMN_MAJOR,
                "sourcePackageId": source["packageId"],
                "sourcePackageSha256": source["packageSha256"],
                "sourceAssemblyGuid": source["assemblyGuid"],
                "sourceAssemblyGlbSha256": source["assemblyGlbSha256"],
            }
        },
    }
    json_chunk = json.dumps(
        document,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    json_chunk += b" " * (-len(json_chunk) % 4)
    binary += b"\0" * (-len(binary) % 4)
    total_length = 12 + 8 + len(json_chunk) + 8 + len(binary)
    return b"".join(
        (
            struct.pack("<4sII", b"glTF", 2, total_length),
            struct.pack("<I4s", len(json_chunk), b"JSON"),
            json_chunk,
            struct.pack("<I4s", len(binary), b"BIN\0"),
            binary,
        )
    )


def _obj_number(value: np.float32) -> str:
    number = float(value)
    if number == 0.0:
        return "0"
    return format(number, ".9g")


def _build_obj(triangles: np.ndarray, source: dict[str, str]) -> bytes:
    """Return a deterministic metre-based OBJ with the same triangle soup as the GLB."""

    positions = triangles.reshape((-1, 3)).astype("<f4", copy=False)
    lines = [
        "# QS vendor-neutral CAD tracking target 1.0",
        "# linear_unit: metre",
        f"# target_frame: {TARGET_FRAME}",
        f"# target_origin: {TARGET_ORIGIN}",
        "# assembly_from_target: identity",
        f"# source_package_id: {source['packageId']}",
        f"# source_package_sha256: {source['packageSha256']}",
        f"# source_assembly_guid: {source['assemblyGuid']}",
        f"# source_assembly_glb_sha256: {source['assemblyGlbSha256']}",
        "o tracking_geometry",
    ]
    lines.extend(
        "v " + " ".join(_obj_number(value) for value in position)
        for position in positions
    )
    lines.append("s off")
    lines.extend(
        f"f {index} {index + 1} {index + 2}"
        for index in range(1, len(positions) + 1, 3)
    )
    return ("\n".join(lines) + "\n").encode("ascii")


def build_tracking_target(qsxr_payload: bytes, source_name: str = "source.qsxr") -> TrackingTargetArtifact:
    """Build deterministic structural GLB/OBJ targets and auditable metadata."""

    manifest, parts, source_model, source_registration = _package_inputs(qsxr_payload)
    coordinates = manifest.get("coordinateSystem", {})
    if coordinates.get("linearUnit") != "m":
        raise ValueError("Tracking target export requires a metre-based QSXR")
    if coordinates.get("handedness") != "RIGHT_HANDED":
        raise ValueError("Tracking target export requires a right-handed QSXR")
    if coordinates.get("upAxis") != "Y":
        raise ValueError("Tracking target export requires the canonical Y-up QSXR frame")
    if coordinates.get("matrixStorage") != "COLUMN_MAJOR":
        raise ValueError("Tracking target export requires column-major QSXR matrices")
    if coordinates.get("vectorConvention") != "COLUMN_VECTOR":
        raise ValueError("Tracking target export requires the QSXR column-vector convention")
    if coordinates.get("transformNotation") != "PARENT_FROM_CHILD":
        raise ValueError("Tracking target export requires parentFromChild transforms")

    package_id = manifest.get("packageId")
    assembly = manifest.get("assembly")
    if not isinstance(package_id, str) or not package_id.strip():
        raise ValueError("Tracking target export requires a QSXR packageId")
    if not isinstance(assembly, dict):
        raise ValueError("Tracking target export requires QSXR assembly identity")
    assembly_guid = assembly.get("guid")
    if not isinstance(assembly_guid, str) or not assembly_guid.strip():
        raise ValueError("Tracking target export requires an assembly guid")

    source_model_hash = _sha256(source_model)
    source_package_hash = _sha256(qsxr_payload)
    source_identity = {
        "packageId": package_id,
        "packageSha256": source_package_hash,
        "assemblyGuid": assembly_guid,
        "assemblyGlbSha256": source_model_hash,
    }
    triangles, filtering = _structural_triangles(source_model, parts)
    target = _build_glb(triangles, source_identity)
    obj_target = _build_obj(triangles, source_identity)
    registration_payload, registration = build_registration_model(target)
    if registration_payload != source_registration:
        raise ValueError(
            "Tracking target QSRG does not match the QSXR registration reference"
        )
    lower = np.asarray(registration.bounds_min, dtype=np.float64)
    upper = np.asarray(registration.bounds_max, dtype=np.float64)
    dimensions = upper - lower
    if not np.isfinite(dimensions).all() or np.any(dimensions < 0) or not np.any(dimensions > 1e-6):
        raise ValueError("Tracking target has invalid physical bounds")

    target_coordinates = {
        "frame": TARGET_FRAME,
        "origin": TARGET_ORIGIN,
        "handedness": coordinates.get("handedness"),
        "upAxis": coordinates.get("upAxis"),
        "linearUnit": coordinates.get("linearUnit"),
        "matrixStorage": coordinates.get("matrixStorage"),
        "vectorConvention": coordinates.get("vectorConvention"),
        "transformNotation": coordinates.get("transformNotation"),
        "assemblyFromTarget": IDENTITY_COLUMN_MAJOR,
    }
    registration_hash = _sha256(registration_payload)
    obj_hash = _sha256(obj_target)
    target_hash = _sha256(target)
    metadata: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "source": {
            "fileName": Path(source_name).name,
            "packageSha256": source_package_hash,
            "assemblyGlbSha256": source_model_hash,
            "packageId": package_id,
            "assemblyGuid": assembly_guid,
            "positionNumber": assembly.get("positionNumber"),
            "revision": assembly.get("revision"),
        },
        "dataHandling": {
            "classification": "CONFIDENTIAL_COMPANY_CAD",
            "cloudUploadAllowed": False,
            "externalProcessingAllowed": False,
            "allowedProcessing": ["ON_DEVICE", "ON_PREMISE_LAN"],
            "exporterUsesNetwork": False,
        },
        "coordinateSystem": {
            **target_coordinates,
            "geometryFlattenedInAssemblyCoordinates": True,
        },
        "filter": {
            "excludedIfcClasses": ["IfcFastener", "IfcMechanicalFastener"],
            "excludeAnyFastenerCategory": True,
            **filtering,
        },
        "geometry": {
            "triangleCount": filtering["includedTriangleCount"],
            "vertexCount": filtering["includedTriangleCount"] * 3,
            "boundsM": {
                "min": [float(value) for value in lower],
                "max": [float(value) for value in upper],
            },
            "dimensionsM": [float(value) for value in dimensions],
            "diagonalM": float(np.linalg.norm(dimensions)),
        },
        "registrationReference": {
            "builder": "QSRG-1.0",
            "format": "QS registration model 1.0",
            "byteLength": len(registration_payload),
            "sha256": registration_hash,
            "pointCount": registration.point_count,
            "featureEdgeCount": registration.edge_count,
            "sampleSpacingM": registration.sample_spacing_m,
        },
        "target": {
            "format": "glTF Binary 2.0",
            "byteLength": len(target),
            "sha256": target_hash,
        },
        "objTarget": {
            "format": "Wavefront OBJ",
            "linearUnit": "m",
            "byteLength": len(obj_target),
            "sha256": obj_hash,
        },
        "provisioningBinding": {
            "schemaVersion": PROVISIONING_BINDING_VERSION,
            "targetId": package_id,
            "package": {
                "packageId": package_id,
                "sha256": source_package_hash,
            },
            "sourceAssembly": {
                "guid": assembly_guid,
                "glbSha256": source_model_hash,
            },
            "artifacts": {
                "registrationReference": {
                    "sha256": registration_hash,
                },
                "rasterModel": {
                    "sha256": obj_hash,
                },
                "trackingGlb": {
                    "sha256": target_hash,
                },
            },
            "targetCoordinates": target_coordinates,
        },
    }
    return TrackingTargetArtifact(
        glb=target,
        obj=obj_target,
        registration=registration_payload,
        metadata=metadata,
    )


def _safe_base_name(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("._-")
    return cleaned or "cad-target"


def _atomic_write(path: Path, payload: bytes) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_bytes(payload)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def export_tracking_target(
    source_path: Path,
    output_dir: Path,
    *,
    base_name: str | None = None,
    force: bool = False,
) -> TrackingTargetFiles:
    source_path = source_path.resolve()
    artifact = build_tracking_target(source_path.read_bytes(), source_path.name)
    inferred = str(artifact.metadata["source"].get("positionNumber") or source_path.stem)
    base = _safe_base_name(base_name or inferred)
    output_dir.mkdir(parents=True, exist_ok=True)
    glb_path = output_dir / f"{base}-tracking.glb"
    obj_path = output_dir / f"{base}-tracking.obj"
    registration_path = output_dir / f"{base}-tracking.qsrg"
    metadata_path = output_dir / f"{base}-tracking.json"
    if not force and (
        glb_path.exists()
        or obj_path.exists()
        or registration_path.exists()
        or metadata_path.exists()
    ):
        raise FileExistsError(
            f"Tracking target already exists for {base}; pass --force to replace it"
        )
    metadata = json.loads(json.dumps(artifact.metadata))
    metadata["target"]["fileName"] = glb_path.name
    metadata["objTarget"]["fileName"] = obj_path.name
    metadata["registrationReference"]["fileName"] = registration_path.name
    bound_artifacts = metadata["provisioningBinding"]["artifacts"]
    bound_artifacts["trackingGlb"]["fileName"] = glb_path.name
    bound_artifacts["rasterModel"]["fileName"] = obj_path.name
    bound_artifacts["registrationReference"]["fileName"] = registration_path.name
    _atomic_write(glb_path, artifact.glb)
    _atomic_write(obj_path, artifact.obj)
    _atomic_write(registration_path, artifact.registration)
    _atomic_write(metadata_path, _json_bytes(metadata))
    return TrackingTargetFiles(
        glb_path=glb_path,
        obj_path=obj_path,
        registration_path=registration_path,
        metadata_path=metadata_path,
        metadata=metadata,
    )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Extract structural, metre-based CAD tracking GLB/OBJ from QSXR",
    )
    result.add_argument("source", type=Path, help="Input .qsxr package")
    result.add_argument("--output-dir", type=Path, required=True)
    result.add_argument("--name", help="Output base name (defaults to Positionsnummer)")
    result.add_argument("--force", action="store_true", help="Replace existing outputs")
    return result


def main() -> None:
    arguments = parser().parse_args()
    files = export_tracking_target(
        arguments.source,
        arguments.output_dir,
        base_name=arguments.name,
        force=arguments.force,
    )
    print(
        json.dumps(
            {
                "glb": str(files.glb_path),
                "obj": str(files.obj_path),
                "registration": str(files.registration_path),
                "metadata": str(files.metadata_path),
                "dimensionsM": files.metadata["geometry"]["dimensionsM"],
                "sha256": files.metadata["target"]["sha256"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
