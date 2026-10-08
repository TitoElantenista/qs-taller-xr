"""Build a portable, checksummed QS XR package for the Android application."""

from __future__ import annotations

import base64
import hashlib
import json
import struct
import uuid
import zipfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import ifcopenshell.guid

from .catalog import SourcePackage
from .registration_model import build_registration_model


PACKAGE_SCHEMA_VERSION = "2.0.0"
DOCUMENT_SCHEMA_VERSION = "1.0.0"
PACKAGE_NAMESPACE = uuid.UUID("53a1209f-e62d-47f7-92cd-e8dd37b53dde")
THUMBNAIL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)
MAIN_PART_CLASSES = {"IfcBeam", "IfcColumn", "IfcMember", "IfcPlate"}


def _json_bytes(document: dict[str, Any]) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _ifc_uuid(global_id: str) -> str:
    try:
        return str(uuid.UUID(hex=ifcopenshell.guid.expand(global_id)))
    except (ValueError, TypeError):
        return str(uuid.uuid5(PACKAGE_NAMESPACE, f"ifc:{global_id}"))


def _read_glb_json(payload: bytes) -> tuple[dict[str, Any], bytes]:
    if len(payload) < 20:
        raise ValueError("GLB is too short")
    magic, version, declared_length = struct.unpack_from("<4sII", payload, 0)
    if magic != b"glTF" or version != 2 or declared_length != len(payload):
        raise ValueError("Invalid GLB header")
    json_length, chunk_type = struct.unpack_from("<I4s", payload, 12)
    if chunk_type != b"JSON":
        raise ValueError("GLB first chunk is not JSON")
    json_end = 20 + json_length
    document = json.loads(payload[20:json_end].rstrip(b" \0").decode("utf-8"))
    return document, payload[json_end:]


def _write_glb_json(document: dict[str, Any], remaining_chunks: bytes) -> bytes:
    json_chunk = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode(
        "utf-8"
    )
    json_chunk += b" " * (-len(json_chunk) % 4)
    total_length = 12 + 8 + len(json_chunk) + len(remaining_chunks)
    return b"".join(
        (
            struct.pack("<4sII", b"glTF", 2, total_length),
            struct.pack("<I4s", len(json_chunk), b"JSON"),
            json_chunk,
            remaining_chunks,
        )
    )


def _part_kind(
    ifc_class: str,
    name: str,
    fastener_category: str | None = None,
) -> tuple[str, bool]:
    if fastener_category == "PHYSICAL_FASTENER":
        return "BOLT_GROUP", False
    if fastener_category == "FABRICATION_MARK":
        return "FABRICATION_MARK", False
    if fastener_category == "UNKNOWN_MECHANICAL_FASTENER":
        return "OTHER", False
    if ifc_class in {"IfcMechanicalFastener", "IfcFastener"}:
        return "BOLT_GROUP", False
    if ifc_class == "IfcPlate":
        return "PLATE", True
    if ifc_class in {"IfcBeam", "IfcColumn", "IfcMember"}:
        return "SECONDARY_PART", True
    return "OTHER", not any(word in name.casefold() for word in ("weld", "schweiß", "schweiss"))


