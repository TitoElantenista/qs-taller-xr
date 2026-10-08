import json
import struct
from pathlib import Path

import pytest

from qs_desktop_viewer.catalog import ProjectCatalog, load_occurrences
from qs_desktop_viewer.glb import build_occurrence_glb


@pytest.mark.integration
def test_real_ifc_occurrence_builds_valid_glb_header(tmp_path: Path) -> None:
    repository_root = Path(__file__).resolve().parents[3]
    packages = ProjectCatalog(repository_root / "artifacts").packages()
    if not packages:
        pytest.skip("No extracted IFC packages available")
    package = packages[0]
    occurrence = load_occurrences(package)[0]
    output = tmp_path / "occurrence.glb"

    metadata = build_occurrence_glb(
        package.package_dir,
        occurrence["sourceGlobalId"],
        output,
        package.ifc_path,
    )

    magic, version, byte_length = struct.unpack("<4sII", output.read_bytes()[:12])
    assert magic == b"glTF"
    assert version == 2
    assert byte_length == output.stat().st_size
    # IFC hole proxies are consumed as boolean cutters and therefore no longer
    # appear as renderable part nodes.
    assert metadata["partCount"] > 0
    assert metadata["generatedHoleCount"] >= 0
    assert metadata["holeCutFailureCount"] >= 0
    json_length, chunk_type = struct.unpack("<I4s", output.read_bytes()[12:20])
    assert chunk_type == b"JSON"
    gltf = json.loads(output.read_bytes()[20 : 20 + json_length])
    assert (
        gltf["extras"]["externalFastenerCount"]
        == metadata["externalFastenerCount"]
    )
    assert gltf["extras"]["fabricationHoles"]["generatedHoleCount"] >= 0
    quality = gltf["extras"]["geometryQuality"]
    assert quality["sourceNormals"] is True
    assert quality["linearDeflectionMetres"] <= 0.00025
    part_extras = [node.get("extras", {}) for node in gltf["nodes"]]
    positioned_parts = [item for item in part_extras if item.get("partPositionNumber")]
    assert positioned_parts
    assert all(item["partDrawingNumber"].isdigit() for item in positioned_parts)
    assert all(len(item["partDrawingNumber"]) == 6 for item in positioned_parts)
    assert all(item["partNumber"].isdigit() for item in positioned_parts)
    assert all(item.get("assemblyPositionNumber") for item in positioned_parts)
    assert all(item.get("material") for item in positioned_parts)
