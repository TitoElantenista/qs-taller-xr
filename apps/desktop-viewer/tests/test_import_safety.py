import json
from pathlib import Path

import pytest

from qs_desktop_viewer.import_history import ImportHistoryStore
from qs_desktop_viewer.index_ifc import (
    ImportValidationError,
    _validate_import_position_data,
    build_desktop_index,
)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def test_preflight_rejects_an_update_without_assembly_positions() -> None:
    diagnostics = {
        "schema": "IFC4",
        "viewDescription": "ViewDefinition [ReferenceView_V1.2]",
        "assemblyCount": 8538,
        "positionPropertyCount": 0,
        "tagCount": 0,
        "positionMetadataCount": 0,
        "assignedOccurrenceCount": 0,
        "assignedPositionCount": 0,
        "businessPositions": [],
    }
    previous = {
        "assemblies": [
            {"positionNumber": "191001.1"},
            {"positionNumber": "191001.2"},
        ]
    }

    with pytest.raises(ImportValidationError) as captured:
        _validate_import_position_data(diagnostics, previous)

    detail = captured.value.as_detail()
    assert detail["code"] == "IFC_POSITION_DATA_MISSING"
    assert detail["imported"] is False
    assert detail["stats"]["positionsMissingFromUpdate"] == 2


def test_preflight_allows_unassigned_assemblies_in_a_new_project() -> None:
    diagnostics = {
        "schema": "IFC4",
        "viewDescription": "ReferenceView",
        "assemblyCount": 12,
        "positionPropertyCount": 0,
        "tagCount": 0,
        "positionMetadataCount": 0,
        "assignedOccurrenceCount": 0,
        "assignedPositionCount": 0,
        "businessPositions": [],
    }
    _validate_import_position_data(diagnostics, {})


