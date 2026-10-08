import hashlib
import importlib.util
import json
import struct
import zipfile
from pathlib import Path

import ifcopenshell.guid
import numpy as np

from qs_desktop_viewer.catalog import SourcePackage
from qs_desktop_viewer.mobile_package import _part_kind, build_mobile_package
from qs_desktop_viewer.registration_model import inspect_registration_model


def test_mobile_fastener_classes_are_exported_as_bolt_groups() -> None:
    assert _part_kind("IfcMechanicalFastener", "Bolt assembly") == (
        "BOLT_GROUP",
        False,
    )
    assert _part_kind("IfcFastener", "Fastener") == ("BOLT_GROUP", False)


def test_mobile_package_separates_fabrication_marks_from_physical_fasteners() -> None:
    assert _part_kind(
        "IfcMechanicalFastener",
        "Bolt assembly",
        "FABRICATION_MARK",
    ) == ("FABRICATION_MARK", False)
    assert _part_kind(
        "IfcMechanicalFastener",
        "Bolt assembly",
        "PHYSICAL_FASTENER",
    ) == ("BOLT_GROUP", False)


def fake_glb(part_global_id: str) -> bytes:
    vertices = np.asarray([[0, 0, 0], [1, 0, 0], [0, 1, 0]], dtype="<f4").tobytes()
    indices = np.asarray([0, 1, 2], dtype="<u4").tobytes()
    binary = vertices + indices
    gltf = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "BLECH", "mesh": 0}],
        "meshes": [
            {
                "name": "BLECH",
                "extras": {
                    "sourceGlobalId": part_global_id,
                    "ifcClass": "IfcPlate",
                    "name": "BLECH",
                    "partPositionNumber": "100001.501",
                    "profile": "BL10",
                    "typeName": "BL10",
                    "material": "S355",
                    "weightKg": 14.25,
                    "surfaceAreaM2": 1.75,
                },
                "primitives": [
                    {
                        "attributes": {"POSITION": 0},
                        "indices": 1,
                        "mode": 4,
                    }
                ],
            }
        ],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": len(vertices), "target": 34962},
            {
                "buffer": 0,
                "byteOffset": len(vertices),
                "byteLength": len(indices),
                "target": 34963,
            },
        ],
        "accessors": [
            {
                "bufferView": 0,
                "componentType": 5126,
                "count": 3,
                "type": "VEC3",
                "min": [0, 0, 0],
                "max": [1, 1, 0],
            },
            {"bufferView": 1, "componentType": 5125, "count": 3, "type": "SCALAR"},
        ],
    }
    json_chunk = json.dumps(gltf, separators=(",", ":")).encode()
    json_chunk += b" " * (-len(json_chunk) % 4)
    binary += b"\0" * (-len(binary) % 4)
    length = 12 + 8 + len(json_chunk) + 8 + len(binary)
    return b"".join(
        (
            struct.pack("<4sII", b"glTF", 2, length),
            struct.pack("<I4s", len(json_chunk), b"JSON"),
            json_chunk,
            struct.pack("<I4s", len(binary), b"BIN\0"),
            binary,
        )
    )


def test_mobile_package_matches_xr_contracts(tmp_path: Path) -> None:
    part_global_id = ifcopenshell.guid.compress("44444444444444448444444444444444")
    occurrence_id = ifcopenshell.guid.compress("33333333333343338333333333333333")
    glb_path = tmp_path / "source.glb"
    glb_path.write_bytes(fake_glb(part_global_id))
    package = SourcePackage(
        project_id="Project-A",
        project_name="Project A",
        producer_folder="desktop",
        producer="DESKTOP",
        source_id="desktop--100001.1",
        producer_dir=tmp_path,
        package_dir=tmp_path,
        raw={"positionNumber": "100001.1", "revision": 2},
    )
    output = build_mobile_package(
        package,
        {"sourceGlobalId": occurrence_id},
        glb_path,
        tmp_path / "package.qsxr",
    )
    extracted = tmp_path / "extracted"
    with zipfile.ZipFile(output) as archive:
        archive.extractall(extracted)
        assert set(archive.namelist()) == {
            "manifest.json",
            "assembly.glb",
            "registration_model.bin",
            "parts.json",
            "thumbnail.png",
            "checksums.json",
        }

    manifest = json.loads((extracted / "manifest.json").read_text())
    parts = json.loads((extracted / "parts.json").read_text())
    assert manifest["schemaVersion"] == "2.0.0"
    assert manifest["assembly"]["positionNumber"] == "100001.1"
    assert manifest["assembly"]["revision"] == 2
    assert manifest["files"]["registrationModel"] == "registration_model.bin"
    registration = inspect_registration_model(
        (extracted / "registration_model.bin").read_bytes()
    )
    assert registration.point_count >= 1_024
    assert registration.edge_count == 3
    assert parts["parts"][0]["gltfNodeName"].startswith("part:")
    assert parts["parts"][0]["positionNumber"] == "100001.501"
    assert parts["parts"][0]["properties"]["material"] == "S355"
    assert parts["parts"][0]["properties"]["weightKg"] == 14.25
    assert parts["parts"][0]["properties"]["surfaceAreaM2"] == 1.75
    assert "typeName" not in parts["parts"][0]["properties"]

    checksums = json.loads((extracted / "checksums.json").read_text())
    for entry in checksums["files"]:
        payload = (extracted / entry["path"]).read_bytes()
        assert hashlib.sha256(payload).hexdigest() == entry["sha256"]
        assert len(payload) == entry["byteLength"]

    validator_path = (
        Path(__file__).resolve().parents[3]
        / "packages/xr-contracts/tools/validate_examples.py"
    )
    spec = importlib.util.spec_from_file_location("xr_validate", validator_path)
    assert spec and spec.loader
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    schemas, registry = validator.load_schemas(validator.CONTRACT_ROOT / "schemas")
    for filename in ("manifest.json", "parts.json", "checksums.json"):
        schema_name = validator.DOCUMENT_SCHEMAS[filename]
        assert validator.validate_document(
            extracted / filename, schema_name, schemas, registry
        ) == []