def _mobile_glb_and_parts(
    glb_payload: bytes,
    assembly_guid: str,
) -> tuple[bytes, dict[str, Any]]:
    gltf, remaining_chunks = _read_glb_json(glb_payload)
    parts: list[dict[str, Any]] = []
    main_candidates: list[tuple[float, int]] = []
    for node_index, node in enumerate(gltf.get("nodes", [])):
        if "mesh" not in node:
            continue
        mesh = gltf["meshes"][int(node["mesh"])]
        extras = {**mesh.get("extras", {}), **node.get("extras", {})}
        source_global_id = str(extras.get("sourceGlobalId") or "")
        if not source_global_id:
            raise ValueError(f"GLB node {node_index} has no sourceGlobalId")
        guid = _ifc_uuid(source_global_id)
        node_name = f"part:{guid}"
        node["name"] = node_name
        mesh["name"] = node_name

        primitive = mesh["primitives"][0]
        position_accessor = gltf["accessors"][primitive["attributes"]["POSITION"]]
        lower = [float(value) for value in position_accessor["min"]]
        upper = [float(value) for value in position_accessor["max"]]
        extent = [high - low for low, high in zip(lower, upper)]
        ifc_class = str(extras.get("ifcClass") or "")
        display_name = str(extras.get("name") or node_name)
        kind, inspectable = _part_kind(
            ifc_class,
            display_name,
            str(extras.get("fastenerCategory") or "") or None,
        )
        position_number = str(extras.get("partPositionNumber") or f"SIN-POS-{guid[:8]}")
        properties = {
            key: value
            for key in (
                "ifcClass",
                "name",
                "description",
                "objectType",
                "typeName",
                "profile",
                "material",
                "weightKg",
                "surfaceAreaM2",
                "phase",
                "comment",
                "classAttribute",
                "assemblyPositionNumber",
                "partDrawingNumber",
                "partNumber",
                "externalFastener",
                "fastenerScope",
                "connectionDetection",
                "ownerAssemblySourceGlobalId",
                "ownerAssemblyPositionNumber",
                "ownerAssemblyName",
                "connectedAssemblySourceGlobalId",
                "connectedFastenerItemCount",
                "fastenerItemCount",
                "fastenerCategory",
                "fastenerStandard",
                "fastenerCatalogName",
                "fastenerClassificationReason",
                "holeDiameterXmm",
                "holeDiameterYmm",
                "generatedFabricationHoles",
            )
            if (value := extras.get(key)) is not None and isinstance(value, (str, int, float, bool))
        }
        parts.append(
            {
                "guid": guid,
                "positionNumber": position_number,
                "kind": kind,
                "gltfNodeName": node_name,
                "boundsAssemblyM": {"min": lower, "max": upper},
                "inspectable": inspectable,
                "properties": properties,
            }
        )
        profile = str(properties.get("profile") or "").strip().casefold()
        if profile:
            for duplicate_key in ("typeName", "objectType"):
                duplicate = str(properties.get(duplicate_key) or "").strip().casefold()
                if duplicate == profile:
                    properties.pop(duplicate_key)
        if inspectable and ifc_class in MAIN_PART_CLASSES:
            main_candidates.append((extent[0] * extent[1] * extent[2], len(parts) - 1))

    if not parts:
        raise ValueError("GLB contains no part nodes")
    if main_candidates:
        _, main_index = max(main_candidates)
        parts[main_index]["kind"] = "MAIN_PART"
    gltf.setdefault("extras", {})["mobilePackage"] = {
        "schemaVersion": PACKAGE_SCHEMA_VERSION,
        "assemblyGuid": assembly_guid,
    }
    return _write_glb_json(gltf, remaining_chunks), {
        "schemaVersion": DOCUMENT_SCHEMA_VERSION,
        "assemblyGuid": assembly_guid,
        "parts": parts,
    }


def build_mobile_package(
    package: SourcePackage,
    occurrence: dict[str, Any],
    glb_path: Path,
    output_path: Path,
) -> Path:
    """Create a complete .qsxr ZIP package and atomically replace output_path."""

    occurrence_id = str(occurrence["sourceGlobalId"])
    assembly_guid = _ifc_uuid(occurrence_id)
    revision = int(package.raw.get("revision", 1))
    mobile_glb, parts = _mobile_glb_and_parts(glb_path.read_bytes(), assembly_guid)
    registration_model, _ = build_registration_model(mobile_glb)
    project_uuid = str(uuid.uuid5(PACKAGE_NAMESPACE, f"project:{package.project_id}"))
    identity_hash = _sha256(mobile_glb + registration_model)
    package_uuid = str(
        uuid.uuid5(
            PACKAGE_NAMESPACE,
            f"{package.project_id}:{package.source_id}:{revision}:{occurrence_id}:{identity_hash}",
        )
    )
    manifest = {
        "schemaVersion": PACKAGE_SCHEMA_VERSION,
        "packageId": package_uuid,
        "createdAt": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "producer": {"name": "QS Desktop Viewer", "version": "0.1.0"},
        "project": {"projectId": project_uuid, "name": package.project_name},
        "assembly": {
            "guid": assembly_guid,
            "positionNumber": package.position_number,
            "revision": revision,
        },
        "coordinateSystem": {
            "handedness": "RIGHT_HANDED",
            "upAxis": "Y",
            "linearUnit": "m",
            "matrixStorage": "COLUMN_MAJOR",
            "vectorConvention": "COLUMN_VECTOR",
            "transformNotation": "PARENT_FROM_CHILD",
        },
        "files": {
            "model": "assembly.glb",
            "registrationModel": "registration_model.bin",
            "parts": "parts.json",
            "thumbnail": "thumbnail.png",
            "checksums": "checksums.json",
        },
    }
    contents = {
        "manifest.json": _json_bytes(manifest),
        "assembly.glb": mobile_glb,
        "registration_model.bin": registration_model,
        "parts.json": _json_bytes(parts),
        "thumbnail.png": THUMBNAIL_PNG,
    }
    checksums = {
        "schemaVersion": DOCUMENT_SCHEMA_VERSION,
        "algorithm": "SHA-256",
        "files": [
            {"path": name, "sha256": _sha256(payload), "byteLength": len(payload)}
            for name, payload in contents.items()
        ],
    }
    contents["checksums.json"] = _json_bytes(checksums)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in contents.items():
            archive.writestr(name, payload)
    temporary.replace(output_path)
    return output_path