def test_failed_preflight_does_not_create_project_artifacts(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    class FileDescription:
        description = ["ViewDefinition [ReferenceView_V1.2]"]

    class Header:
        file_description = FileDescription()

    class EmptyModel:
        schema = "IFC4"
        header = Header()

        @staticmethod
        def by_type(name: str):
            return []

    source = tmp_path / "empty.ifc"
    source.write_text("ISO-10303-21;", encoding="utf-8")
    monkeypatch.setattr(
        "qs_desktop_viewer.index_ifc.ifcopenshell.open", lambda _: EmptyModel()
    )

    with pytest.raises(ImportValidationError):
        build_desktop_index(source, tmp_path / "artifacts", "Rejected Project", False)

    assert not (tmp_path / "artifacts" / "Rejected_Project").exists()


def test_legacy_latest_import_rollback_restores_retired_positions(tmp_path: Path) -> None:
    desktop = tmp_path / "Project-A" / "desktop"
    target_hash = "a" * 64
    target_id = target_hash[:16]
    index = {
        "schemaVersion": "1.0.0",
        "project": {"name": "Project A", "folder": "Project-A"},
        "lastImport": {
            "id": target_id,
            "sourceFile": "bad.ifc",
            "sourceSha256": target_hash,
            "importedAt": "2026-07-02T12:00:00Z",
            "createdPositions": 0,
            "revisedPositions": 1,
            "retiredPositions": 1,
        },
        "imports": [
            {
                "id": target_id,
                "sourceFile": "bad.ifc",
                "sourceSha256": target_hash,
                "importedAt": "2026-07-02T12:00:00Z",
                "createdPositions": 0,
                "revisedPositions": 1,
                "retiredPositions": 1,
            }
        ],
        "assemblies": [
            {
                "sourceKey": "PENDIENTES",
                "positionNumber": "__UNASSIGNED__",
                "folder": "revisions/PENDIENTES/rev-0002",
                "occurrenceCount": 20,
                "partCount": 30,
                "geometryFingerprint": "new",
                "revision": 2,
                "revisionCount": 2,
                "revisions": [
                    {
                        "revision": 1,
                        "folder": "sources/PENDIENTES",
                        "geometryFingerprint": "old",
                        "occurrenceCount": 5,
                        "partCount": 8,
                    },
                    {
                        "revision": 2,
                        "folder": "revisions/PENDIENTES/rev-0002",
                        "geometryFingerprint": "new",
                        "sourceFile": "bad.ifc",
                        "sourceSha256": target_hash,
                        "importedAt": "2026-07-02T12:00:00Z",
                        "occurrenceCount": 20,
                        "partCount": 30,
                    },
                ],
            }
        ],
        "retiredAssemblies": [
            {
                "positionNumber": "191001.1",
                "sourceKey": "191001.1",
                "folder": "revisions/191001.1/rev-0001",
                "occurrenceCount": 2,
                "partCount": 4,
                "geometryFingerprint": "position-old",
                "sourceFile": "bad.ifc",
                "sourceSha256": target_hash,
                "importedAt": "2026-07-02T12:00:00Z",
            }
        ],
    }
    write_json(desktop / "project-index.json", index)
    (desktop / "sources" / "PENDIENTES").mkdir(parents=True)
    (desktop / "sources" / "191001.1").mkdir(parents=True)
    (desktop / "sources" / "191001.1" / "source_manifest.json").write_text(
        "{}", encoding="utf-8"
    )
    (desktop / "revisions" / "PENDIENTES" / "rev-0002").mkdir(parents=True)
    (desktop / "revisions" / "191001.1" / "rev-0001").mkdir(parents=True)
    (desktop / "imports" / target_id).mkdir(parents=True)
    write_json(
        desktop / "measurements.json",
        {
            "schemaVersion": "1.0.0",
            "measurements": [
                {
                    "id": "remove",
                    "sourceId": "desktop--PENDIENTES",
                    "revision": 2,
                },
                {
                    "id": "keep",
                    "sourceId": "desktop--PENDIENTES",
                    "revision": 1,
                },
                {
                    "id": "keep-position",
                    "sourceId": "desktop--191001.1",
                    "revision": 1,
                },
            ],
        },
    )

    store = ImportHistoryStore(tmp_path)
    history = store.list("Project-A")
    assert history["imports"][0]["canDelete"] is True

    result = store.delete_latest("Project-A", target_id)
    restored = json.loads((desktop / "project-index.json").read_text(encoding="utf-8"))
    assert result["mode"] == "LEGACY_RECONSTRUCTION"
    assert {item["positionNumber"] for item in restored["assemblies"]} == {
        "191001.1",
        "__UNASSIGNED__",
    }
    assert restored["imports"] == []
    assert not (desktop / "revisions" / "PENDIENTES" / "rev-0002").exists()
    restored_position = next(
        item for item in restored["assemblies"] if item["positionNumber"] == "191001.1"
    )
    assert restored_position["folder"] == "sources/191001.1"
    assert not (desktop / "revisions" / "191001.1" / "rev-0001").exists()
    measurements = json.loads((desktop / "measurements.json").read_text(encoding="utf-8"))
    assert [item["id"] for item in measurements["measurements"]] == [
        "keep",
        "keep-position",
    ]


def test_snapshot_rollback_keeps_data_for_an_unchanged_revision(tmp_path: Path) -> None:
    desktop = tmp_path / "Project-B" / "desktop"
    target_hash = "b" * 64
    target_id = target_hash[:16]
    previous = {
        "schemaVersion": "1.0.0",
        "project": {"name": "Project B", "folder": "Project-B"},
        "assemblies": [
            {
                "sourceKey": "191001.1",
                "positionNumber": "191001.1",
                "folder": "sources/191001.1",
                "occurrenceCount": 1,
                "partCount": 3,
                "revision": 1,
                "revisionCount": 1,
                "revisions": [
                    {
                        "revision": 1,
                        "folder": "sources/191001.1",
                        "occurrenceCount": 1,
                        "partCount": 3,
                    }
                ],
            }
        ],
        "retiredAssemblies": [],
        "imports": [],
        "lastImport": None,
    }
    current = json.loads(json.dumps(previous))
    current["assemblies"][0]["folder"] = (
        "revisions/191001.1/rev-0001-state-bbbbbbbb"
    )
    current["assemblies"][0]["revisions"][0] = {
        "revision": 1,
        "folder": "revisions/191001.1/rev-0001-state-bbbbbbbb",
        "sourceFile": "same-geometry.ifc",
        "sourceSha256": target_hash,
        "importedAt": "2026-07-02T14:00:00Z",
        "occurrenceCount": 2,
        "partCount": 6,
        "changeType": "UNCHANGED_STATE",
    }
    imported = {
        "id": target_id,
        "sourceFile": "same-geometry.ifc",
        "sourceSha256": target_hash,
        "importedAt": "2026-07-02T14:00:00Z",
        "createdPositions": 0,
        "revisedPositions": 0,
        "unchangedPositions": 1,
        "retiredPositions": 0,
    }
    current["imports"] = [imported]
    current["lastImport"] = imported
    write_json(desktop / "project-index.json", current)
    write_json(desktop / "index-history" / f"{target_id}.before.json", previous)
    (desktop / "revisions" / "191001.1" / "rev-0001-state-bbbbbbbb").mkdir(
        parents=True
    )
    (desktop / "imports" / target_id).mkdir(parents=True)
    write_json(
        desktop / "measurements.json",
        {
            "schemaVersion": "1.0.0",
            "measurements": [
                {
                    "id": "keep",
                    "sourceId": "desktop--191001.1",
                    "revision": 1,
                }
            ],
        },
    )

    result = ImportHistoryStore(tmp_path).delete_latest("Project-B", target_id)
    restored = json.loads((desktop / "project-index.json").read_text(encoding="utf-8"))
    assert result["mode"] == "SNAPSHOT"
    assert restored == previous
    assert not (
        desktop / "revisions" / "191001.1" / "rev-0001-state-bbbbbbbb"
    ).exists()
    measurements = json.loads((desktop / "measurements.json").read_text(encoding="utf-8"))
    assert [item["id"] for item in measurements["measurements"]] == ["keep"]
