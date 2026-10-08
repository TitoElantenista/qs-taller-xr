import json
from pathlib import Path

from fastapi.testclient import TestClient

from qs_desktop_viewer.api import create_app


def write_project(artifacts: Path, project_id: str) -> Path:
    project = artifacts / project_id
    desktop = project / "desktop"
    desktop.mkdir(parents=True)
    (desktop / "project-index.json").write_text(
        json.dumps(
            {
                "schemaVersion": "1.0.0",
                "project": {"name": project_id, "folder": project_id},
                "assemblies": [],
            }
        ),
        encoding="utf-8",
    )
    return project


def test_delete_project_removes_only_the_selected_project(tmp_path: Path) -> None:
    artifacts = tmp_path / "artifacts"
    target = write_project(artifacts, "Project-A")
    keep = write_project(artifacts, "Project-B")
    nested_artifact = (
        target
        / "desktop"
        / "revisions"
        / "123456.1"
        / "rev-0001"
        / "model.glb"
    )
    nested_artifact.parent.mkdir(parents=True)
    nested_artifact.write_bytes(b"model")
    client = TestClient(create_app(artifacts, tmp_path / "missing-frontend"))

    response = client.delete("/api/projects/Project-A")

    assert response.status_code == 204
    assert not target.exists()
    assert (keep / "desktop" / "project-index.json").is_file()
    assert client.delete("/api/projects/Project-A").status_code == 404


def test_delete_project_rejects_an_unindexed_artifact_folder(tmp_path: Path) -> None:
    artifacts = tmp_path / "artifacts"
    upload = artifacts / ".uploads"
    upload.mkdir(parents=True)
    marker = upload / "pending.ifc"
    marker.write_bytes(b"pending")
    client = TestClient(create_app(artifacts, tmp_path / "missing-frontend"))

    response = client.delete("/api/projects/.uploads")

    assert response.status_code == 404
    assert marker.is_file()


def test_delete_project_does_not_follow_a_project_symlink(tmp_path: Path) -> None:
    artifacts = tmp_path / "artifacts"
    external = write_project(tmp_path / "external", "External-Project")
    artifacts.mkdir()
    (artifacts / "Linked-Project").symlink_to(external, target_is_directory=True)
    client = TestClient(create_app(artifacts, tmp_path / "missing-frontend"))

    response = client.delete("/api/projects/Linked-Project")

    assert response.status_code == 404
    assert (external / "desktop" / "project-index.json").is_file()
