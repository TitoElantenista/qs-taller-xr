import json
from pathlib import Path

from qs_desktop_viewer.catalog import (
    UNASSIGNED_POSITION_NUMBER,
    ProjectCatalog,
    parse_position_number,
)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def test_position_number_supports_canonical_and_legacy_formats() -> None:
    assert parse_position_number("123456.17") == ("123456", "17")
    assert parse_position_number("731006/0(?)") == ("731006", "0")
    assert parse_position_number("P-3", "654321") == ("654321", "3")
    assert parse_position_number("unknown") == ("SIN-DIBUJO", "unknown")
    assert parse_position_number(UNASSIGNED_POSITION_NUMBER) == (
        "PENDIENTES",
        "Sin Positionsnummer",
    )


def test_catalog_groups_and_sorts_positions_by_drawing(tmp_path: Path) -> None:
    producer = tmp_path / "Werk-A" / "script"
    assemblies = [
        {
            "positionNumber": "123456.11",
            "folder": "assemblies/123456.11",
            "occurrenceCount": 2,
            "partCount": 8,
        },
        {
            "positionNumber": "123456.2",
            "folder": "assemblies/123456.2",
            "occurrenceCount": 1,
            "partCount": 3,
        },
        {
            "positionNumber": "100001/7(?)",
            "folder": "assemblies/100001_7",
            "numberingStatus": "QUESTIONABLE",
            "occurrenceCount": 4,
            "partCount": 12,
        },
    ]
    write_json(
        producer / "project-index.json",
        {
            "producer": "IFC_SCRIPT",
            "project": {"name": "Werk A"},
            "assemblies": assemblies,
        },
    )
    for entry in assemblies:
        write_json(producer / entry["folder"] / "source_manifest.json", {})

    document = ProjectCatalog(tmp_path).document()

    assert len(document["projects"]) == 1
    project = document["projects"][0]
    assert [item["number"] for item in project["drawings"]] == ["100001", "123456"]
    assert [item["position"] for item in project["drawings"][1]["positions"]] == ["2", "11"]
    assert project["drawingCount"] == 2
    assert project["positionCount"] == 3


def test_catalog_exposes_desktop_fastener_audit(tmp_path: Path) -> None:
    producer = tmp_path / "Werk-A" / "desktop"
    write_json(
        producer / "project-index.json",
        {
            "producer": "IFC_DESKTOP_INDEX",
            "project": {"name": "Werk A"},
            "sourceGeometryAudit": {
                "physicalFastenerCount": 12,
                "fabricationHoleCount": 7,
                "fabricationMarkCount": 3,
            },
            "assemblies": [
                {
                    "positionNumber": "123456.1",
                    "folder": "revisions/123456.1/rev-0001",
                    "occurrenceCount": 1,
                    "partCount": 2,
                }
            ],
        },
    )
    write_json(
        producer / "revisions/123456.1/rev-0001/source_manifest.json",
        {},
    )

    project = ProjectCatalog(tmp_path).document()["projects"][0]

    assert project["sourceGeometryAudit"]["physicalFastenerCount"] == 12
    assert project["sourceGeometryAudit"]["fabricationHoleCount"] == 7
    assert project["sourceGeometryAudit"]["fabricationMarkCount"] == 3
