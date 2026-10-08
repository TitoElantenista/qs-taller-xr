#!/usr/bin/env python3
"""Validate XR schemas, example documents and cross-file package invariants."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
import sys
from pathlib import Path
from typing import Any, Iterable

try:
    from jsonschema import FormatChecker
    from jsonschema.validators import validator_for
    from referencing import Registry, Resource
except ImportError as exc:  # pragma: no cover - exercised only without dev dependencies
    raise SystemExit(
        "Missing validation dependencies. Run: "
        "python3 -m pip install -r packages/xr-contracts/requirements-dev.txt"
    ) from exc


CONTRACT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PACKAGE = CONTRACT_ROOT / "examples" / "BG12345" / "revision-4"
DEFAULT_PROTOCOL = CONTRACT_ROOT / "examples" / "inspection-protocol.json"

DOCUMENT_SCHEMAS = {
    "manifest.json": "manifest.schema.json",
    "parts.json": "parts.schema.json",
    "checksums.json": "checksums.schema.json",
}


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_schemas(schema_dir: Path) -> tuple[dict[str, dict[str, Any]], Registry]:
    schemas = {path.name: read_json(path) for path in sorted(schema_dir.glob("*.schema.json"))}
    resources = [(schema["$id"], Resource.from_contents(schema)) for schema in schemas.values()]
    return schemas, Registry().with_resources(resources)


def json_path(parts: Iterable[Any]) -> str:
    rendered = "$"
    for part in parts:
        rendered += f"[{part}]" if isinstance(part, int) else f".{part}"
    return rendered


def validate_document(
    document_path: Path,
    schema_name: str,
    schemas: dict[str, dict[str, Any]],
    registry: Registry,
) -> list[str]:
    schema = schemas[schema_name]
    validator_class = validator_for(schema)
    validator_class.check_schema(schema)
    validator = validator_class(
        schema,
        registry=registry,
        format_checker=FormatChecker(),
    )
    errors = []
    for error in sorted(validator.iter_errors(read_json(document_path)), key=lambda item: list(item.path)):
        errors.append(f"{document_path}: {json_path(error.path)}: {error.message}")
    return errors


def dot(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right))


def cross(left: list[float], right: list[float]) -> list[float]:
    return [
        left[1] * right[2] - left[2] * right[1],
        left[2] * right[0] - left[0] * right[2],
        left[0] * right[1] - left[1] * right[0],
    ]


def validate_rigid_matrix(matrix: list[float], label: str, tolerance: float = 1e-6) -> list[str]:
    columns = [
        [matrix[0], matrix[1], matrix[2]],
        [matrix[4], matrix[5], matrix[6]],
        [matrix[8], matrix[9], matrix[10]],
    ]
    errors = []
    for index, column in enumerate(columns):
        if not math.isclose(dot(column, column), 1.0, abs_tol=tolerance):
            errors.append(f"{label}: rotation column {index} is not unit length")
    for left, right in ((0, 1), (0, 2), (1, 2)):
        if not math.isclose(dot(columns[left], columns[right]), 0.0, abs_tol=tolerance):
            errors.append(f"{label}: rotation columns {left} and {right} are not orthogonal")
    determinant = dot(columns[0], cross(columns[1], columns[2]))
    if not math.isclose(determinant, 1.0, abs_tol=tolerance):
        errors.append(f"{label}: rotation determinant is {determinant}, expected +1")
    return errors


def parse_glb(path: Path) -> dict[str, Any]:
    payload = path.read_bytes()
    if len(payload) < 20:
        raise ValueError("file is too short to be a GLB")
    magic, version, declared_length = struct.unpack_from("<4sII", payload, 0)
    if magic != b"glTF":
        raise ValueError("invalid GLB magic")
    if version != 2:
        raise ValueError(f"unsupported GLB version {version}")
    if declared_length != len(payload):
        raise ValueError(f"declared GLB length {declared_length} differs from {len(payload)}")
    chunk_length, chunk_type = struct.unpack_from("<I4s", payload, 12)
    if chunk_type != b"JSON":
        raise ValueError("first GLB chunk is not JSON")
    chunk_end = 20 + chunk_length
    if chunk_end > len(payload):
        raise ValueError("GLB JSON chunk exceeds file length")
    return json.loads(payload[20:chunk_end].rstrip(b" \x00").decode("utf-8"))


def duplicate_values(items: list[dict[str, Any]], field: str) -> set[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for item in items:
        value = item[field]
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return duplicates


def validate_package(package_dir: Path, protocol_path: Path) -> list[str]:
    errors: list[str] = []
    manifest = read_json(package_dir / "manifest.json")
    parts_doc = read_json(package_dir / manifest["files"]["parts"])
    checksums_doc = read_json(package_dir / manifest["files"]["checksums"])
    protocol = read_json(protocol_path)

    expected_files = {"manifest.json", *manifest["files"].values()}
    actual_files = {path.name for path in package_dir.iterdir() if path.is_file()}
    if actual_files != expected_files:
        errors.append(
            f"{package_dir}: package files differ; missing={sorted(expected_files - actual_files)}, "
            f"unexpected={sorted(actual_files - expected_files)}"
        )

    assembly_guid = manifest["assembly"]["guid"]
    if parts_doc["assemblyGuid"] != assembly_guid:
        errors.append("parts.json: assemblyGuid does not match manifest")

    parts = parts_doc["parts"]
    part_by_guid = {item["guid"]: item for item in parts}

    for values, field, label in (
        (parts, "guid", "parts.json"),
        (parts, "gltfNodeName", "parts.json"),
    ):
        duplicates = duplicate_values(values, field)
        if duplicates:
            errors.append(f"{label}: duplicate {field}: {sorted(duplicates)}")

    for index, part in enumerate(parts):
        bounds = part["boundsAssemblyM"]
        if any(low > high for low, high in zip(bounds["min"], bounds["max"])):
            errors.append(f"parts.json: parts[{index}] has inverted bounds")

    model_path = package_dir / manifest["files"]["model"]
    try:
        gltf = parse_glb(model_path)
        if gltf.get("asset", {}).get("version") != "2.0":
            errors.append("assembly.glb: asset.version must be 2.0")
        actual_nodes = {node.get("name") for node in gltf.get("nodes", [])}
        expected_nodes = {part["gltfNodeName"] for part in parts}
        if actual_nodes != expected_nodes:
            errors.append("assembly.glb: named nodes do not match parts.json")
    except (ValueError, json.JSONDecodeError) as exc:
        errors.append(f"assembly.glb: {exc}")

    registration_path = package_dir / manifest["files"]["registrationModel"]
    registration_payload = registration_path.read_bytes()
    if len(registration_payload) < 48 or registration_payload[:4] != b"QSRG":
        errors.append("registration_model.bin: invalid QSRG header")
    else:
        _, major, minor, point_count, edge_count = struct.unpack_from(
            "<4sHHII", registration_payload, 0
        )
        expected_length = 48 + (point_count + edge_count) * 24
        if (major, minor) != (1, 0) or point_count < 1_024:
            errors.append("registration_model.bin: unsupported or undersampled QSRG model")
        if len(registration_payload) != expected_length:
            errors.append("registration_model.bin: payload length differs from QSRG header")

    thumbnail_path = package_dir / manifest["files"]["thumbnail"]
    if not thumbnail_path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
        errors.append("thumbnail.png: invalid PNG signature")

    expected_checksum_paths = expected_files - {manifest["files"]["checksums"]}
    checksum_entries = checksums_doc["files"]
    checksum_paths = {entry["path"] for entry in checksum_entries}
    if len(checksum_entries) != len(checksum_paths):
        errors.append("checksums.json: duplicate file paths")
    if checksum_paths != expected_checksum_paths:
        errors.append("checksums.json: entries do not cover package content exactly")
    checksum_by_path = {entry["path"]: entry for entry in checksum_entries}
    for relative_path in sorted(expected_checksum_paths):
        path = package_dir / relative_path
        entry = checksum_by_path.get(relative_path)
        if entry is None or not path.is_file():
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if entry["sha256"] != digest:
            errors.append(f"checksums.json: wrong digest for {relative_path}")
        if entry["byteLength"] != path.stat().st_size:
            errors.append(f"checksums.json: wrong byteLength for {relative_path}")

    assembly = protocol["assembly"]
    for field, expected in (
        ("guid", assembly_guid),
        ("positionNumber", manifest["assembly"]["positionNumber"]),
        ("revision", manifest["assembly"]["revision"]),
    ):
        if assembly[field] != expected:
            errors.append(f"inspection-protocol.json: assembly.{field} does not match package")
    model_checksum = checksum_by_path.get(manifest["files"]["model"], {}).get("sha256")
    if assembly["modelSha256"] != model_checksum:
        errors.append("inspection-protocol.json: modelSha256 does not match assembly.glb")

    world_from_assembly = protocol["registration"]["worldFromAssembly"]
    errors.extend(
        validate_rigid_matrix(
            world_from_assembly,
            "inspection-protocol.json: registration.worldFromAssembly",
        )
    )
    photo_ids = {photo["photoId"] for photo in protocol["photos"]}
    issue_ids = [issue["issueId"] for issue in protocol["issues"]]
    if len(issue_ids) != len(set(issue_ids)):
        errors.append("inspection-protocol.json: issueId values must be unique")
    for index, issue in enumerate(protocol["issues"]):
        if "partGuid" in issue and issue["partGuid"] not in part_by_guid:
            errors.append(f"inspection-protocol.json: issues[{index}] references an unknown part")
        missing_photos = set(issue["photoIds"]) - photo_ids
        if missing_photos:
            errors.append(
                f"inspection-protocol.json: issues[{index}] references unknown photos {sorted(missing_photos)}"
            )
    return errors


def validate_repository(
    contract_root: Path = CONTRACT_ROOT,
    package_dir: Path = DEFAULT_PACKAGE,
    protocol_path: Path = DEFAULT_PROTOCOL,
) -> list[str]:
    schemas, registry = load_schemas(contract_root / "schemas")
    errors: list[str] = []
    for schema in schemas.values():
        validator_for(schema).check_schema(schema)
    for document_name, schema_name in DOCUMENT_SCHEMAS.items():
        errors.extend(validate_document(package_dir / document_name, schema_name, schemas, registry))
    errors.extend(
        validate_document(
            protocol_path,
            "inspection-protocol.schema.json",
            schemas,
            registry,
        )
    )
    if errors:
        return errors
    return validate_package(package_dir, protocol_path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--protocol", type=Path, default=DEFAULT_PROTOCOL)
    args = parser.parse_args()

    errors = validate_repository(
        package_dir=args.package.resolve(),
        protocol_path=args.protocol.resolve(),
    )
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("OK: schemas, XR package, checksums and inspection protocol are valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
