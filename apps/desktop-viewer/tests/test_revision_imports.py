from pathlib import Path

import ifcopenshell
import pytest
from fastapi.testclient import TestClient

from qs_desktop_viewer.api import create_app


@pytest.mark.integration
def test_ifc_import_only_revises_changed_positions(tmp_path: Path) -> None:
    repository_root = Path(__file__).resolve().parents[3]
    source = (
        repository_root
        / "artifacts"
        / "Modul1-4"
        / "script"
        / "assemblies"
        / "731006_0"
        / "assembly.ifc"
    )
    if not source.is_file():
        pytest.skip("No compact extracted IFC fixture available")

    artifacts = tmp_path / "artifacts"
    client = TestClient(
        create_app(artifacts, repository_root / "apps" / "desktop-viewer" / "frontend" / "dist")
    )

    with source.open("rb") as handle:
        created = client.post(
            "/api/projects",
            data={"project_name": "Revision Test"},
            files={"ifc": ("assembly.ifc", handle, "application/octet-stream")},
        )
    assert created.status_code == 201
    assert created.json()["summary"]["createdPositions"] == 1

    with source.open("rb") as handle:
        unchanged = client.post(
            "/api/projects/Revision_Test/imports",
            files={"ifc": ("assembly.ifc", handle, "application/octet-stream")},
        )
    assert unchanged.status_code == 200
    assert unchanged.json()["summary"]["unchangedPositions"] == 1
    assert unchanged.json()["summary"]["revisedPositions"] == 0

    changed_path = tmp_path / "changed.ifc"
    model = ifcopenshell.open(source)
    assembly = sorted(model.by_type("IfcElementAssembly"), key=lambda item: item.GlobalId)[0]
    member = next(
        item
        for relation in assembly.IsDecomposedBy
        if relation.is_a("IfcRelAggregates")
        for item in relation.RelatedObjects
        if not item.is_a("IfcMechanicalFastener")
    )
    member.Name = f"{member.Name or 'Part'} REVISION TEST"
    model.write(changed_path)
    with changed_path.open("rb") as handle:
        changed = client.post(
            "/api/projects/Revision_Test/imports",
            files={"ifc": ("changed.ifc", handle, "application/octet-stream")},
        )
    assert changed.status_code == 200
    assert changed.json()["summary"]["revisedPositions"] == 1

    catalog = client.get("/api/catalog").json()
    position = catalog["projects"][0]["drawings"][0]["positions"][0]
    assert position["revision"] == 2
    assert position["revisionCount"] == 2
    base = f"/api/projects/Revision_Test/sources/{position['id']}"
    assert client.get(base, params={"revision": 1}).json()["source"]["revision"] == 1
    assert client.get(base, params={"revision": 2}).json()["source"]["revision"] == 2

