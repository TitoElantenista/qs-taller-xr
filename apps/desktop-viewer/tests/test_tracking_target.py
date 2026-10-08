import io
import hashlib
import json
import zipfile
from pathlib import Path

import numpy as np
import pytest

from qs_desktop_viewer.mobile_package import _read_glb_json, _write_glb_json
from qs_desktop_viewer.registration_model import _accessor_array, _read_glb
from qs_desktop_viewer.tracking_target import build_tracking_target, export_tracking_target


ROOT = Path(__file__).resolve().parents[3]
HEB_QSXR = (
    ROOT
    / "apps"
    / "mobile-android"
    / "app"
    / "src"
    / "debug"
    / "assets"
    / "bundled"
    / "heb140-300.qsxr"
)


def _heb_with_fastener_instances() -> bytes:
    with zipfile.ZipFile(HEB_QSXR) as source:
        manifest = json.loads(source.read("manifest.json"))
        parts = source.read("parts.json")
        registration = source.read("registration_model.bin")
        document, chunks = _read_glb_json(source.read("assembly.glb"))
    for index, (ifc_class, offset) in enumerate(
        (("IfcMechanicalFastener", 4.0), ("IfcFastener", -4.0)),
        start=1,
    ):
        document["nodes"].append(
            {
                "name": f"excluded-fastener-{index}",
                "mesh": 0,
                "translation": [offset, 0.0, 0.0],
                "extras": {
                    "ifcClass": ifc_class,
                    "fastenerCategory": "FABRICATION_MARK",
                },
            }
        )
        document["scenes"][0]["nodes"].append(len(document["nodes"]) - 1)
    model = _write_glb_json(document, chunks)
    manifest["files"] = {
        "model": "assembly.glb",
        "parts": "parts.json",
        "registrationModel": "registration_model.bin",
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        archive.writestr("parts.json", parts)
        archive.writestr("assembly.glb", model)
        archive.writestr("registration_model.bin", registration)
    return output.getvalue()


def test_tracking_target_excludes_fasteners_and_fabrication_marks() -> None:
    artifact = build_tracking_target(_heb_with_fastener_instances(), "mixed.qsxr")

    assert artifact.metadata["filter"]["includedNodeCount"] == 1
    assert artifact.metadata["filter"]["excludedNodeCount"] == 2
    assert artifact.metadata["filter"]["excludedByReason"] == {
        "ifc-fastener-class": 2
    }
    assert artifact.metadata["geometry"]["dimensionsM"] == pytest.approx(
        [0.140, 0.140, 0.300], abs=1e-6
    )
    assert b"IfcMechanicalFastener" not in artifact.glb
    assert b"IfcFastener" not in artifact.glb
    assert b"FABRICATION_MARK" not in artifact.glb
    assert b"IfcMechanicalFastener" not in artifact.obj
    assert b"IfcFastener" not in artifact.obj
    assert b"FABRICATION_MARK" not in artifact.obj

    obj_lines = artifact.obj.decode("ascii").splitlines()
    vertices = [
        [float(value) for value in line.split()[1:]]
        for line in obj_lines
        if line.startswith("v ")
    ]
    faces = [line for line in obj_lines if line.startswith("f ")]
    glb_document, glb_binary = _read_glb(artifact.glb)
    glb_vertices = _accessor_array(glb_document, glb_binary, 0)
    assert len(vertices) == artifact.metadata["geometry"]["vertexCount"]
    assert len(faces) == artifact.metadata["geometry"]["triangleCount"]
    assert np.array_equal(np.asarray(vertices, dtype=np.float32), glb_vertices)
    assert [max(axis) - min(axis) for axis in zip(*vertices)] == pytest.approx(
        [0.140, 0.140, 0.300], abs=1e-6
    )
    assert artifact.metadata["objTarget"]["sha256"] == hashlib.sha256(
        artifact.obj
    ).hexdigest()
    assert artifact.metadata["registrationReference"]["sha256"] == hashlib.sha256(
        artifact.registration
    ).hexdigest()
    assert artifact.registration.startswith(b"QSRG")


def test_tracking_target_preserves_heb_metric_bounds_and_is_deterministic() -> None:
    source = HEB_QSXR.read_bytes()

    first = build_tracking_target(source, HEB_QSXR.name)
    second = build_tracking_target(source, HEB_QSXR.name)

    assert first.glb == second.glb
    assert first.obj == second.obj
    assert first.registration == second.registration
    # This is the immutable cross-device target identity: the phone reads the
    # QSRG checksum from its QSXR while the GPU station stores the exact OBJ.
    # Both artifacts must have been derived from the very same filtered geometry.
    with zipfile.ZipFile(io.BytesIO(source)) as package:
        manifest = json.loads(package.read("manifest.json"))
        registration_name = manifest["files"]["registrationModel"]
        assert first.registration == package.read(registration_name)
    assert first.metadata == second.metadata
    assert first.metadata["dataHandling"] == {
        "classification": "CONFIDENTIAL_COMPANY_CAD",
        "cloudUploadAllowed": False,
        "externalProcessingAllowed": False,
        "allowedProcessing": ["ON_DEVICE", "ON_PREMISE_LAN"],
        "exporterUsesNetwork": False,
    }
    assert first.metadata["coordinateSystem"]["assemblyFromTarget"] == [
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
    binding = first.metadata["provisioningBinding"]
    assert binding["targetId"] == manifest["packageId"]
    assert binding["package"] == {
        "packageId": manifest["packageId"],
        "sha256": hashlib.sha256(source).hexdigest(),
    }
    assert binding["sourceAssembly"] == {
        "guid": manifest["assembly"]["guid"],
        "glbSha256": hashlib.sha256(
            zipfile.ZipFile(io.BytesIO(source)).read("assembly.glb")
        ).hexdigest(),
    }
    assert binding["artifacts"]["registrationReference"]["sha256"] == hashlib.sha256(
        first.registration
    ).hexdigest()
    assert binding["artifacts"]["rasterModel"]["sha256"] == hashlib.sha256(
        first.obj
    ).hexdigest()
    assert binding["targetCoordinates"] == {
        "frame": "QSXR_ASSEMBLY",
        "origin": "QSXR_ASSEMBLY_LOCAL_ORIGIN",
        "handedness": "RIGHT_HANDED",
        "upAxis": "Y",
        "linearUnit": "m",
        "matrixStorage": "COLUMN_MAJOR",
        "vectorConvention": "COLUMN_VECTOR",
        "transformNotation": "PARENT_FROM_CHILD",
        "assemblyFromTarget": first.metadata["coordinateSystem"]["assemblyFromTarget"],
    }
    assert first.metadata["geometry"]["boundsM"]["min"] == pytest.approx(
        [-0.070, -0.070, -0.150], abs=1e-6
    )
    assert first.metadata["geometry"]["boundsM"]["max"] == pytest.approx(
        [0.070, 0.070, 0.150], abs=1e-6
    )
    assert first.metadata["geometry"]["dimensionsM"] == pytest.approx(
        [0.140, 0.140, 0.300], abs=1e-6
    )


def test_exported_bundle_manifest_names_every_bound_artifact(tmp_path: Path) -> None:
    files = export_tracking_target(HEB_QSXR, tmp_path, base_name="heb")

    document = json.loads(files.metadata_path.read_text())
    artifacts = document["provisioningBinding"]["artifacts"]
    assert artifacts == {
        "rasterModel": {
            "fileName": "heb-tracking.obj",
            "sha256": hashlib.sha256(files.obj_path.read_bytes()).hexdigest(),
        },
        "registrationReference": {
            "fileName": "heb-tracking.qsrg",
            "sha256": hashlib.sha256(files.registration_path.read_bytes()).hexdigest(),
        },
        "trackingGlb": {
            "fileName": "heb-tracking.glb",
            "sha256": hashlib.sha256(files.glb_path.read_bytes()).hexdigest(),
        },
    }


def test_export_rejects_qsrg_not_derived_from_the_declared_assembly() -> None:
    with zipfile.ZipFile(HEB_QSXR) as source:
        entries = {name: source.read(name) for name in source.namelist()}
    manifest = json.loads(entries["manifest.json"])
    manifest["files"].pop("checksums")
    entries["manifest.json"] = json.dumps(manifest).encode()
    damaged = bytearray(entries["registration_model.bin"])
    damaged[-1] ^= 1
    entries["registration_model.bin"] = bytes(damaged)
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in entries.items():
            if name != "checksums.json":
                archive.writestr(name, payload)

    with pytest.raises(ValueError, match="does not match the QSXR registration reference"):
        build_tracking_target(output.getvalue(), "mismatched.qsxr")
